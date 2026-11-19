package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.item.RealisticItems;
import io.github.uddinnadd.realisticificator.physics.Densities;
import io.github.uddinnadd.realisticificator.physics.EntityContact;
import io.github.uddinnadd.realisticificator.physics.Materials;
import io.github.uddinnadd.realisticificator.physics.PhysicsObject;
import io.github.uddinnadd.realisticificator.physics.RigidMath;
import io.github.uddinnadd.realisticificator.physics.WorldCollider;
import io.github.uddinnadd.realisticificator.registry.ModEntities;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * A "realistic" block: a server-simulated rigid body made of one or more block cells.
 * <p>
 * Each tick runs 4-16 physics substeps (more when moving fast, so nothing tunnels): gravity and per-cell buoyancy,
 * contact detection against world blocks, other bodies and ragdolls, positional correction, then a sequential-impulse
 * solver with friction and restitution. Orientation is fully continuous; nothing is snapped or animated.
 * <p>
 * The entity position is the centre of mass.
 */
public class RealisticBlockEntity extends Entity implements PhysicsObject {
	public static final int MAX_CELLS = 64;
	private static final double GRAVITY = 0.04;
	private static final int MIN_SUBSTEPS = 4;
	private static final int MAX_SUBSTEPS = 16;
	/** Furthest any point may travel in one substep; keeps penetration well under half a block. */
	private static final double MAX_STEP_TRAVEL = 0.15;
	private static final int SOLVER_ITERATIONS = 8;
	private static final double SLOP = 0.004;
	private static final double BOUNCE_THRESHOLD = 0.06;
	private static final double MAX_SPEED = 2.5;
	private static final double MAX_SPIN = 1.2;
	/** Mass given to mobs and players standing on a body, so they can weigh down see-saws. */
	private static final double ENTITY_MASS = 0.4;
	private static final int LEVEL_EVENT_BLOCK_BREAK = 2001;
	private static final Vector3d UP = new Vector3d(0, 1, 0);
	private static final Vector3d DOWN = new Vector3d(0, -1, 0);

	private static final EntityDataAccessor<BlockStructure> DATA_STRUCTURE = SynchedEntityData.defineId(RealisticBlockEntity.class, BlockStructure.SERIALIZER);
	private static final EntityDataAccessor<BodyPose> DATA_POSE = SynchedEntityData.defineId(RealisticBlockEntity.class, BodyPose.SERIALIZER);

	// No initializers on these: makeBoundingBox() runs inside the Entity constructor, before field initializers.
	private @Nullable BlockStructure structure;
	private @Nullable Quaterniond orientation;
	private @Nullable List<AABB> cachedBoxes;
	private @Nullable Vec3 cachedBoxesPos;
	private @Nullable Quaterniond cachedBoxesRot;

	private final Vector3d linearVelocity = new Vector3d();
	private final Vector3d angularVelocity = new Vector3d();
	private double dt = 1.0 / MIN_SUBSTEPS;
	private boolean asleep;
	private boolean frozen;
	private int heldUntil;
	private int stillTicks;
	private IntSet standingIds = new IntOpenHashSet();

	// Per-tick scratch state (server only).
	private @Nullable WorldCollider collider;
	private List<RealisticBlockEntity> nearbyBodies = List.of();
	private List<PhysicsObject> nearbyObstacles = List.of();
	private final Map<RealisticBlockEntity, Matrix3d> otherInverseInertia = new IdentityHashMap<>();

	// Client interpolation between the last two received poses.
	private boolean clientPoseInitialized;
	private Vec3 prevRenderPos = Vec3.ZERO;
	private Vec3 curRenderPos = Vec3.ZERO;
	private final Quaternionf prevRenderRot = new Quaternionf();
	private final Quaternionf curRenderRot = new Quaternionf();

	public RealisticBlockEntity(EntityType<? extends RealisticBlockEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
		if (this.orientation == null) {
			this.orientation = new Quaterniond();
		}
	}

	public RealisticBlockEntity(Level level, Vec3 centerOfMass, BlockStructure structure) {
		this(ModEntities.REALISTIC_BLOCK, level);
		this.setStructure(structure);
		this.setPos(centerOfMass);
		this.setOldPos();
		this.syncPose();
	}

	// ================================================================ structure, pose & shape

	public BlockStructure getStructure() {
		return this.structure == null ? BlockStructure.EMPTY : this.structure;
	}

	public void setStructure(BlockStructure structure) {
		this.structure = structure;
		this.cachedBoxes = null;
		this.entityData.set(DATA_STRUCTURE, structure);
		this.setBoundingBox(this.makeBoundingBox());
	}

	private Quaterniond rot() {
		if (this.orientation == null) {
			this.orientation = new Quaterniond();
		}
		return this.orientation;
	}

	public void setOrientation(Quaterniond q) {
		this.rot().set(q).normalize();
		this.cachedBoxes = null;
		this.setBoundingBox(this.makeBoundingBox());
	}

	private Matrix3d rotationMatrix() {
		return new Matrix3d().set(this.rot());
	}

	private double inverseMass() {
		return 1.0 / this.getStructure().mass();
	}

	private Matrix3d worldInverseInertia(Matrix3d r) {
		return new Matrix3d(r).mul(this.getStructure().inverseInertia()).mul(new Matrix3d(r).transpose());
	}

	/** Structure-space point to world. */
	public Vec3 toWorld(double sx, double sy, double sz, Matrix3d r) {
		Vec3 com = this.getStructure().centerOfMass();
		Vector3d v = r.transform(new Vector3d(sx - com.x, sy - com.y, sz - com.z));
		return new Vec3(this.getX() + v.x, this.getY() + v.y, this.getZ() + v.z);
	}

	/** World point to structure space. */
	public Vec3 toStructure(Vec3 world, Matrix3d r) {
		Vec3 com = this.getStructure().centerOfMass();
		Vector3d v = r.transformTranspose(new Vector3d(world.x - this.getX(), world.y - this.getY(), world.z - this.getZ()));
		return new Vec3(v.x + com.x, v.y + com.y, v.z + com.z);
	}

	/** World AABB around a structure-space box rotated by r about the body. */
	private AABB worldBox(AABB local, Matrix3d r, Vec3 position) {
		Vec3 com = this.getStructure().centerOfMass();
		Vec3 c = local.getCenter();
		Vector3d w = r.transform(new Vector3d(c.x - com.x, c.y - com.y, c.z - com.z));
		double ex = local.getXsize() / 2, ey = local.getYsize() / 2, ez = local.getZsize() / 2;
		double hx = Math.abs(r.m00) * ex + Math.abs(r.m10) * ey + Math.abs(r.m20) * ez;
		double hy = Math.abs(r.m01) * ex + Math.abs(r.m11) * ey + Math.abs(r.m21) * ez;
		double hz = Math.abs(r.m02) * ex + Math.abs(r.m12) * ey + Math.abs(r.m22) * ez;
		double x = position.x + w.x, y = position.y + w.y, z = position.z + w.z;
		return new AABB(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		BlockStructure s = this.structure;
		if (s == null || s.isEmpty()) {
			return super.makeBoundingBox(position);
		}
		Matrix3d r = new Matrix3d().set(this.orientation == null ? new Quaterniond() : this.orientation);
		AABB result = null;
		for (BlockPos c : s.cells().keySet()) {
			for (AABB box : s.boxesOf(c)) {
				AABB w = this.worldBox(box, r, position);
				result = result == null ? w : result.minmax(w);
			}
		}
		return result;
	}

	/**
	 * Axis-aligned boxes approximating the (possibly rotated) body, used for entity collisions (see LevelMixin).
	 * Each block uses its real collision boxes, so slabs and stairs are only as tall as they look. Tilted full
	 * blocks are split into eight smaller boxes for a tighter fit.
	 */
	public List<AABB> getCollisionBoxes() {
		Vec3 pos = this.position();
		Quaterniond q = this.rot();
		if (this.cachedBoxes != null && pos.equals(this.cachedBoxesPos) && q.equals(this.cachedBoxesRot)) {
			return this.cachedBoxes;
		}
		BlockStructure s = this.getStructure();
		Matrix3d r = new Matrix3d().set(q);
		double ex = Math.abs(r.m00) + Math.abs(r.m10) + Math.abs(r.m20);
		double ey = Math.abs(r.m01) + Math.abs(r.m11) + Math.abs(r.m21);
		double ez = Math.abs(r.m02) + Math.abs(r.m12) + Math.abs(r.m22);
		boolean aligned = ex < 1.03 && ey < 1.03 && ez < 1.03;
		List<AABB> boxes = new ArrayList<>();
		for (BlockPos c : s.cells().keySet()) {
			if (aligned || !s.isFull(c)) {
				for (AABB box : s.boxesOf(c)) {
					boxes.add(this.worldBox(box, r, pos));
				}
				continue;
			}
			if (isBuried(s, c)) {
				continue;
			}
			for (int i = 0; i < 8; i++) {
				double ox = (i & 1) == 0 ? -0.25 : 0.25, oy = (i & 2) == 0 ? -0.25 : 0.25, oz = (i & 4) == 0 ? -0.25 : 0.25;
				AABB sub = new AABB(c.getX() + ox - 0.25, c.getY() + oy - 0.25, c.getZ() + oz - 0.25, c.getX() + ox + 0.25, c.getY() + oy + 0.25, c.getZ() + oz + 0.25);
				boxes.add(this.worldBox(sub, r, pos));
			}
		}
		this.cachedBoxes = List.copyOf(boxes);
		this.cachedBoxesPos = pos;
		this.cachedBoxesRot = new Quaterniond(q);
		return this.cachedBoxes;
	}

	private static boolean isBuried(BlockStructure s, BlockPos c) {
		for (Direction d : Direction.values()) {
			if (!s.isFull(c.relative(d))) {
				return false;
			}
		}
		return true;
	}

	// ================================================================ synced data

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_STRUCTURE, BlockStructure.EMPTY);
		builder.define(DATA_POSE, BodyPose.ORIGIN);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (!this.level().isClientSide()) {
			return;
		}
		if (DATA_STRUCTURE.equals(accessor)) {
			this.structure = this.entityData.get(DATA_STRUCTURE);
			this.cachedBoxes = null;
			// Growing shifts the centre of mass, so the next pose jumps; don't interpolate across that.
			this.clientPoseInitialized = false;
			this.setBoundingBox(this.makeBoundingBox());
		}
	}

	private void syncPose() {
		Quaterniond q = this.rot();
		this.entityData.set(DATA_POSE, new BodyPose(this.position(), new Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w)));
	}

	public Vec3 getRenderPosition(float partialTicks) {
		return this.prevRenderPos.lerp(this.curRenderPos, partialTicks);
	}

	public Quaternionf getRenderRotation(float partialTicks, Quaternionf dest) {
		return this.prevRenderRot.slerp(this.curRenderRot, partialTicks, dest);
	}

	// ================================================================ ticking

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) {
			this.clientTick();
			return;
		}
		BlockStructure s = this.getStructure();
		if (s.isEmpty() || this.getY() < this.level().getMinY() - 64) {
			this.discard();
			return;
		}

		if (this.collider == null) {
			this.collider = new WorldCollider(this.level());
		}
		this.collider.clear();
		Vec3 oldPos = this.position();
		Quaterniond oldRot = new Quaterniond(this.rot());
		List<AABB> oldBoxes = this.getCollisionBoxes();
		AABB near = this.getBoundingBox().inflate(1.5);
		this.nearbyBodies = this.level().getEntitiesOfClass(RealisticBlockEntity.class, near, e -> e != this && e.isAlive());
		List<PhysicsObject> obstacles = new ArrayList<>();
		for (Entity e : this.level().getEntities(this, near, e -> e instanceof PhysicsObject && !(e instanceof RealisticBlockEntity) && e.isAlive())) {
			obstacles.add((PhysicsObject) e);
		}
		this.nearbyObstacles = obstacles;

		boolean held = this.tickCount <= this.heldUntil;
		if (this.frozen && !held) {
			this.linearVelocity.zero();
			this.angularVelocity.zero();
		} else {
			if (this.asleep && this.tickCount % 10 == 0 && this.detectContacts(this.rotationMatrix()).isEmpty()) {
				this.wake(); // whatever was holding it up is gone
			}
			if (!this.asleep) {
				int contacts = 0;
				double submerged = 0;
				double impactSpeed = 0;
				double travel = this.linearVelocity.length() + this.angularVelocity.length() * s.radius();
				int substeps = Mth.clamp(Mth.ceil(travel / MAX_STEP_TRAVEL), MIN_SUBSTEPS, MAX_SUBSTEPS);
				this.dt = 1.0 / substeps;
				for (int i = 0; i < substeps; i++) {
					StepResult result = this.substep();
					contacts = result.contacts;
					submerged = result.submerged;
					impactSpeed = Math.max(impactSpeed, result.impactSpeed);
				}
				this.applyDamping(contacts > 0, submerged);
				if (impactSpeed > 0.18) {
					this.onImpact((ServerLevel) this.level(), impactSpeed);
				}
				this.updateSleep(contacts > 0 && !held);
			}
		}

		this.interactWithEntities((ServerLevel) this.level(), oldPos, oldRot, oldBoxes);
		this.setDeltaMovement(new Vec3(this.linearVelocity.x, this.linearVelocity.y, this.linearVelocity.z));
		this.syncPose();
	}

	private void clientTick() {
		BodyPose target = this.entityData.get(DATA_POSE);
		Player local = null;
		for (Player p : this.level().players()) {
			if (p.isLocalPlayer() && p.distanceToSqr(this) < 64 * 64) {
				local = p;
			}
		}
		boolean standing = local != null && EntityContact.isStandingOn(local, this.getCollisionBoxes());
		Vec3 oldPos = this.position();
		Quaterniond oldRot = new Quaterniond(this.rot());

		if (!this.clientPoseInitialized) {
			this.curRenderPos = target.position();
			this.curRenderRot.set(target.rotation());
			this.clientPoseInitialized = true;
		}
		this.prevRenderPos = this.curRenderPos;
		this.prevRenderRot.set(this.curRenderRot);
		this.curRenderPos = target.position();
		this.curRenderRot.set(target.rotation());

		this.rot().set(this.curRenderRot.x, this.curRenderRot.y, this.curRenderRot.z, this.curRenderRot.w);
		this.setPos(this.curRenderPos);

		if (local != null && !local.isSpectator() && EntityContact.moved(oldPos, oldRot, this.position(), this.rot())) {
			if (standing) {
				EntityContact.carry(local, oldPos, oldRot, this.position(), this.rot());
			} else {
				EntityContact.pushOut(local, this.getCollisionBoxes());
			}
		}
	}

	private record StepResult(int contacts, double submerged, double impactSpeed) {
	}

	private StepResult substep() {
		Matrix3d r = this.rotationMatrix();
		Matrix3d invI = this.worldInverseInertia(r);
		this.otherInverseInertia.clear();

		this.linearVelocity.y -= GRAVITY * this.dt;
		double submerged = this.applyBuoyancy(r, invI);

		List<Contact> contacts = this.detectContacts(r);
		if (!contacts.isEmpty()) {
			this.correctPosition(contacts);
		}
		double impact = contacts.isEmpty() ? 0 : this.solveVelocities(contacts, invI);
		this.integrate();
		return new StepResult(contacts.size(), submerged, impact);
	}

	private void integrate() {
		if (this.linearVelocity.length() > MAX_SPEED) {
			this.linearVelocity.normalize(MAX_SPEED);
		}
		double spin = this.angularVelocity.length();
		if (spin > MAX_SPIN) {
			this.angularVelocity.normalize(MAX_SPIN);
			spin = MAX_SPIN;
		}
		if (spin > 1.0E-9) {
			Quaterniond dq = new Quaterniond().rotationAxis(spin * this.dt, this.angularVelocity.x / spin, this.angularVelocity.y / spin, this.angularVelocity.z / spin);
			this.rot().premul(dq).normalize();
		}
		this.setPos(this.getX() + this.linearVelocity.x * this.dt, this.getY() + this.linearVelocity.y * this.dt, this.getZ() + this.linearVelocity.z * this.dt);
	}

	private void applyDamping(boolean touching, double submerged) {
		if (touching) {
			this.angularVelocity.mul(0.96); // rolling resistance
			this.linearVelocity.mul(0.995);
		} else {
			this.angularVelocity.mul(0.99);
			this.linearVelocity.mul(0.98);
		}
		if (submerged > 0) {
			this.linearVelocity.mul(1.0 - 0.12 * submerged);
			this.angularVelocity.mul(1.0 - 0.1 * submerged);
		}
	}

	private void updateSleep(boolean touching) {
		if (touching && this.linearVelocity.length() < 0.01 && this.angularVelocity.length() < 0.008) {
			if (++this.stillTicks > 10) {
				this.asleep = true;
				this.linearVelocity.zero();
				this.angularVelocity.zero();
			}
		} else {
			this.stillTicks = 0;
		}
	}

	public void wake() {
		this.asleep = false;
		this.stillTicks = 0;
	}

	public boolean isAsleep() {
		return this.asleep;
	}

	// ================================================================ buoyancy

	/** Archimedes per cell: lift equals the weight of displaced fluid, applied at the submerged part's centre. */
	private double applyBuoyancy(Matrix3d r, Matrix3d invI) {
		BlockStructure s = this.getStructure();
		double submergedCells = 0;
		for (BlockPos c : s.cells().keySet()) {
			Vec3 wc = this.toWorld(c.getX(), c.getY(), c.getZ(), r);
			double bottom = wc.y - 0.5;
			double fluidDensity = Densities.WATER;
			double surface = fluidSurface(this.level(), wc.x, bottom + 0.05, wc.z, FluidTags.WATER);
			if (Double.isNaN(surface)) {
				fluidDensity = Densities.LAVA;
				surface = fluidSurface(this.level(), wc.x, bottom + 0.05, wc.z, FluidTags.LAVA);
			}
			if (Double.isNaN(surface)) {
				continue;
			}
			double f = Mth.clamp(surface - bottom, 0.0, 1.0);
			if (f <= 0) {
				continue;
			}
			double volume = 0;
			for (AABB box : s.boxesOf(c)) {
				volume += box.getXsize() * box.getYsize() * box.getZsize();
			}
			submergedCells += f;
			Vector3d impulse = new Vector3d(0, GRAVITY * fluidDensity * volume * f * this.dt, 0);
			this.applyImpulseInternal(impulse, new Vector3d(wc.x - this.getX(), bottom + f / 2 - this.getY(), wc.z - this.getZ()), invI);
		}
		return submergedCells / s.size();
	}

	/** World Y of the fluid surface above (x, y, z), or NaN if that point is not inside the fluid. */
	public static double fluidSurface(Level level, double x, double y, double z, TagKey<Fluid> fluid) {
		BlockPos pos = BlockPos.containing(x, y, z);
		if (!level.hasChunkAt(pos)) {
			return Double.NaN;
		}
		FluidState state = level.getFluidState(pos);
		if (!state.is(fluid)) {
			return Double.NaN;
		}
		double top = pos.getY() + state.getHeight(level, pos);
		BlockPos above = pos.above();
		for (int i = 0; i < 2; i++) {
			FluidState aboveState = level.getFluidState(above);
			if (!aboveState.is(fluid)) {
				break;
			}
			top = above.getY() + aboveState.getHeight(level, above);
			above = above.above();
		}
		return top;
	}

	// ================================================================ contact detection

	private static final class Contact {
		final Vec3 point;
		final Vector3d normal; // points towards this body: the direction this body must move to separate
		final double depth;
		final double friction;
		final double restitution;
		final @Nullable RealisticBlockEntity other;
		final @Nullable PhysicsObject obstacle;
		final int obstaclePart;
		boolean otherDynamic;
		Vector3d r;
		@Nullable Vector3d rOther;
		double normalMass;
		double targetVelocity;
		double closingSpeed;
		double normalImpulse;
		final Vector3d tangentImpulse = new Vector3d();

		Contact(Vec3 point, Vector3d normal, double depth, double friction, double restitution, @Nullable RealisticBlockEntity other) {
			this(point, normal, depth, friction, restitution, other, null, 0);
		}

		Contact(Vec3 point, Vector3d normal, double depth, double friction, double restitution, @Nullable RealisticBlockEntity other, @Nullable PhysicsObject obstacle, int obstaclePart) {
			this.point = point;
			this.normal = normal;
			this.depth = depth;
			this.friction = friction;
			this.restitution = restitution;
			this.other = other;
			this.obstacle = obstacle;
			this.obstaclePart = obstaclePart;
		}
	}

	private static double faceBias(Direction d, Matrix3d r, Vector3d prefer) {
		Vector3d n = r.transform(new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ()));
		return 0.05 * (1.0 - n.dot(prefer));
	}

	private Vector3d faceNormal(Direction d, Matrix3d r) {
		return r.transform(new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ()));
	}

	private List<Contact> detectContacts(Matrix3d r) {
		BlockStructure s = this.getStructure();
		BlockState material = s.primaryState();
		double bodyFriction = Materials.friction(material);
		double bodyRestitution = Materials.restitution(material);
		List<Contact> contacts = new ArrayList<>();
		WorldCollider world = this.collider;

		// 1) Body sample points inside world blocks.
		for (Vec3 sp : s.samplePoints()) {
			Vec3 wp = this.toWorld(sp.x, sp.y, sp.z, r);
			WorldCollider.Hit hit = world.penetration(wp);
			if (hit != null) {
				contacts.add(new Contact(wp, new Vector3d(hit.normal().getStepX(), hit.normal().getStepY(), hit.normal().getStepZ()), hit.depth(),
					Materials.combineFriction(bodyFriction, Materials.friction(hit.state())),
					Materials.combineRestitution(bodyRestitution, Materials.restitution(hit.state())), null));
			}
		}

		// 2) Corners of world collision boxes poking into the body (e.g. resting tilted across a block edge).
		AABB region = this.getBoundingBox().inflate(0.02);
		for (BlockPos bp : BlockPos.betweenClosed(Mth.floor(region.minX), Mth.floor(region.minY) - 1, Mth.floor(region.minZ), Mth.floor(region.maxX), Mth.floor(region.maxY), Mth.floor(region.maxZ))) {
			WorldCollider.BlockInfo info = world.blockInfo(bp);
			for (AABB box : info.boxes()) {
				for (int i = 0; i < 8; i++) {
					Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
					if (!region.contains(corner)) {
						continue;
					}
					BlockStructure.LocalHit hit = s.penetration(this.toStructure(corner, r), d -> faceBias(d, r, DOWN), true);
					if (hit != null) {
						contacts.add(new Contact(corner, this.faceNormal(hit.face(), r).negate(), hit.depth(),
							Materials.combineFriction(bodyFriction, Materials.friction(info.state())),
							Materials.combineRestitution(bodyRestitution, Materials.restitution(info.state())), null));
					}
				}
			}
		}

		// 3) Other realistic bodies, both ways round.
		for (RealisticBlockEntity other : this.nearbyBodies) {
			if (!other.isAlive() || !region.intersects(other.getBoundingBox())) {
				continue;
			}
			BlockStructure os = other.getStructure();
			BlockState otherMaterial = os.primaryState();
			double friction = Materials.combineFriction(bodyFriction, Materials.friction(otherMaterial));
			double restitution = Materials.combineRestitution(bodyRestitution, Materials.restitution(otherMaterial));
			Matrix3d or = other.rotationMatrix();
			AABB otherBox = other.getBoundingBox().inflate(0.01);
			Vec3 apart = this.position().subtract(other.position());
			if (apart.lengthSqr() < 0.35 * 0.35) {
				// Centres nearly coincide (e.g. spawned inside each other): face tests can't pick a side, so just separate.
				Vec3 dir = apart.lengthSqr() > 1.0E-6 ? apart.normalize() : new Vec3(0, this.getId() > other.getId() ? 1 : -1, 0);
				contacts.add(new Contact(this.position(), new Vector3d(dir.x, dir.y, dir.z), 0.5, friction, 0.0, other));
				continue;
			}
			for (Vec3 sp : s.samplePoints()) {
				Vec3 wp = this.toWorld(sp.x, sp.y, sp.z, r);
				if (otherBox.contains(wp)) {
					BlockStructure.LocalHit hit = os.penetration(other.toStructure(wp, or), d -> faceBias(d, or, UP), false);
					if (hit != null) {
						contacts.add(new Contact(wp, other.faceNormal(hit.face(), or), hit.depth(), friction, restitution, other));
					}
				}
			}
			for (Vec3 osp : os.samplePoints()) {
				Vec3 wp = other.toWorld(osp.x, osp.y, osp.z, or);
				if (region.contains(wp)) {
					BlockStructure.LocalHit hit = s.penetration(this.toStructure(wp, r), d -> faceBias(d, r, DOWN), false);
					if (hit != null) {
						contacts.add(new Contact(wp, this.faceNormal(hit.face(), r).negate(), hit.depth(), friction, restitution, other));
					}
				}
			}
		}

		// 4) Ragdolls and other physics objects: solved as static here, then pushed by the reaction afterwards.
		for (PhysicsObject obstacle : this.nearbyObstacles) {
			AABB obstacleBox = ((Entity) obstacle).getBoundingBox().inflate(0.01);
			if (!region.intersects(obstacleBox)) {
				continue;
			}
			for (Vec3 sp : s.samplePoints()) {
				Vec3 wp = this.toWorld(sp.x, sp.y, sp.z, r);
				if (obstacleBox.contains(wp)) {
					PhysicsObject.Penetration pen = obstacle.penetration(wp);
					if (pen != null) {
						contacts.add(new Contact(wp, RigidMath.vec(pen.outwardNormal()), pen.depth(), Math.min(bodyFriction, 0.6), Math.min(bodyRestitution, 0.1), null, obstacle, pen.part()));
					}
				}
			}
		}
		return contacts;
	}

	// ================================================================ contact solving

	private void correctPosition(List<Contact> contacts) {
		double invM = this.inverseMass();
		Vector3d correction = new Vector3d();
		for (int iteration = 0; iteration < 4; iteration++) {
			for (Contact c : contacts) {
				double share = c.obstacle != null ? 0.5 : c.other == null || c.other.asleep ? 1.0 : invM / (invM + c.other.inverseMass());
				double remaining = c.depth * share - correction.dot(c.normal);
				if (remaining > SLOP) {
					correction.fma((remaining - SLOP) * 0.5, c.normal);
				}
			}
		}
		if (correction.length() > 0.3) {
			correction.normalize(0.3);
		}
		this.setPos(this.getX() + correction.x, this.getY() + correction.y, this.getZ() + correction.z);
	}

	/** Sequential impulses with accumulated clamping. Returns the hardest impact speed seen. */
	private double solveVelocities(List<Contact> contacts, Matrix3d invI) {
		double invM = this.inverseMass();
		double impact = 0;
		for (Contact c : contacts) {
			c.r = new Vector3d(c.point.x - this.getX(), c.point.y - this.getY(), c.point.z - this.getZ());
			c.normalMass = invM + RigidMath.angularTerm(invI, c.r, c.normal);
			if (c.other != null) {
				double relSpeed = this.relativeVelocity(c).dot(c.normal);
				if (c.other.asleep && relSpeed < -0.15) {
					c.other.wake();
				}
				c.otherDynamic = !c.other.asleep && !c.other.frozen;
				if (c.otherDynamic) {
					c.rOther = new Vector3d(c.point.x - c.other.getX(), c.point.y - c.other.getY(), c.point.z - c.other.getZ());
					c.normalMass += c.other.inverseMass() + RigidMath.angularTerm(this.otherInertia(c.other), c.rOther, c.normal);
				}
			}
			double vn = this.relativeVelocity(c).dot(c.normal);
			c.closingSpeed = Math.max(0.0, -vn);
			impact = Math.max(impact, -vn);
			// Fade bounciness out at low speeds so slime bounces die down instead of buzzing forever.
			double fade = Mth.clamp((-vn - BOUNCE_THRESHOLD) / 0.12, 0.0, 1.0);
			c.targetVelocity = -c.restitution * fade * vn;
		}

		for (int iteration = 0; iteration < SOLVER_ITERATIONS; iteration++) {
			for (Contact c : contacts) {
				Vector3d v = this.relativeVelocity(c);
				double vn = v.dot(c.normal);
				double newImpulse = Math.max(c.normalImpulse + (c.targetVelocity - vn) / c.normalMass, 0.0);
				double delta = newImpulse - c.normalImpulse;
				c.normalImpulse = newImpulse;
				this.applyContactImpulse(c, new Vector3d(c.normal).mul(delta), invI);

				v = this.relativeVelocity(c);
				Vector3d vt = new Vector3d(v).fma(-v.dot(c.normal), c.normal);
				double slide = vt.length();
				if (slide < 1.0E-9) {
					continue;
				}
				Vector3d t = new Vector3d(vt).div(slide);
				double tangentMass = this.inverseMass() + RigidMath.angularTerm(invI, c.r, t);
				if (c.otherDynamic && c.rOther != null) {
					tangentMass += c.other.inverseMass() + RigidMath.angularTerm(this.otherInertia(c.other), c.rOther, t);
				}
				Vector3d newTangent = new Vector3d(c.tangentImpulse).fma(-slide / tangentMass, t);
				double maxFriction = c.friction * c.normalImpulse;
				if (newTangent.length() > maxFriction) {
					newTangent.normalize(maxFriction);
				}
				Vector3d tangentDelta = new Vector3d(newTangent).sub(c.tangentImpulse);
				c.tangentImpulse.set(newTangent);
				this.applyContactImpulse(c, tangentDelta, invI);
			}
		}

		for (Contact c : contacts) {
			if (c.obstacle != null && c.normalImpulse > 1.0E-5) {
				Vector3d total = new Vector3d(c.normal).mul(c.normalImpulse).add(c.tangentImpulse).mul(-0.5);
				// Solved as if the obstacle were immovable; never hand it more than the impact could really transfer.
				double cap = c.obstacle.partMass(c.obstaclePart) * (c.closingSpeed + 0.05);
				if (total.length() > cap) {
					total.normalize(cap);
				}
				c.obstacle.applyPartImpulse(c.obstaclePart, RigidMath.vec(total), c.point);
			}
		}
		return impact;
	}

	private Matrix3d otherInertia(RealisticBlockEntity other) {
		return this.otherInverseInertia.computeIfAbsent(other, o -> o.worldInverseInertia(o.rotationMatrix()));
	}

	private Vector3d relativeVelocity(Contact c) {
		Vector3d v = new Vector3d(this.angularVelocity).cross(c.r).add(this.linearVelocity);
		if (c.otherDynamic && c.rOther != null) {
			v.sub(new Vector3d(c.other.angularVelocity).cross(c.rOther).add(c.other.linearVelocity));
		}
		return v;
	}

	private void applyContactImpulse(Contact c, Vector3d impulse, Matrix3d invI) {
		this.applyImpulseInternal(impulse, c.r, invI);
		if (c.otherDynamic && c.rOther != null) {
			c.other.applyImpulseInternal(new Vector3d(impulse).negate(), c.rOther, this.otherInertia(c.other));
		}
	}

	private void applyImpulseInternal(Vector3d impulse, Vector3d r, Matrix3d invI) {
		this.linearVelocity.fma(this.inverseMass(), impulse);
		this.angularVelocity.add(invI.transform(new Vector3d(r).cross(impulse)));
	}

	/** Applies an impulse (mass x blocks/tick) at a world point and wakes the body. */
	public void applyImpulse(Vec3 impulse, Vec3 worldPoint) {
		if (this.frozen && this.tickCount > this.heldUntil) {
			return;
		}
		this.wake();
		this.applyImpulseInternal(RigidMath.vec(impulse),
			new Vector3d(worldPoint.x - this.getX(), worldPoint.y - this.getY(), worldPoint.z - this.getZ()),
			this.worldInverseInertia(this.rotationMatrix()));
	}

	public Vec3 velocityAt(Vec3 worldPoint) {
		Vector3d v = new Vector3d(this.angularVelocity).cross(worldPoint.x - this.getX(), worldPoint.y - this.getY(), worldPoint.z - this.getZ()).add(this.linearVelocity);
		return RigidMath.vec(v);
	}

	private void onImpact(ServerLevel level, double speed) {
		BlockState state = this.getStructure().primaryState();
		SoundType sound = state.getSoundType();
		this.playSound(sound.getFallSound(), (float) Math.min(1.5, 0.3 + speed), sound.getPitch() * 0.8F);
		AABB box = this.getBoundingBox();
		level.sendParticles(
			new BlockParticleOption(ParticleTypes.BLOCK, state),
			box.getCenter().x, box.minY, box.getCenter().z,
			(int) Math.min(30, 8 * this.getStructure().size() * speed),
			box.getXsize() / 4, 0.0, box.getZsize() / 4, 0.15
		);
	}

	// ================================================================ physics gun & other simulations

	@Override
	public @Nullable PartHit raycastPart(Vec3 from, Vec3 to) {
		BlockHitResult hit = this.raycastCells(from, to);
		return hit == null ? null : new PartHit(0, this.toWorld(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, this.rotationMatrix()));
	}

	@Override
	public Vec3 partToWorld(int part, Vec3 local) {
		return this.toWorld(local.x, local.y, local.z, this.rotationMatrix());
	}

	@Override
	public Vec3 worldToPart(int part, Vec3 world) {
		return this.toStructure(world, this.rotationMatrix());
	}

	@Override
	public Vec3 partVelocityAt(int part, Vec3 world) {
		return this.velocityAt(world);
	}

	@Override
	public void applyPartImpulse(int part, Vec3 impulse, Vec3 world) {
		this.applyImpulse(impulse, world);
	}

	@Override
	public void drivePoint(int part, Vec3 world, Vec3 desired, double strength) {
		this.wake();
		Matrix3d invI = this.worldInverseInertia(this.rotationMatrix());
		Vector3d r = new Vector3d(world.x - this.getX(), world.y - this.getY(), world.z - this.getZ());
		Matrix3d k = RigidMath.pointMassMatrix(this.inverseMass(), invI, r);
		Vector3d dv = RigidMath.vec(desired.subtract(this.velocityAt(world)));
		Vector3d impulse = k.invert().transform(dv).mul(strength);
		this.applyImpulseInternal(impulse, r, invI);
	}

	@Override
	public void dampPartSpin(int part, double factor) {
		this.angularVelocity.mul(factor);
	}

	@Override
	public double partMass(int part) {
		return this.getStructure().mass();
	}

	@Override
	public void markHeld() {
		this.heldUntil = this.tickCount + 2;
		this.frozen = false;
		this.wake();
	}

	@Override
	public void setFrozen(boolean frozen) {
		this.frozen = frozen;
		if (frozen) {
			this.linearVelocity.zero();
			this.angularVelocity.zero();
		} else {
			this.wake();
		}
	}

	@Override
	public boolean isFrozen() {
		return this.frozen;
	}

	@Override
	public @Nullable Penetration penetration(Vec3 world) {
		Matrix3d r = this.rotationMatrix();
		BlockStructure.LocalHit hit = this.getStructure().penetration(this.toStructure(world, r), d -> faceBias(d, r, UP), false);
		return hit == null ? null : new Penetration(0, hit.depth(), RigidMath.vec(this.faceNormal(hit.face(), r)));
	}

	// ================================================================ entities on and around the body

	private void interactWithEntities(ServerLevel level, Vec3 oldPos, Quaterniond oldRot, List<AABB> oldBoxes) {
		List<AABB> boxes = this.getCollisionBoxes();
		boolean moved = EntityContact.moved(oldPos, oldRot, this.position(), this.rot());
		IntSet nowStanding = new IntOpenHashSet();
		AABB area = this.getBoundingBox().inflate(1.0);
		for (Entity e : level.getEntities(this, area, e -> !(e instanceof PhysicsObject) && e.isAlive() && !e.isSpectator() && !e.noPhysics && !e.isPassenger())) {
			if (EntityContact.isStandingOn(e, oldBoxes)) {
				nowStanding.add(e.getId());
				if (moved && !(e instanceof Player)) {
					EntityContact.carry(e, oldPos, oldRot, this.position(), this.rot());
				}
				if (e instanceof LivingEntity && !this.frozen) {
					if (this.asleep && !this.standingIds.contains(e.getId())) {
						this.wake(); // someone just stepped on: maybe it tips now
					}
					if (!this.asleep) {
						AABB eb = e.getBoundingBox();
						Vector3d weight = new Vector3d(0, -ENTITY_MASS * GRAVITY, 0);
						this.applyImpulseInternal(weight, new Vector3d(e.getX() - this.getX(), eb.minY - this.getY(), e.getZ() - this.getZ()), this.worldInverseInertia(this.rotationMatrix()));
					}
				}
				continue;
			}
			if (moved && !(e instanceof Player)) {
				EntityContact.pushOut(e, boxes);
			}
			if (e instanceof LivingEntity living) {
				this.hurtIfStruck(level, living, boxes);
			}
			if (e instanceof ServerPlayer player && !this.frozen) {
				this.pushedByPlayer(player, boxes);
			}
		}
		this.standingIds = nowStanding;
	}

	private void hurtIfStruck(ServerLevel level, LivingEntity living, List<AABB> boxes) {
		AABB eb = living.getBoundingBox().inflate(0.1);
		for (AABB box : boxes) {
			if (!box.intersects(eb)) {
				continue;
			}
			Vec3 center = eb.getCenter();
			Vec3 v = this.velocityAt(center).subtract(living.getDeltaMovement());
			Vec3 toward = center.subtract(this.position());
			double speed = v.length();
			if (speed > 0.35 && v.dot(toward) > 0) {
				float damage = (float) Math.min(20.0, (speed - 0.3) * this.getStructure().mass() * 6.0);
				if (living.hurtServer(level, this.damageSources().fallingBlock(this), damage)) {
					living.push(v.x * 0.5, Math.max(0.1, v.y * 0.5), v.z * 0.5);
				}
			}
			return;
		}
	}

	/** Players walking into the body push it at the point of contact, so pushing high up tips tall bodies over. */
	private void pushedByPlayer(ServerPlayer player, List<AABB> boxes) {
		Input input = player.getLastClientInput();
		float forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
		float strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
		if (forward == 0 && strafe == 0) {
			return;
		}
		AABB pb = player.getBoundingBox().inflate(0.08, 0.0, 0.08);
		for (AABB box : boxes) {
			if (!box.intersects(pb)) {
				continue;
			}
			float yaw = player.getYRot() * Mth.DEG_TO_RAD;
			float sin = Mth.sin(yaw), cos = Mth.cos(yaw);
			Vec3 intent = new Vec3(strafe * cos - forward * sin, 0.0, forward * cos + strafe * sin).normalize();
			Vec3 chest = new Vec3(player.getX(), player.getY() + 0.9, player.getZ());
			Vec3 contact = new Vec3(Mth.clamp(chest.x, box.minX, box.maxX), Mth.clamp(chest.y, box.minY, box.maxY), Mth.clamp(chest.z, box.minZ, box.maxZ));
			Vec3 toward = contact.subtract(chest).multiply(1, 0, 1);
			if (toward.lengthSqr() < 1.0E-6 || intent.dot(toward.normalize()) < 0.3) {
				return;
			}
			this.applyImpulse(intent.scale(player.isSprinting() ? 0.13 : 0.1), contact);
			return;
		}
	}

	// ================================================================ interaction: growing, hitting, breaking

	/** Ray cast in structure space. The hit's block pos is the cell and its location is in structure space. */
	private @Nullable BlockHitResult raycastCells(Vec3 fromWorld, Vec3 toWorld) {
		Matrix3d r = this.rotationMatrix();
		Vec3 from = this.toStructure(fromWorld, r), to = this.toStructure(toWorld, r);
		BlockStructure s = this.getStructure();
		BlockHitResult best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos c : s.cells().keySet()) {
			BlockHitResult hit = AABB.clip(s.boxesOf(c), from, to, c);
			if (hit != null) {
				double d = hit.getLocation().distanceToSqr(from);
				if (d < bestDist) {
					bestDist = d;
					best = hit;
				}
			}
		}
		return best;
	}

	private @Nullable BlockHitResult raycastFrom(Player player) {
		Vec3 eye = player.getEyePosition();
		return this.raycastCells(eye, eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange() + 3.0)));
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		ItemStack stack = player.getItemInHand(hand);
		if (!RealisticItems.isRealistic(stack) || !(stack.getItem() instanceof BlockItem blockItem)) {
			return super.interact(player, hand, location);
		}
		if (blockItem.getBlock() != this.getStructure().kind()) {
			return InteractionResult.PASS;
		}
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (this.getStructure().size() >= MAX_CELLS) {
			return InteractionResult.FAIL;
		}
		BlockHitResult hit = this.raycastFrom(player);
		if (hit == null) {
			return InteractionResult.FAIL;
		}
		BlockPos newCell = hit.getBlockPos().relative(hit.getDirection());
		if (this.getStructure().contains(newCell) || !this.isFreeForNewCell(newCell)) {
			return InteractionResult.FAIL;
		}

		BlockStructure old = this.getStructure();
		BlockState state = placementState(blockItem.getBlock(), hit.getDirection());
		BlockStructure grown = old.with(newCell, state);
		Matrix3d r = this.rotationMatrix();
		Vec3 comShift = grown.centerOfMass().subtract(old.centerOfMass());
		Vector3d worldShift = r.transform(new Vector3d(comShift.x, comShift.y, comShift.z));
		Vec3 newCellWorld = this.toWorld(newCell.getX(), newCell.getY(), newCell.getZ(), r);
		// Momentum is conserved: the new block joins at rest.
		this.linearVelocity.mul(old.mass() / grown.mass());
		this.setStructure(grown);
		this.setPos(this.getX() + worldShift.x, this.getY() + worldShift.y, this.getZ() + worldShift.z);
		this.wake();
		this.syncPose();

		SoundType sound = state.getSoundType();
		this.level().playSound(null, newCellWorld.x, newCellWorld.y, newCellWorld.z, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	private boolean isFreeForNewCell(BlockPos cell) {
		Matrix3d r = this.rotationMatrix();
		Vec3 center = this.toWorld(cell.getX(), cell.getY(), cell.getZ(), r);
		WorldCollider world = new WorldCollider(this.level());
		for (int i = 0; i < 9; i++) {
			Vec3 p = i == 8 ? center : this.toWorld(cell.getX() + ((i & 1) == 0 ? -0.4 : 0.4), cell.getY() + ((i & 2) == 0 ? -0.4 : 0.4), cell.getZ() + ((i & 4) == 0 ? -0.4 : 0.4), r);
			WorldCollider.Hit hit = world.penetration(p);
			if (hit != null && hit.depth() > 0.02) {
				return false;
			}
		}
		AABB space = new AABB(center, center).inflate(0.45);
		return this.level().getEntities(this, space, e -> e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e instanceof PhysicsObject)).isEmpty();
	}

	static BlockState placementState(Block block, Direction face) {
		BlockState state = block.defaultBlockState();
		if (state.hasProperty(BlockStateProperties.AXIS)) {
			state = state.setValue(BlockStateProperties.AXIS, face.getAxis());
		}
		if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
			state = state.setValue(BlockStateProperties.WATERLOGGED, false);
		}
		return state;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (this.isRemoved() || this.isInvulnerableToBase(source) || source.is(DamageTypeTags.IS_EXPLOSION)) {
			return false; // explosions are handled in pushFromExplosion, which knows the blast direction
		}
		if (!(source.getDirectEntity() instanceof Player player)) {
			return false;
		}
		if (player.isSecondaryUseActive()) {
			this.breakApart(level, !player.hasInfiniteMaterials());
			return true;
		}
		this.frozen = false;
		BlockHitResult hit = this.raycastFrom(player);
		Vec3 point = hit == null ? this.position() : this.toWorld(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, this.rotationMatrix());
		double strength = (0.25 + 0.06 * Math.min(damage, 10.0F)) * (player.isSprinting() ? 1.5 : 1.0);
		Vec3 impulse = player.getLookAngle().scale(strength).add(0.0, 0.08 * strength, 0.0);
		this.applyImpulse(impulse, point);
		SoundType sound = this.getStructure().primaryState().getSoundType();
		this.playSound(sound.getHitSound(), sound.getVolume(), sound.getPitch());
		return true;
	}

	/** TNT and other explosions knock the structure apart into single tumbling blocks. */
	@Override
	public void pushFromExplosion(Vec3 impulse) {
		if (!(this.level() instanceof ServerLevel level) || this.isRemoved()) {
			return;
		}
		double power = impulse.length();
		if (power < 0.03) {
			return;
		}
		this.frozen = false;
		BlockStructure s = this.getStructure();
		Matrix3d r = this.rotationMatrix();
		if (s.size() == 1) {
			// Hit slightly off-centre so it spins as well as flies.
			Vec3 offset = new Vec3(this.random.nextGaussian(), this.random.nextGaussian(), this.random.nextGaussian()).scale(0.25);
			this.applyImpulse(impulse.scale(1.6 * s.mass() / Math.sqrt(s.mass())), this.position().add(offset));
			return;
		}
		for (Map.Entry<BlockPos, BlockState> cell : s.cells().entrySet()) {
			BlockPos c = cell.getKey();
			BlockState state = cell.getValue();
			Vec3 center = this.toWorld(c.getX(), c.getY(), c.getZ(), r);
			if (state.getBlock().getExplosionResistance() < 0.5F && power > 0.25) {
				level.levelEvent(LEVEL_EVENT_BLOCK_BREAK, BlockPos.containing(center), Block.getId(state));
				continue; // glass, leaves and the like shatter
			}
			Vec3 outward = center.subtract(this.position());
			outward = outward.lengthSqr() > 1.0E-4 ? outward.normalize() : Vec3.ZERO;
			Vec3 velocity = this.velocityAt(center)
				.add(impulse.scale(1.6 / Math.sqrt(Densities.of(state))))
				.add(outward.scale(0.3 * power))
				.add(0.0, 0.15 * power, 0.0);
			BlockStructure single = BlockStructure.single(state);
			// A lone slab's centre of mass isn't its cell centre.
			Vector3d comOffset = r.transform(RigidMath.vec(single.centerOfMass()));
			RealisticBlockEntity piece = new RealisticBlockEntity(level, center.add(comOffset.x, comOffset.y, comOffset.z), single);
			piece.setOrientation(this.rot());
			piece.linearVelocity.set(velocity.x, velocity.y, velocity.z);
			piece.angularVelocity.set(this.angularVelocity).add(
				this.random.nextGaussian() * 0.3 * power,
				this.random.nextGaussian() * 0.3 * power,
				this.random.nextGaussian() * 0.3 * power
			);
			piece.syncPose();
			level.addFreshEntity(piece);
		}
		this.discard();
	}

	private void breakApart(ServerLevel level, boolean drop) {
		Matrix3d r = this.rotationMatrix();
		for (Map.Entry<BlockPos, BlockState> cell : this.getStructure().cells().entrySet()) {
			BlockPos c = cell.getKey();
			level.levelEvent(LEVEL_EVENT_BLOCK_BREAK, BlockPos.containing(this.toWorld(c.getX(), c.getY(), c.getZ(), r)), Block.getId(cell.getValue()));
		}
		if (drop) {
			BlockState state = this.getStructure().primaryState();
			int count = this.getStructure().size();
			while (count > 0) {
				int n = Math.min(count, state.getBlock().asItem().getDefaultMaxStackSize());
				this.spawnAtLocation(level, RealisticItems.realisticStackOf(state, n), 0.0F);
				count -= n;
			}
		}
		this.discard();
	}

	@Override
	public @Nullable ItemStack getPickResult() {
		return RealisticItems.realisticStackOf(this.getStructure().primaryState(), 1);
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return this.isAlive() && !(other instanceof PhysicsObject);
	}

	@Override
	protected Entity.MovementEmission getMovementEmission() {
		return Entity.MovementEmission.NONE;
	}

	// ================================================================ persistence

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		Quaterniond q = this.rot();
		output.store("Structure", BlockStructure.CODEC, this.getStructure());
		output.store("Orientation", ExtraCodecs.QUATERNIONF, new Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w));
		output.store("LinearVelocity", Vec3.CODEC, RigidMath.vec(this.linearVelocity));
		output.store("AngularVelocity", Vec3.CODEC, RigidMath.vec(this.angularVelocity));
		output.putBoolean("Asleep", this.asleep);
		output.putBoolean("Frozen", this.frozen);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		input.read("Structure", BlockStructure.CODEC).ifPresent(this::setStructure);
		input.read("Orientation", ExtraCodecs.QUATERNIONF).ifPresent(q -> this.setOrientation(new Quaterniond(q.x(), q.y(), q.z(), q.w())));
		input.read("LinearVelocity", Vec3.CODEC).ifPresent(v -> this.linearVelocity.set(v.x, v.y, v.z));
		input.read("AngularVelocity", Vec3.CODEC).ifPresent(v -> this.angularVelocity.set(v.x, v.y, v.z));
		this.asleep = input.getBooleanOr("Asleep", false);
		this.frozen = input.getBooleanOr("Frozen", false);
		this.syncPose();
	}
}
