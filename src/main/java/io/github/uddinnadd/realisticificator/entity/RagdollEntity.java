package io.github.uddinnadd.realisticificator.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.uddinnadd.realisticificator.physics.EntityContact;
import io.github.uddinnadd.realisticificator.physics.PhysicsObject;
import io.github.uddinnadd.realisticificator.physics.RagdollSkeleton;
import io.github.uddinnadd.realisticificator.physics.RigidMath;
import io.github.uddinnadd.realisticificator.physics.WorldCollider;
import io.github.uddinnadd.realisticificator.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * A mob corpse simulated as rigid boxes (bones) connected by ball joints with angle limits.
 * The client draws the mob's real model, with each model part riding its bone.
 */
public class RagdollEntity extends Entity implements PhysicsObject {
	private static final double GRAVITY = 0.04;
	private static final double FLESH_DENSITY = 0.95; // a touch lighter than water, so corpses float
	private static final int MIN_SUBSTEPS = 4;
	private static final int MAX_SUBSTEPS = 12;
	private static final double MAX_STEP_TRAVEL = 0.12;
	private static final int SOLVER_ITERATIONS = 10;
	private static final double SLOP = 0.004;
	private static final double JOINT_STIFFNESS = 0.25;
	private static final double FRICTION = 0.7;
	private static final double RESTITUTION = 0.05;
	private static final double SELF_FRICTION = 0.4;
	/** Bones are shrunk by this much when testing them against each other, so touching parts don't fight. */
	private static final double SELF_COLLISION_MARGIN = 0.03;
	private static final double MAX_SPEED = 2.5;
	private static final double MAX_SPIN = 1.5;
	private static final Vector3d UP = new Vector3d(0, 1, 0);

	private static final EntityDataAccessor<RagdollInfo> DATA_INFO = SynchedEntityData.defineId(RagdollEntity.class, RagdollInfo.SERIALIZER);
	private static final EntityDataAccessor<List<BodyPose>> DATA_POSES = SynchedEntityData.defineId(RagdollEntity.class, RagdollInfo.POSES_SERIALIZER);
	private static final EntityDataAccessor<Integer> DATA_LINK = SynchedEntityData.defineId(RagdollEntity.class, EntityDataSerializers.INT);

	/** Simulation state of one bone. Position is the bone centre. */
	public static final class Bone {
		final Vector3d pos = new Vector3d();
		final Quaterniond rot = new Quaterniond();
		final Vector3d linVel = new Vector3d();
		final Vector3d angVel = new Vector3d();
		final Vector3d half;
		final double mass;
		final Vector3d invInertiaLocal;

		Bone(RagdollSkeleton.Bone template) {
			this.half = RigidMath.vec(template.half());
			this.mass = Math.max(template.volume() * FLESH_DENSITY, 1.0E-3);
			double sx = this.half.x * 2, sy = this.half.y * 2, sz = this.half.z * 2;
			this.invInertiaLocal = new Vector3d(12.0 / (this.mass * (sy * sy + sz * sz)), 12.0 / (this.mass * (sx * sx + sz * sz)), 12.0 / (this.mass * (sx * sx + sy * sy)));
		}

		Matrix3d worldInvInertia() {
			Matrix3d r = new Matrix3d().set(this.rot);
			Matrix3d d = new Matrix3d().scaling(this.invInertiaLocal.x, this.invInertiaLocal.y, this.invInertiaLocal.z);
			return new Matrix3d(r).mul(d).mul(r.transpose());
		}

		Vec3 toWorld(Vec3 local) {
			Vector3d v = this.rot.transform(RigidMath.vec(local)).add(this.pos);
			return RigidMath.vec(v);
		}

		Vec3 toLocal(Vec3 world) {
			return RigidMath.vec(this.rot.transformInverse(new Vector3d(world.x - this.pos.x, world.y - this.pos.y, world.z - this.pos.z)));
		}

		Vector3d velocityAt(Vector3d r) {
			return new Vector3d(this.angVel).cross(r).add(this.linVel);
		}

		void applyImpulse(Vector3d impulse, Vector3d r, Matrix3d invI) {
			this.linVel.fma(1.0 / this.mass, impulse);
			this.angVel.add(invI.transform(new Vector3d(r).cross(impulse)));
		}

		AABB worldBox() {
			Matrix3d r = new Matrix3d().set(this.rot);
			double hx = Math.abs(r.m00) * this.half.x + Math.abs(r.m10) * this.half.y + Math.abs(r.m20) * this.half.z;
			double hy = Math.abs(r.m01) * this.half.x + Math.abs(r.m11) * this.half.y + Math.abs(r.m21) * this.half.z;
			double hz = Math.abs(r.m02) * this.half.x + Math.abs(r.m12) * this.half.y + Math.abs(r.m22) * this.half.z;
			return new AABB(this.pos.x - hx, this.pos.y - hy, this.pos.z - hz, this.pos.x + hx, this.pos.y + hy, this.pos.z + hz);
		}
	}

	private record SavedBone(Vec3 pos, org.joml.Quaternionfc rot, Vec3 linVel, Vec3 angVel) {
		static final Codec<SavedBone> CODEC = RecordCodecBuilder.create(i -> i.group(
			Vec3.CODEC.fieldOf("pos").forGetter(SavedBone::pos),
			ExtraCodecs.QUATERNIONF.fieldOf("rot").forGetter(SavedBone::rot),
			Vec3.CODEC.fieldOf("lin_vel").forGetter(SavedBone::linVel),
			Vec3.CODEC.fieldOf("ang_vel").forGetter(SavedBone::angVel)
		).apply(i, SavedBone::new));
	}

	// No initializers: makeBoundingBox() runs inside the Entity constructor, before field initializers.
	private @Nullable RagdollSkeleton skeleton;
	private @Nullable Bone[] bones;

	private boolean asleep;
	/** False until bones have a real layout; summoned corpses get their position after their data is read. */
	private boolean bonesPlaced;
	private boolean frozen;
	private int heldUntil;
	private int stillTicks;
	private double dt = 1.0 / MIN_SUBSTEPS;
	private @Nullable WorldCollider collider;
	private List<PhysicsObject> nearbyObstacles = List.of();

	// Knock-down state (server). A live ragdoll has its entity riding it; recoverFrom is non-null while it does.
	private @Nullable Vec3 recoverFrom;
	private boolean restoreAi;
	private int liveTicks;
	private int restlessTicks;
	private double fallPeakY = Double.NaN;
	private double lastTorsoVy;
	/** Tick at which a death corpse disappears, or -1 for corpses that stay (spawned from eggs). */
	private int despawnAt = -1;
	// Hardest terrain impact this tick, for thuds, dust and the occasional "oof".
	private double impactSpeed;
	private @Nullable Vec3 impactPoint;
	private int nextImpactEffect;
	/** Where the current fling started, to tell how far someone got launched. */
	private @Nullable Vec3 launchedFrom;

	/** Client: ids of entities currently represented by a ragdoll, so their own model is hidden. */
	private static final Set<Integer> CLIENT_LINKS = ConcurrentHashMap.newKeySet();
	private int clientLink = -1;

	private boolean clientInitialized;
	private final List<BodyPose> prevPoses = new ArrayList<>();
	private final List<BodyPose> curPoses = new ArrayList<>();

	public RagdollEntity(EntityType<? extends RagdollEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	/** Makes a corpse of a freshly created (never added) mob, standing at {@code feet} and facing {@code yaw}. */
	public static RagdollEntity of(ServerLevel level, LivingEntity mob, Vec3 feet, float yaw) {
		if (mob instanceof Mob m && m.isBaby()) {
			m.setBaby(false);
		}
		RagdollEntity ragdoll = create(level, mob);
		ragdoll.layOutBones(feet, yaw, true);
		ragdoll.setOldPos();
		return ragdoll;
	}

	private static RagdollEntity create(ServerLevel level, LivingEntity entity) {
		CompoundTag data;
		if (entity instanceof Player player) {
			// Player data is huge (inventory, advancements...); the client only needs to know whose skin to use.
			data = new CompoundTag();
			data.putString("player_uuid", player.getUUID().toString());
		} else {
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			entity.saveWithoutId(output);
			data = output.buildResult();
			for (String key : List.of("UUID", "Pos", "Motion", "Rotation", "Passengers", "Leash", "Inventory", "Offers", "Brain")) {
				data.remove(key);
			}
		}
		RagdollSkeleton.Kind kind = RagdollSkeleton.classify(entity);
		RagdollInfo info = new RagdollInfo(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), data, kind.ordinal(), entity.getBbWidth(), entity.getBbHeight());
		RagdollEntity ragdoll = new RagdollEntity(ModEntities.RAGDOLL, level);
		ragdoll.setInfo(info);
		if (entity.hasCustomName()) {
			ragdoll.setCustomName(entity.getCustomName());
		}
		return ragdoll;
	}

	/**
	 * Knocks a living entity down: it rides a new ragdoll of itself (hidden on clients) until the ragdoll settles,
	 * then gets back up where the body ended. If it is already down, the existing ragdoll just takes the hit.
	 */
	public static @Nullable RagdollEntity knockDown(ServerLevel level, LivingEntity entity, Vec3 motion, Vec3 fling, @Nullable Vec3 hitFrom) {
		if (entity.getVehicle() instanceof RagdollEntity existing) {
			existing.fling(fling, hitFrom);
			existing.liveTicks = Math.min(existing.liveTicks, 10); // knocked again: stay down a bit longer
			return existing;
		}
		entity.stopRiding();
		entity.ejectPassengers();
		RagdollEntity ragdoll = create(level, entity);
		ragdoll.layOutBones(entity.position(), entity instanceof LivingEntity l ? l.yBodyRot : entity.getYRot(), false);
		Vec3 v = motion;
		for (Bone bone : ragdoll.bones()) {
			bone.linVel.set(v.x, Math.max(v.y, -1.0), v.z);
		}
		ragdoll.recoverFrom = entity.position();
		ragdoll.fallPeakY = ragdoll.bones()[RagdollSkeleton.TORSO].pos.y + entity.fallDistance;
		ragdoll.setLink(entity.getId());
		if (entity instanceof Mob mob && !mob.isNoAi()) {
			mob.setNoAi(true); // no attacking or wandering while knocked out
			ragdoll.restoreAi = true;
		}
		ragdoll.setOldPos();
		level.addFreshEntity(ragdoll);
		if (!entity.startRiding(ragdoll, true, false)) {
			ragdoll.restoreAi(entity);
			ragdoll.discard();
			return null;
		}
		ragdoll.fling(fling, hitFrom);
		if (entity instanceof ServerPlayer player) {
			io.github.uddinnadd.realisticificator.registry.ModAdvancements.award(player, io.github.uddinnadd.realisticificator.registry.ModAdvancements.RAGDOLLED);
		}
		return ragdoll;
	}

	/** A body for something that just died: a fresh corpse, or its knock-down ragdoll left lying where it is. */
	public static void onDeath(ServerLevel level, LivingEntity entity, Vec3 motion, @Nullable Vec3 fling, @Nullable Vec3 hitFrom, int lifetimeTicks) {
		RagdollEntity ragdoll;
		if (entity.getVehicle() instanceof RagdollEntity existing) {
			ragdoll = existing;
			ragdoll.restoreAi(entity);
			ragdoll.restoreAi = false;
			entity.stopRiding();
		} else {
			if (fling == null || lifetimeTicks <= 0) {
				return;
			}
			ragdoll = create(level, entity);
			ragdoll.layOutBones(entity.position(), entity.yBodyRot, false);
			Vec3 v = motion;
			for (Bone bone : ragdoll.bones()) {
				bone.linVel.set(v.x, Math.max(v.y, -1.0), v.z);
			}
			ragdoll.setOldPos();
			level.addFreshEntity(ragdoll);
		}
		// Keeps the dying entity hidden while vanilla plays its death animation.
		ragdoll.setLink(entity.getId());
		ragdoll.recoverFrom = null;
		ragdoll.despawnAt = lifetimeTicks > 0 ? ragdoll.tickCount + lifetimeTicks : ragdoll.tickCount;
		if (fling != null) {
			ragdoll.fling(fling, hitFrom);
		}
	}

	/** Adds a velocity to the whole body, plus a cartwheel away from the hit and flailing limbs. */
	public void fling(Vec3 velocity, @Nullable Vec3 hitFrom) {
		this.frozen = false;
		this.wake();
		Bone torso = this.bones()[RagdollSkeleton.TORSO];
		double power = velocity.length();
		for (Bone bone : this.bones()) {
			bone.linVel.add(velocity.x, velocity.y, velocity.z);
		}
		// Hits land on the upper body, so the body spins backwards away from the hit, as well as flying.
		Vector3d dir = new Vector3d(velocity.x, 0, velocity.z);
		if (dir.length() > 1.0E-4) {
			Vector3d spinAxis = new Vector3d(0, 1, 0).cross(dir.normalize());
			double spin = Math.min(1.1, power * 1.2);
			for (Bone bone : this.bones()) {
				bone.angVel.fma(spin, spinAxis);
			}
		}
		// Arms and legs windmill on the way, and a random twist so no two launches look the same.
		double flail = Math.min(1.2, power * 1.5);
		for (int i = 0; i < this.bones().length; i++) {
			Bone bone = this.bones()[i];
			double amount = i == RagdollSkeleton.TORSO ? 0.25 : 1.0;
			bone.angVel.add(this.random.nextGaussian() * flail * amount, this.random.nextGaussian() * flail * amount, this.random.nextGaussian() * flail * amount);
		}
		if (hitFrom != null) {
			Vector3d arm = new Vector3d(torso.pos).sub(hitFrom.x, hitFrom.y, hitFrom.z);
			if (arm.length() > 1.0E-3) {
				torso.angVel.add(arm.normalize().cross(0, 1, 0).mul(0.1));
			}
		}
		if (power > 0.35) {
			this.level().playSound(null, torso.pos.x, torso.pos.y, torso.pos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL,
				(float) Math.min(1.0, power), 0.6F + this.random.nextFloat() * 0.3F);
		}
		if (this.launchedFrom == null || power > 0.5) {
			this.launchedFrom = RigidMath.vec(torso.pos);
		}
	}

	private void setLink(int id) {
		this.entityData.set(DATA_LINK, id);
	}

	/** Entity this ragdoll stands in for (knocked down, or dying), or -1. */
	public int getLink() {
		return this.entityData.get(DATA_LINK);
	}

	private void restoreAi(Entity entity) {
		if (this.restoreAi && entity instanceof Mob mob) {
			mob.setNoAi(false);
		}
	}

	/**
	 * Lays the skeleton out with its feet at {@code feet}, facing {@code yaw}. Corpses start slumping; knocked-down
	 * entities start in the pose they were standing in.
	 */
	private void layOutBones(Vec3 feet, float yaw, boolean slumped) {
		// A four-legged stance is stable enough to stay standing, so start corpses tipped over: quadrupeds rolled
		// onto their side, bipeds leaning so they fold at the hips.
		double side = this.random.nextBoolean() ? 1 : -1;
		Quaterniond slump = !slumped ? new Quaterniond() : switch (this.skeleton().kind) {
			case QUADRUPED -> new Quaterniond().rotationZ(side * 1.35);
			case BIPED -> new Quaterniond().rotationX(side * 0.35);
			case BLOB -> new Quaterniond();
		};
		Quaterniond facing = new Quaterniond().rotationY(-Math.toRadians(yaw)).mul(slump);
		double lowest = Double.MAX_VALUE;
		for (int i = 0; i < this.bones().length; i++) {
			Bone bone = this.bones()[i];
			Vector3d c = facing.transform(RigidMath.vec(this.skeleton().bones.get(i).center()));
			bone.pos.set(feet.x + c.x, feet.y + c.y, feet.z + c.z);
			bone.rot.set(facing);
			bone.linVel.zero();
			bone.angVel.zero();
			lowest = Math.min(lowest, bone.worldBox().minY);
		}
		double lift = feet.y + 0.02 - lowest;
		for (Bone bone : this.bones()) {
			bone.pos.y += lift;
		}
		// A perfectly balanced corpse could stay standing; tip it slightly so it slumps over.
		this.bones()[RagdollSkeleton.TORSO].angVel.set(this.random.nextGaussian() * 0.04, 0, this.random.nextGaussian() * 0.04);
		this.bonesPlaced = true;
		this.afterBonesMoved();
		this.syncPoses();
	}

	public RagdollInfo getInfo() {
		return this.entityData.get(DATA_INFO);
	}

	private void setInfo(RagdollInfo info) {
		this.entityData.set(DATA_INFO, info);
		this.rebuild(info);
	}

	private void rebuild(RagdollInfo info) {
		this.skeleton = RagdollSkeleton.build(RagdollSkeleton.Kind.byId(info.kind()), info.width(), info.height());
		this.bones = new Bone[this.skeleton.bones.size()];
		for (int i = 0; i < this.bones.length; i++) {
			this.bones[i] = new Bone(this.skeleton.bones.get(i));
			this.bones[i].pos.set(RigidMath.vec(this.position().add(this.skeleton.bones.get(i).center())));
		}
	}

	public RagdollSkeleton skeleton() {
		if (this.skeleton == null) {
			this.rebuild(this.getInfo());
		}
		return this.skeleton;
	}

	private Bone[] bones() {
		if (this.bones == null) {
			this.rebuild(this.getInfo());
		}
		return this.bones;
	}

	public int boneCount() {
		return this.bones().length;
	}

	// ================================================================ shape

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		if (this.bones == null) {
			return super.makeBoundingBox(position);
		}
		AABB box = null;
		for (Bone bone : this.bones) {
			AABB b = bone.worldBox();
			box = box == null ? b : box.minmax(b);
		}
		return box;
	}

	public List<AABB> getCollisionBoxes() {
		List<AABB> boxes = new ArrayList<>(this.bones().length);
		for (Bone bone : this.bones()) {
			boxes.add(bone.worldBox().deflate(0.03));
		}
		return boxes;
	}

	/** Entity position follows the torso; the bounding box covers every bone. */
	private void afterBonesMoved() {
		Vector3d torso = this.bones()[RagdollSkeleton.TORSO].pos;
		this.setPosRaw(torso.x, torso.y, torso.z);
		this.setBoundingBox(this.makeBoundingBox());
	}

	// ================================================================ sync

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_INFO, RagdollInfo.EMPTY);
		builder.define(DATA_POSES, List.of());
		builder.define(DATA_LINK, -1);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_INFO.equals(accessor)) {
			this.rebuild(this.entityData.get(DATA_INFO));
			this.clientInitialized = false;
		}
		if (DATA_LINK.equals(accessor) && this.level().isClientSide()) {
			this.updateClientLink(this.entityData.get(DATA_LINK));
		}
	}

	private void updateClientLink(int link) {
		if (this.clientLink >= 0) {
			CLIENT_LINKS.remove(this.clientLink);
		}
		this.clientLink = link;
		if (link >= 0) {
			CLIENT_LINKS.add(link);
		}
	}

	@Override
	public void onClientRemoval() {
		super.onClientRemoval();
		this.updateClientLink(-1);
	}

	/**
	 * Client: should this entity's own model be hidden because a ragdoll is standing in for it? True while it rides
	 * its knock-down ragdoll, or while it is dying and its corpse has taken over.
	 */
	public static boolean hidesOnClient(Entity entity) {
		if (entity.getVehicle() instanceof RagdollEntity) {
			return true;
		}
		return !CLIENT_LINKS.isEmpty() && CLIENT_LINKS.contains(entity.getId()) && !entity.isAlive();
	}

	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
		// Put the rider's eyes in the ragdoll's head, so a knocked-down player's camera rides along with it.
		Vector3d head = this.bones()[Math.min(RagdollSkeleton.HEAD, this.bones().length - 1)].pos;
		moveFunction.accept(passenger, head.x, head.y - passenger.getEyeHeight(), head.z);
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().isEmpty();
	}

	private void syncPoses() {
		List<BodyPose> poses = new ArrayList<>(this.bones().length);
		for (Bone bone : this.bones()) {
			poses.add(new BodyPose(RigidMath.vec(bone.pos), new Quaternionf((float) bone.rot.x, (float) bone.rot.y, (float) bone.rot.z, (float) bone.rot.w)));
		}
		this.entityData.set(DATA_POSES, poses);
	}

	/** Interpolated pose of a bone for rendering. */
	public BodyPose getRenderPose(int bone, float partialTicks) {
		if (bone >= this.curPoses.size()) {
			return new BodyPose(this.position(), new Quaternionf());
		}
		BodyPose a = this.prevPoses.get(bone), b = this.curPoses.get(bone);
		return new BodyPose(a.position().lerp(b.position(), partialTicks), new Quaternionf(a.rotation()).slerp(b.rotation(), partialTicks));
	}

	// ================================================================ ticking

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) {
			this.clientTick();
			return;
		}
		if (this.getY() < this.level().getMinY() - 64) {
			this.discard();
			return;
		}
		if (!this.bonesPlaced) {
			this.layOutBones(this.position(), this.getYRot(), true);
		}
		if (this.despawnAt >= 0 && this.tickCount >= this.despawnAt && this.getPassengers().isEmpty()) {
			((ServerLevel) this.level()).sendParticles(ParticleTypes.POOF, this.getX(), this.getY(), this.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
			this.discard();
			return;
		}
		if (this.getLink() >= 0 && this.recoverFrom == null && this.tickCount % 20 == 0) {
			Entity linked = this.level().getEntity(this.getLink());
			if (linked == null || linked.isAlive()) {
				this.setLink(-1); // the dead entity is gone (or respawned): stop hiding it
			}
		}
		if (this.collider == null) {
			this.collider = new WorldCollider(this.level());
		}
		this.collider.clear();
		Vec3 oldPos = this.position();
		Quaterniond oldRot = new Quaterniond(this.bones()[RagdollSkeleton.TORSO].rot);
		List<PhysicsObject> obstacles = new ArrayList<>();
		for (Entity e : this.level().getEntities(this, this.getBoundingBox().inflate(1.5), e -> e instanceof PhysicsObject && e.isAlive())) {
			obstacles.add((PhysicsObject) e);
		}
		this.nearbyObstacles = obstacles;

		boolean held = this.tickCount <= this.heldUntil;
		if (this.frozen && !held) {
			for (Bone bone : this.bones()) {
				bone.linVel.zero();
				bone.angVel.zero();
			}
		} else {
			if (this.asleep && this.tickCount % 10 == 0 && this.detectContacts().isEmpty()) {
				this.wake();
			}
			if (!this.asleep) {
				double travel = 0;
				for (Bone bone : this.bones()) {
					travel = Math.max(travel, bone.linVel.length() + bone.angVel.length() * bone.half.length());
				}
				int substeps = Mth.clamp(Mth.ceil(travel / MAX_STEP_TRAVEL), MIN_SUBSTEPS, MAX_SUBSTEPS);
				this.dt = 1.0 / substeps;
				int contacts = 0;
				for (int i = 0; i < substeps; i++) {
					contacts = this.substep();
				}
				this.applyDamping(contacts > 0);
				this.updateSleep(contacts > 0 && !held);
			}
		}

		this.afterBonesMoved();
		this.interactWithEntities((ServerLevel) this.level(), oldPos, oldRot);
		this.syncPoses();
		this.playMotionEffects((ServerLevel) this.level(), held);
		if (this.recoverFrom != null) {
			this.tickKnockDown((ServerLevel) this.level());
		}
	}

	private void noteImpact(double speed, Vec3 point) {
		if (speed > this.impactSpeed) {
			this.impactSpeed = speed;
			this.impactPoint = point;
		}
	}

	/** Thuds and dust on hard landings, a yelp from whoever is knocked down, and a smoke trail while flying. */
	private void playMotionEffects(ServerLevel level, boolean held) {
		double speed = this.impactSpeed;
		Vec3 point = this.impactPoint;
		this.impactSpeed = 0;
		this.impactPoint = null;
		Bone torso = this.bones()[RagdollSkeleton.TORSO];
		if (!held && torso.linVel.length() > 0.7 && this.tickCount % 2 == 0) {
			level.sendParticles(ParticleTypes.CLOUD, torso.pos.x, torso.pos.y, torso.pos.z, 1, 0.1, 0.1, 0.1, 0.0);
		}
		if (point == null || speed < 0.3 || this.tickCount < this.nextImpactEffect) {
			return;
		}
		this.nextImpactEffect = this.tickCount + 5;
		BlockPos ground = BlockPos.containing(point.x, point.y - 0.05, point.z);
		var state = level.getBlockState(ground);
		if (state.isAir()) {
			state = level.getBlockState(ground.below());
		}
		float volume = (float) Math.min(1.5, 0.4 + speed);
		level.playSound(null, point.x, point.y, point.z, speed > 0.7 ? SoundEvents.PLAYER_BIG_FALL : SoundEvents.PLAYER_SMALL_FALL,
			this.getSoundSource(), volume, 0.8F + this.random.nextFloat() * 0.3F);
		if (!state.isAir()) {
			level.playSound(null, point.x, point.y, point.z, state.getSoundType().getFallSound(), this.getSoundSource(), volume * 0.7F, 0.8F);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, state), point.x, point.y + 0.1, point.z,
				(int) Math.min(30, 6 + speed * 20), 0.25, 0.1, 0.25, 0.15);
		}
		if (speed > 0.6 && this.recoverFrom != null && this.getFirstPassenger() instanceof LivingEntity rider && rider.isAlive()) {
			SoundEvent oof = ((io.github.uddinnadd.realisticificator.mixin.LivingEntityAccessor) rider).realisticIficator$hurtSound(this.damageSources().generic());
			if (oof != null) {
				level.playSound(null, point.x, point.y, point.z, oof, rider.getSoundSource(), 1.0F, rider.getVoicePitch() * (0.9F + this.random.nextFloat() * 0.4F));
			}
		}
	}

	/** While someone is knocked down: get them back up once the body has come to rest (or after a while regardless). */
	private void tickKnockDown(ServerLevel level) {
		Entity rider = this.getFirstPassenger();
		if (rider == null || rider.isRemoved()) {
			if (rider != null) {
				this.restoreAi(rider);
			}
			this.discard(); // logged out, teleported away, unloaded...
			return;
		}
		if (this.getLink() != rider.getId()) {
			this.setLink(rider.getId()); // ids change across a reload
		}
		if (rider instanceof LivingEntity living && living.isDeadOrDying()) {
			return; // the death event turns us into a corpse
		}
		this.checkLanding(rider);
		if (rider.isRemoved() || rider instanceof LivingEntity dying && dying.isDeadOrDying()) {
			return;
		}
		this.liveTicks++;
		boolean restless = false;
		for (Bone bone : this.bones()) {
			if (bone.linVel.length() > 0.04 || bone.angVel.length() > 0.08) {
				restless = true;
				break;
			}
		}
		this.restlessTicks = restless ? 0 : this.restlessTicks + 1;
		if ((this.liveTicks > 30 && (this.asleep || this.restlessTicks > 12)) || this.liveTicks > 240) {
			this.getUp(level, rider);
		}
	}

	/** Hard landings hurt whoever is knocked down, as falling would, so a hit in mid-air doesn't cancel fall damage. */
	private void checkLanding(Entity rider) {
		Bone torso = this.bones()[RagdollSkeleton.TORSO];
		double vy = torso.linVel.y;
		boolean inFluid = !this.level().getFluidState(BlockPos.containing(torso.pos.x, torso.pos.y, torso.pos.z)).isEmpty();
		this.fallPeakY = Double.isNaN(this.fallPeakY) || inFluid ? torso.pos.y : Math.max(this.fallPeakY, torso.pos.y);
		if (this.lastTorsoVy < -0.3 && vy > this.lastTorsoVy * 0.4) {
			double drop = this.fallPeakY - torso.pos.y;
			this.fallPeakY = torso.pos.y;
			rider.causeFallDamage(drop, 1.0F, this.damageSources().fall());
		}
		this.lastTorsoVy = vy;
	}

	private void getUp(ServerLevel level, Entity rider) {
		Bone torso = this.bones()[RagdollSkeleton.TORSO];
		double lowest = Double.MAX_VALUE;
		for (Bone bone : this.bones()) {
			lowest = Math.min(lowest, bone.worldBox().minY);
		}
		Vec3 fallback = this.recoverFrom;
		this.recoverFrom = null;
		this.restoreAi(rider);
		rider.stopRiding();
		this.setLink(-1);
		this.discard();

		// Stand up on the spot the body lies, nudging up out of anything solid; otherwise go back to where it was hit.
		Vec3 spot = null;
		for (double dy = 0; dy <= 1.5 && spot == null; dy += 0.25) {
			Vec3 candidate = new Vec3(torso.pos.x, lowest + dy, torso.pos.z);
			if (level.noCollision(rider, rider.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(candidate))) {
				spot = candidate;
			}
		}
		if (spot == null) {
			spot = fallback;
		}
		if (rider instanceof ServerPlayer player) {
			player.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), player.getYRot(), player.getXRot(), false);
			if (this.launchedFrom != null && this.launchedFrom.subtract(spot).horizontalDistance() >= 20) {
				io.github.uddinnadd.realisticificator.registry.ModAdvancements.award(player, io.github.uddinnadd.realisticificator.registry.ModAdvancements.YEET);
			}
		} else {
			rider.setPos(spot);
		}
		rider.setDeltaMovement(Vec3.ZERO);
		rider.resetFallDistance();
	}

	private void clientTick() {
		List<BodyPose> target = this.entityData.get(DATA_POSES);
		if (target.size() != this.bones().length) {
			return;
		}
		if (!this.clientInitialized) {
			this.curPoses.clear();
			this.curPoses.addAll(target);
			this.clientInitialized = true;
		}
		Player local = null;
		for (Player p : this.level().players()) {
			if (p.isLocalPlayer() && p.distanceToSqr(this) < 64 * 64) {
				local = p;
			}
		}
		List<AABB> oldBoxes = this.getCollisionBoxes();
		boolean standing = local != null && EntityContact.isStandingOn(local, oldBoxes);
		Vec3 oldTorso = RigidMath.vec(this.bones()[RagdollSkeleton.TORSO].pos);
		Quaterniond oldRot = new Quaterniond(this.bones()[RagdollSkeleton.TORSO].rot);

		this.prevPoses.clear();
		this.prevPoses.addAll(this.curPoses);
		this.curPoses.clear();
		this.curPoses.addAll(target);
		for (int i = 0; i < this.bones().length; i++) {
			BodyPose pose = target.get(i);
			this.bones()[i].pos.set(RigidMath.vec(pose.position()));
			this.bones()[i].rot.set(pose.rotation().x(), pose.rotation().y(), pose.rotation().z(), pose.rotation().w());
		}
		this.afterBonesMoved();

		Vec3 newTorso = RigidMath.vec(this.bones()[RagdollSkeleton.TORSO].pos);
		Quaterniond newRot = this.bones()[RagdollSkeleton.TORSO].rot;
		if (local != null && !local.isSpectator() && !local.isPassenger() && EntityContact.moved(oldTorso, oldRot, newTorso, newRot)) {
			if (standing) {
				EntityContact.carry(local, oldTorso, oldRot, newTorso, newRot);
			} else {
				EntityContact.pushOut(local, this.getCollisionBoxes());
			}
		}
	}

	private void applyDamping(boolean touching) {
		for (Bone bone : this.bones()) {
			bone.linVel.mul(touching ? 0.99 : 0.985);
			bone.angVel.mul(touching ? 0.93 : 0.98);
		}
		// Joint friction: limbs settle instead of swinging forever.
		for (RagdollSkeleton.Joint joint : this.skeleton().joints) {
			Bone a = this.bones()[joint.parent()], b = this.bones()[joint.child()];
			Vector3d rel = new Vector3d(b.angVel).sub(a.angVel).mul(0.1);
			double total = a.mass + b.mass;
			b.angVel.fma(-a.mass / total, rel);
			a.angVel.fma(b.mass / total, rel);
		}
	}

	private void updateSleep(boolean touching) {
		boolean still = touching;
		for (Bone bone : this.bones()) {
			if (bone.linVel.length() > 0.015 || bone.angVel.length() > 0.03) {
				still = false;
				break;
			}
		}
		if (still) {
			if (++this.stillTicks > 20) {
				this.asleep = true;
				for (Bone bone : this.bones()) {
					bone.linVel.zero();
					bone.angVel.zero();
				}
			}
		} else {
			this.stillTicks = 0;
		}
	}

	public void wake() {
		this.asleep = false;
		this.stillTicks = 0;
	}

	// ================================================================ simulation

	private static final class Contact {
		final int bone;
		final Vec3 point;
		final Vector3d normal;
		final double depth;
		final @Nullable PhysicsObject obstacle;
		final int obstaclePart;
		Vector3d r;
		/** Self-collision: the other bone of this ragdoll (pushed the opposite way), or -1. */
		int otherBone = -1;
		@Nullable Vector3d rOther;
		double normalMass;
		double targetVelocity;
		double closingSpeed;
		double normalImpulse;
		final Vector3d tangentImpulse = new Vector3d();

		Contact(int bone, Vec3 point, Vector3d normal, double depth, @Nullable PhysicsObject obstacle, int obstaclePart) {
			this.bone = bone;
			this.point = point;
			this.normal = normal;
			this.depth = depth;
			this.obstacle = obstacle;
			this.obstaclePart = obstaclePart;
		}
	}

	private static final class JointState {
		final RagdollSkeleton.Joint joint;
		Vector3d rA;
		Vector3d rB;
		Matrix3d inverseK;
		Vector3d bias;
		@Nullable Vector3d limitAxis;
		double limitTarget;
		double limitMass;
		double limitImpulse;

		JointState(RagdollSkeleton.Joint joint) {
			this.joint = joint;
		}
	}

	private int substep() {
		Bone[] bones = this.bones();
		Matrix3d[] invI = new Matrix3d[bones.length];
		for (int i = 0; i < bones.length; i++) {
			Bone bone = bones[i];
			invI[i] = bone.worldInvInertia();
			bone.linVel.y -= GRAVITY * this.dt;
			this.applyBuoyancy(bone, invI[i]);
		}

		List<Contact> contacts = this.detectContacts();
		this.correctPositions(contacts);
		for (int i = 0; i < bones.length; i++) {
			invI[i] = bones[i].worldInvInertia();
		}

		List<JointState> joints = new ArrayList<>();
		for (RagdollSkeleton.Joint joint : this.skeleton().joints) {
			joints.add(this.prepareJoint(joint, invI));
		}
		for (Contact c : contacts) {
			Bone bone = bones[c.bone];
			c.r = new Vector3d(c.point.x - bone.pos.x, c.point.y - bone.pos.y, c.point.z - bone.pos.z);
			c.normalMass = 1.0 / bone.mass + RigidMath.angularTerm(invI[c.bone], c.r, c.normal);
			if (c.otherBone >= 0) {
				Bone other = bones[c.otherBone];
				c.rOther = new Vector3d(c.point.x - other.pos.x, c.point.y - other.pos.y, c.point.z - other.pos.z);
				c.normalMass += 1.0 / other.mass + RigidMath.angularTerm(invI[c.otherBone], c.rOther, c.normal);
			}
			double vn = this.contactVelocity(c).dot(c.normal);
			c.closingSpeed = Math.max(0.0, -vn);
			double fade = Mth.clamp((-vn - 0.06) / 0.12, 0.0, 1.0);
			c.targetVelocity = c.otherBone >= 0 ? 0.0 : -RESTITUTION * fade * vn;
		}

		for (int iteration = 0; iteration < SOLVER_ITERATIONS; iteration++) {
			for (JointState js : joints) {
				this.solveJoint(js, invI);
			}
			for (Contact c : contacts) {
				this.solveContact(c, invI);
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

		for (Bone bone : bones) {
			if (bone.linVel.length() > MAX_SPEED) {
				bone.linVel.normalize(MAX_SPEED);
			}
			if (bone.angVel.length() > MAX_SPIN) {
				bone.angVel.normalize(MAX_SPIN);
			}
			double spin = bone.angVel.length();
			if (spin > 1.0E-9) {
				bone.rot.premul(new Quaterniond().rotationAxis(spin * this.dt, bone.angVel.x / spin, bone.angVel.y / spin, bone.angVel.z / spin)).normalize();
			}
			this.moveBone(bone, new Vector3d(bone.linVel).mul(this.dt), true);
		}
		for (Contact c : contacts) {
			if (c.obstacle == null && c.otherBone < 0) {
				this.noteImpact(c.closingSpeed, c.point);
			}
		}
		return contacts.size();
	}

	private void applyBuoyancy(Bone bone, Matrix3d invI) {
		Matrix3d r = new Matrix3d().set(bone.rot);
		double hy = Math.abs(r.m01) * bone.half.x + Math.abs(r.m11) * bone.half.y + Math.abs(r.m21) * bone.half.z;
		double bottom = bone.pos.y - hy;
		double surface = RealisticBlockEntity.fluidSurface(this.level(), bone.pos.x, bottom + 0.02, bone.pos.z, FluidTags.WATER);
		if (Double.isNaN(surface)) {
			return;
		}
		double f = Mth.clamp((surface - bottom) / (2 * hy), 0.0, 1.0);
		double volume = 8 * bone.half.x * bone.half.y * bone.half.z;
		bone.applyImpulse(new Vector3d(0, GRAVITY * volume * f * this.dt, 0), new Vector3d(0, -hy + f * hy, 0), invI);
		bone.linVel.mul(1.0 - 0.03 * f);
		bone.angVel.mul(1.0 - 0.03 * f);
	}

	private static final double[] SAMPLE = {-1, 0, 1};

	private List<Contact> detectContacts() {
		Bone[] bones = this.bones();
		List<Contact> contacts = new ArrayList<>();
		WorldCollider world = this.collider;
		for (int b = 0; b < bones.length; b++) {
			Bone bone = bones[b];
			AABB box = bone.worldBox();
			for (double sx : SAMPLE) {
				for (double sy : SAMPLE) {
					for (double sz : SAMPLE) {
						if (sx == 0 && sy == 0 && sz == 0) {
							continue;
						}
						Vec3 wp = bone.toWorld(new Vec3(sx * bone.half.x, sy * bone.half.y, sz * bone.half.z));
						Vec3 inward = new Vec3(bone.pos.x - wp.x, bone.pos.y - wp.y, bone.pos.z - wp.z);
						WorldCollider.Hit hit = world.penetration(wp, inward);
						if (hit != null) {
							contacts.add(new Contact(b, wp, new Vector3d(hit.normal().getStepX(), hit.normal().getStepY(), hit.normal().getStepZ()), hit.depth(), null, 0));
							continue;
						}
						for (PhysicsObject obstacle : this.nearbyObstacles) {
							if (((Entity) obstacle).getBoundingBox().contains(wp)) {
								Penetration pen = obstacle.penetration(wp);
								if (pen != null) {
									contacts.add(new Contact(b, wp, RigidMath.vec(pen.outwardNormal()), pen.depth(), obstacle, pen.part()));
									break;
								}
							}
						}
					}
				}
			}
			// Terrain corners poking into the bone (lying across a block edge).
			AABB region = box.inflate(0.02);
			for (BlockPos bp : BlockPos.betweenClosed(Mth.floor(region.minX), Mth.floor(region.minY) - 1, Mth.floor(region.minZ), Mth.floor(region.maxX), Mth.floor(region.maxY), Mth.floor(region.maxZ))) {
				for (AABB wb : world.blockInfo(bp).boxes()) {
					for (int i = 0; i < 8; i++) {
						Vec3 corner = new Vec3((i & 1) == 0 ? wb.minX : wb.maxX, (i & 2) == 0 ? wb.minY : wb.maxY, (i & 4) == 0 ? wb.minZ : wb.maxZ);
						if (!region.contains(corner)) {
							continue;
						}
						Penetration pen = this.bonePenetration(b, corner, new Vector3d(0, -1, 0), true);
						if (pen != null) {
							contacts.add(new Contact(b, corner, RigidMath.vec(pen.outwardNormal()).negate(), pen.depth(), null, 0));
						}
					}
				}
			}
		}
		this.detectSelfContacts(contacts);
		return contacts;
	}

	/**
	 * Limbs collide with each other and with the torso, so arms can't pass through the body and legs can't cross
	 * through each other. Bones are tested slightly shrunk so parts that merely touch at rest (arm against torso)
	 * don't fight, and around a joint the parent and child are allowed to overlap.
	 */
	private void detectSelfContacts(List<Contact> contacts) {
		Bone[] bones = this.bones();
		for (int a = 0; a < bones.length; a++) {
			for (int b = a + 1; b < bones.length; b++) {
				if (!bones[a].worldBox().intersects(bones[b].worldBox())) {
					continue;
				}
				RagdollSkeleton.Joint joint = this.jointBetween(a, b);
				Vec3 pivot = null;
				double exclusion = 0;
				if (joint != null) {
					// Joint pivot in world space, via the parent's rest-to-current transform.
					Vec3 rest = joint.pivot().subtract(this.skeleton().bones.get(joint.parent()).center());
					pivot = bones[joint.parent()].toWorld(rest);
					Vector3d childHalf = bones[joint.child()].half;
					exclusion = 0.55 * Math.max(childHalf.x, Math.max(childHalf.y, childHalf.z));
				}
				this.addSelfContacts(contacts, a, b, pivot, exclusion);
				this.addSelfContacts(contacts, b, a, pivot, exclusion);
			}
		}
	}

	/** Sample points of bone {@code a} inside bone {@code b}: contacts that push {@code a} out of {@code b}. */
	private void addSelfContacts(List<Contact> contacts, int a, int b, @Nullable Vec3 pivot, double exclusion) {
		Bone bone = this.bones()[a];
		for (double sx : SAMPLE) {
			for (double sy : SAMPLE) {
				for (double sz : SAMPLE) {
					if (sx == 0 && sy == 0 && sz == 0) {
						continue;
					}
					Vec3 wp = bone.toWorld(new Vec3(sx * bone.half.x, sy * bone.half.y, sz * bone.half.z));
					if (pivot != null && wp.distanceTo(pivot) < exclusion) {
						continue;
					}
					Penetration pen = this.bonePenetration(b, wp, UP, false, SELF_COLLISION_MARGIN);
					if (pen != null) {
						Contact c = new Contact(a, wp, RigidMath.vec(pen.outwardNormal()), pen.depth(), null, 0);
						c.otherBone = b;
						contacts.add(c);
					}
				}
			}
		}
	}

	private RagdollSkeleton.@Nullable Joint jointBetween(int a, int b) {
		for (RagdollSkeleton.Joint joint : this.skeleton().joints) {
			if ((joint.parent() == a && joint.child() == b) || (joint.parent() == b && joint.child() == a)) {
				return joint;
			}
		}
		return null;
	}

	private @Nullable Penetration bonePenetration(int b, Vec3 world, Vector3d prefer, boolean rejectAmbiguous) {
		return this.bonePenetration(b, world, prefer, rejectAmbiguous, 0.0);
	}

	/** Shallowest way out of a bone (shrunk by {@code margin}) for a world point; the normal is the bone's outward face normal. */
	private @Nullable Penetration bonePenetration(int b, Vec3 world, Vector3d prefer, boolean rejectAmbiguous, double margin) {
		Bone bone = this.bones()[b];
		Vec3 l = bone.toLocal(world);
		double hx = bone.half.x - margin, hy = bone.half.y - margin, hz = bone.half.z - margin;
		if (hx <= 0 || hy <= 0 || hz <= 0 || Math.abs(l.x) > hx || Math.abs(l.y) > hy || Math.abs(l.z) > hz) {
			return null;
		}
		double[] depths = {hx - l.x, hx + l.x, hy - l.y, hy + l.y, hz - l.z, hz + l.z};
		Vector3d[] axes = {new Vector3d(1, 0, 0), new Vector3d(-1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, -1, 0), new Vector3d(0, 0, 1), new Vector3d(0, 0, -1)};
		int best = -1;
		double bestScore = Double.MAX_VALUE;
		int shallow = 0;
		for (int i = 0; i < 6; i++) {
			if (depths[i] < 0.03) {
				shallow++;
			}
			Vector3d n = bone.rot.transform(new Vector3d(axes[i]));
			double score = depths[i] + 0.05 * (1.0 - n.dot(prefer));
			if (score < bestScore) {
				bestScore = score;
				best = i;
			}
		}
		if (rejectAmbiguous && shallow >= 2) {
			return null;
		}
		return new Penetration(b, depths[best], RigidMath.vec(bone.rot.transform(new Vector3d(axes[best]))));
	}

	private void correctPositions(List<Contact> contacts) {
		Bone[] bones = this.bones();
		Vector3d[] corrections = new Vector3d[bones.length];
		for (int i = 0; i < bones.length; i++) {
			corrections[i] = new Vector3d();
		}
		for (int iteration = 0; iteration < 4; iteration++) {
			for (Contact c : contacts) {
				if (c.otherBone >= 0) {
					// Both bones are free: split the push between them.
					double remaining = c.depth - (corrections[c.bone].dot(c.normal) - corrections[c.otherBone].dot(c.normal));
					if (remaining > SLOP) {
						double move = (remaining - SLOP) * 0.25;
						corrections[c.bone].fma(move, c.normal);
						corrections[c.otherBone].fma(-move, c.normal);
					}
					continue;
				}
				double remaining = c.depth * (c.obstacle != null ? 0.7 : 1.0) - corrections[c.bone].dot(c.normal);
				if (remaining > SLOP) {
					corrections[c.bone].fma((remaining - SLOP) * 0.5, c.normal);
				}
			}
		}
		for (int i = 0; i < bones.length; i++) {
			if (corrections[i].length() > 0.3) {
				corrections[i].normalize(0.3);
			}
			this.moveBone(bones[i], corrections[i], false);
		}
	}

	/**
	 * Moves a bone, stopping its centre at the first terrain surface in the way, so a body can never pass through a
	 * wall, floor or pane however hard it is thrown, dragged or pushed: whatever box it is in, it stays in.
	 */
	private void moveBone(Bone bone, Vector3d delta, boolean killVelocity) {
		if (this.collider == null) {
			bone.pos.add(delta);
			return;
		}
		Vec3 from = RigidMath.vec(bone.pos);
		Vec3 to = from.add(delta.x, delta.y, delta.z);
		WorldCollider.Sweep hit = this.collider.sweep(from, to);
		if (hit == null) {
			bone.pos.add(delta);
			return;
		}
		Vector3d n = new Vector3d(hit.normal().getStepX(), hit.normal().getStepY(), hit.normal().getStepZ());
		bone.pos.set(hit.point().x + n.x * 0.01, hit.point().y + n.y * 0.01, hit.point().z + n.z * 0.01);
		if (killVelocity) {
			double into = bone.linVel.dot(n);
			if (into < 0) {
				bone.linVel.fma(-into * (1.0 + RESTITUTION), n);
				this.noteImpact(-into, hit.point());
			}
		}
	}

	private JointState prepareJoint(RagdollSkeleton.Joint joint, Matrix3d[] invI) {
		Bone a = this.bones()[joint.parent()], b = this.bones()[joint.child()];
		Vec3 pivot = joint.pivot();
		Vec3 ca = this.skeleton().bones.get(joint.parent()).center(), cb = this.skeleton().bones.get(joint.child()).center();
		JointState js = new JointState(joint);
		js.rA = a.rot.transform(new Vector3d(pivot.x - ca.x, pivot.y - ca.y, pivot.z - ca.z));
		js.rB = b.rot.transform(new Vector3d(pivot.x - cb.x, pivot.y - cb.y, pivot.z - cb.z));
		Matrix3d k = RigidMath.pointMassMatrix(1.0 / a.mass, invI[joint.parent()], js.rA)
			.add(RigidMath.pointMassMatrix(1.0 / b.mass, invI[joint.child()], js.rB));
		js.inverseK = k.invert();
		Vector3d error = new Vector3d(b.pos).add(js.rB).sub(new Vector3d(a.pos).add(js.rA));
		js.bias = error.mul(JOINT_STIFFNESS / this.dt);

		// Rotation of the child away from its rest orientation relative to the parent (both start unrotated).
		Quaterniond err = new Quaterniond(b.rot).mul(new Quaterniond(a.rot).conjugate());
		if (err.w < 0) {
			err.set(-err.x, -err.y, -err.z, -err.w);
		}
		double angle = 2 * Math.acos(Math.min(1.0, err.w));
		if (angle > joint.limit()) {
			Vector3d axis = new Vector3d(err.x, err.y, err.z);
			if (axis.length() > 1.0E-9) {
				axis.normalize();
				js.limitAxis = axis;
				js.limitTarget = -JOINT_STIFFNESS * (angle - joint.limit()) / this.dt;
				js.limitMass = 1.0 / (axis.dot(invI[joint.parent()].transform(new Vector3d(axis))) + axis.dot(invI[joint.child()].transform(new Vector3d(axis))));
			}
		}
		return js;
	}

	private void solveJoint(JointState js, Matrix3d[] invI) {
		int ia = js.joint.parent(), ib = js.joint.child();
		Bone a = this.bones()[ia], b = this.bones()[ib];
		Vector3d cdot = b.velocityAt(js.rB).sub(a.velocityAt(js.rA)).add(js.bias);
		Vector3d impulse = js.inverseK.transform(cdot).negate();
		b.applyImpulse(impulse, js.rB, invI[ib]);
		a.applyImpulse(new Vector3d(impulse).negate(), js.rA, invI[ia]);

		if (js.limitAxis != null) {
			double relative = new Vector3d(b.angVel).sub(a.angVel).dot(js.limitAxis);
			double newImpulse = Math.min(js.limitImpulse + (js.limitTarget - relative) * js.limitMass, 0.0);
			double delta = newImpulse - js.limitImpulse;
			js.limitImpulse = newImpulse;
			Vector3d angular = new Vector3d(js.limitAxis).mul(delta);
			b.angVel.add(invI[ib].transform(new Vector3d(angular)));
			a.angVel.sub(invI[ia].transform(new Vector3d(angular)));
		}
	}

	private Vector3d contactVelocity(Contact c) {
		Vector3d v = this.bones()[c.bone].velocityAt(c.r);
		if (c.otherBone >= 0 && c.rOther != null) {
			v.sub(this.bones()[c.otherBone].velocityAt(c.rOther));
		}
		return v;
	}

	private void applyContactImpulse(Contact c, Vector3d impulse, Matrix3d[] invI) {
		this.bones()[c.bone].applyImpulse(impulse, c.r, invI[c.bone]);
		if (c.otherBone >= 0 && c.rOther != null) {
			this.bones()[c.otherBone].applyImpulse(new Vector3d(impulse).negate(), c.rOther, invI[c.otherBone]);
		}
	}

	private void solveContact(Contact c, Matrix3d[] invI) {
		Vector3d v = this.contactVelocity(c);
		double vn = v.dot(c.normal);
		double newImpulse = Math.max(c.normalImpulse + (c.targetVelocity - vn) / c.normalMass, 0.0);
		double delta = newImpulse - c.normalImpulse;
		c.normalImpulse = newImpulse;
		this.applyContactImpulse(c, new Vector3d(c.normal).mul(delta), invI);

		v = this.contactVelocity(c);
		Vector3d vt = new Vector3d(v).fma(-v.dot(c.normal), c.normal);
		double slide = vt.length();
		if (slide < 1.0E-9) {
			return;
		}
		Vector3d t = new Vector3d(vt).div(slide);
		Bone bone = this.bones()[c.bone];
		double tangentMass = 1.0 / bone.mass + RigidMath.angularTerm(invI[c.bone], c.r, t);
		if (c.otherBone >= 0 && c.rOther != null) {
			tangentMass += 1.0 / this.bones()[c.otherBone].mass + RigidMath.angularTerm(invI[c.otherBone], c.rOther, t);
		}
		Vector3d newTangent = new Vector3d(c.tangentImpulse).fma(-slide / tangentMass, t);
		double max = (c.otherBone >= 0 ? SELF_FRICTION : FRICTION) * c.normalImpulse;
		if (newTangent.length() > max) {
			newTangent.normalize(max);
		}
		Vector3d tangentDelta = new Vector3d(newTangent).sub(c.tangentImpulse);
		c.tangentImpulse.set(newTangent);
		this.applyContactImpulse(c, tangentDelta, invI);
	}

	// ================================================================ entities

	private void interactWithEntities(ServerLevel level, Vec3 oldPos, Quaterniond oldRot) {
		List<AABB> boxes = this.getCollisionBoxes();
		boolean moved = EntityContact.moved(oldPos, oldRot, this.position(), this.bones()[RagdollSkeleton.TORSO].rot);
		for (Entity e : level.getEntities(this, this.getBoundingBox().inflate(1.0), e -> !(e instanceof PhysicsObject) && e.isAlive() && !e.isSpectator() && !e.noPhysics && !e.isPassenger())) {
			if (EntityContact.isStandingOn(e, boxes)) {
				continue;
			}
			if (moved && !(e instanceof Player)) {
				EntityContact.pushOut(e, boxes);
			}
			if (e instanceof ServerPlayer player && !this.frozen) {
				this.pushedByPlayer(player, boxes);
			}
		}
	}

	private void pushedByPlayer(ServerPlayer player, List<AABB> boxes) {
		Input input = player.getLastClientInput();
		if (!input.forward() && !input.backward() && !input.left() && !input.right()) {
			return;
		}
		AABB pb = player.getBoundingBox().inflate(0.1, 0.0, 0.1);
		for (int i = 0; i < boxes.size(); i++) {
			AABB box = boxes.get(i);
			if (box.intersects(pb)) {
				Vec3 dir = box.getCenter().subtract(player.position()).multiply(1, 0, 1);
				if (dir.lengthSqr() > 1.0E-6) {
					this.applyPartImpulse(i, dir.normalize().scale(0.04 * this.bones()[i].mass * 10), box.getCenter());
				}
				return;
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (this.isRemoved() || this.isInvulnerableToBase(source)) {
			return false;
		}
		if (this.recoverFrom != null && this.getFirstPassenger() instanceof LivingEntity rider) {
			// Hitting a knocked-down body hurts whoever it is; the damage event flings the body.
			return rider.hurtServer(level, source, damage);
		}
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			return false;
		}
		if (!(source.getDirectEntity() instanceof Player player)) {
			return false;
		}
		if (player.isSecondaryUseActive()) {
			level.sendParticles(ParticleTypes.POOF, this.getX(), this.getY(), this.getZ(), 20, 0.4, 0.4, 0.4, 0.02);
			this.discard();
			return true;
		}
		this.frozen = false;
		Vec3 eye = player.getEyePosition();
		PartHit hit = this.raycastPart(eye, eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange() + 3.0)));
		double strength = (0.08 + 0.02 * Math.min(damage, 10.0F)) * (player.isSprinting() ? 1.5 : 1.0);
		Vec3 impulse = player.getLookAngle().scale(strength).add(0.0, 0.03, 0.0);
		if (hit != null) {
			this.applyPartImpulse(hit.part(), impulse, hit.point());
		} else {
			this.applyPartImpulse(RagdollSkeleton.TORSO, impulse, this.position());
		}
		this.playSound(SoundEvents.PLAYER_ATTACK_WEAK, 1.0F, 0.8F);
		return true;
	}

	@Override
	public void pushFromExplosion(Vec3 impulse) {
		double power = impulse.length();
		if (power < 0.03) {
			return;
		}
		this.frozen = false;
		this.wake();
		for (Bone bone : this.bones()) {
			bone.linVel.add(impulse.x * 1.4, impulse.y * 1.4 + 0.1 * power, impulse.z * 1.4);
			bone.angVel.add(this.random.nextGaussian() * 0.3 * power, this.random.nextGaussian() * 0.3 * power, this.random.nextGaussian() * 0.3 * power);
		}
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

	@Override
	protected Component getTypeName() {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.tryParse(this.getInfo().entityType()));
		return type == null ? super.getTypeName() : Component.translatable("entity.realistic_ificator.ragdoll.of", type.getDescription());
	}

	// ================================================================ PhysicsObject

	@Override
	public @Nullable PartHit raycastPart(Vec3 from, Vec3 to) {
		PartHit best = null;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < this.bones().length; i++) {
			Bone bone = this.bones()[i];
			AABB local = new AABB(-bone.half.x, -bone.half.y, -bone.half.z, bone.half.x, bone.half.y, bone.half.z);
			var hit = local.clip(bone.toLocal(from), bone.toLocal(to));
			if (hit.isPresent()) {
				Vec3 world = bone.toWorld(hit.get());
				double d = world.distanceToSqr(from);
				if (d < bestDist) {
					bestDist = d;
					best = new PartHit(i, world);
				}
			}
		}
		return best;
	}

	@Override
	public Vec3 partToWorld(int part, Vec3 local) {
		return this.bones()[part].toWorld(local);
	}

	@Override
	public Vec3 worldToPart(int part, Vec3 world) {
		return this.bones()[part].toLocal(world);
	}

	@Override
	public Vec3 partVelocityAt(int part, Vec3 world) {
		Bone bone = this.bones()[part];
		return RigidMath.vec(bone.velocityAt(new Vector3d(world.x - bone.pos.x, world.y - bone.pos.y, world.z - bone.pos.z)));
	}

	@Override
	public void applyPartImpulse(int part, Vec3 impulse, Vec3 world) {
		if (this.frozen && this.tickCount > this.heldUntil) {
			return;
		}
		this.wake();
		Bone bone = this.bones()[part];
		bone.applyImpulse(RigidMath.vec(impulse), new Vector3d(world.x - bone.pos.x, world.y - bone.pos.y, world.z - bone.pos.z), bone.worldInvInertia());
	}

	@Override
	public void drivePoint(int part, Vec3 world, Vec3 desired, double strength) {
		this.wake();
		Bone bone = this.bones()[part];
		Matrix3d invI = bone.worldInvInertia();
		Vector3d r = new Vector3d(world.x - bone.pos.x, world.y - bone.pos.y, world.z - bone.pos.z);
		Matrix3d k = RigidMath.pointMassMatrix(1.0 / bone.mass, invI, r);
		Vector3d dv = RigidMath.vec(desired).sub(bone.velocityAt(r));
		bone.applyImpulse(k.invert().transform(dv).mul(strength), r, invI);
	}

	@Override
	public void dampPartSpin(int part, double factor) {
		this.bones()[part].angVel.mul(factor);
	}

	@Override
	public double partMass(int part) {
		return this.bones()[part].mass;
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
		for (Bone bone : this.bones()) {
			bone.linVel.zero();
			bone.angVel.zero();
		}
		if (!frozen) {
			this.wake();
		}
	}

	@Override
	public boolean isFrozen() {
		return this.frozen;
	}

	@Override
	public @Nullable Penetration penetration(Vec3 world) {
		Penetration best = null;
		for (int i = 0; i < this.bones().length; i++) {
			Penetration pen = this.bonePenetration(i, world, UP, false);
			if (pen != null && (best == null || pen.depth() < best.depth())) {
				best = pen;
			}
		}
		return best;
	}

	// ================================================================ persistence

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.store("Ragdoll", RagdollInfo.CODEC, this.getInfo());
		List<SavedBone> saved = new ArrayList<>();
		for (Bone bone : this.bones()) {
			saved.add(new SavedBone(RigidMath.vec(bone.pos), new Quaternionf((float) bone.rot.x, (float) bone.rot.y, (float) bone.rot.z, (float) bone.rot.w), RigidMath.vec(bone.linVel), RigidMath.vec(bone.angVel)));
		}
		output.store("Bones", SavedBone.CODEC.listOf(), saved);
		output.putBoolean("Asleep", this.asleep);
		output.putBoolean("Frozen", this.frozen);
		output.storeNullable("RecoverFrom", Vec3.CODEC, this.recoverFrom);
		output.putBoolean("RestoreAi", this.restoreAi);
		output.putInt("DespawnIn", this.despawnAt < 0 ? -1 : Math.max(0, this.despawnAt - this.tickCount));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		input.read("Ragdoll", RagdollInfo.CODEC).ifPresent(this::setInfo);
		input.read("Bones", SavedBone.CODEC.listOf()).ifPresent(saved -> {
			this.bonesPlaced = true;
			for (int i = 0; i < Math.min(saved.size(), this.bones().length); i++) {
				SavedBone s = saved.get(i);
				Bone bone = this.bones()[i];
				bone.pos.set(RigidMath.vec(s.pos()));
				bone.rot.set(s.rot().x(), s.rot().y(), s.rot().z(), s.rot().w());
				bone.linVel.set(RigidMath.vec(s.linVel()));
				bone.angVel.set(RigidMath.vec(s.angVel()));
			}
		});
		this.asleep = input.getBooleanOr("Asleep", false);
		this.frozen = input.getBooleanOr("Frozen", false);
		this.recoverFrom = input.read("RecoverFrom", Vec3.CODEC).orElse(null);
		this.restoreAi = input.getBooleanOr("RestoreAi", false);
		int despawnIn = input.getIntOr("DespawnIn", -1);
		this.despawnAt = despawnIn < 0 ? -1 : this.tickCount + despawnIn;
		if (this.bonesPlaced) {
			this.afterBonesMoved();
			this.syncPoses();
		}
	}

	/** Creates a mob of the given type at a block, for turning into a corpse. */
	public static @Nullable LivingEntity createMob(ServerLevel level, EntityType<?> type, BlockPos pos) {
		Entity entity = type.create(level, null, pos, EntitySpawnReason.SPAWN_ITEM_USE, false, false);
		if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
			return living;
		}
		if (entity != null) {
			entity.discard();
		}
		return null;
	}
}
