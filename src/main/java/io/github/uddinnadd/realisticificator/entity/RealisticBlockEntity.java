package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.item.RealisticItems;
import io.github.uddinnadd.realisticificator.physics.Densities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.entity.MoverType;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A "realistic" block: a server-simulated rigid body made of one or more block cells.
 * <p>
 * Collision stays axis aligned so players and other entities can stand on it. Tumbling is a visual
 * rotation that, once the body comes to rest, snaps to the nearest 90° orientation and is baked into
 * the cells, so the collision shape always matches what you see when it is still.
 */
public class RealisticBlockEntity extends Entity {
	public static final int MAX_CELLS = 64;
	private static final double GRAVITY = 0.04;
	private static final int LEVEL_EVENT_BLOCK_BREAK = 2001;

	private static final EntityDataAccessor<BlockStructure> DATA_STRUCTURE = SynchedEntityData.defineId(RealisticBlockEntity.class, BlockStructure.SERIALIZER);
	private static final EntityDataAccessor<org.joml.Quaternionfc> DATA_ORIENTATION = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.QUATERNION);

	// No initializer on purpose: makeBoundingBox() runs inside the Entity constructor before field initializers.
	private @Nullable BlockStructure structure;
	private @Nullable List<AABB> cachedCellBoxes;
	private @Nullable Vec3 cachedCellBoxesPos;

	private final Quaternionf orientation = new Quaternionf();
	private final Vector3f angularVelocity = new Vector3f();
	private int tipTicks;

	private final Quaternionf renderRotationO = new Quaternionf();
	private final Quaternionf renderRotation = new Quaternionf();

	public RealisticBlockEntity(EntityType<? extends RealisticBlockEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	public RealisticBlockEntity(Level level, double x, double y, double z, BlockStructure structure) {
		this(io.github.uddinnadd.realisticificator.registry.ModEntities.REALISTIC_BLOCK, level);
		this.setStructure(structure);
		this.setPos(x, y, z);
		this.xo = x;
		this.yo = y;
		this.zo = z;
	}

	// ---------------------------------------------------------------- structure & shape

	public BlockStructure getStructure() {
		return this.structure == null ? BlockStructure.EMPTY : this.structure;
	}

	public void setStructure(BlockStructure structure) {
		this.structure = structure;
		this.cachedCellBoxes = null;
		this.entityData.set(DATA_STRUCTURE, structure);
		this.setBoundingBox(this.makeBoundingBox());
	}

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		BlockStructure s = this.structure;
		if (s == null || s.isEmpty()) {
			return super.makeBoundingBox(position);
		}
		AABB box = null;
		for (BlockPos c : s.cells().keySet()) {
			AABB cell = cellBox(position, c);
			box = box == null ? cell : box.minmax(cell);
		}
		return box;
	}

	private static AABB cellBox(Vec3 origin, BlockPos c) {
		double x = origin.x + c.getX() - 0.5, y = origin.y + c.getY(), z = origin.z + c.getZ() - 0.5;
		return new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0);
	}

	/** World-space boxes of every cell; used for precise entity collision (see LevelMixin). */
	public List<AABB> getCellBoxes() {
		Vec3 pos = this.position();
		if (this.cachedCellBoxes == null || !pos.equals(this.cachedCellBoxesPos)) {
			List<AABB> boxes = new ArrayList<>(this.getStructure().size());
			for (BlockPos c : this.getStructure().cells().keySet()) {
				boxes.add(cellBox(pos, c));
			}
			this.cachedCellBoxes = boxes;
			this.cachedCellBoxesPos = pos;
		}
		return this.cachedCellBoxes;
	}

	public Vec3 getWorldCenterOfMass() {
		return this.position().add(this.getStructure().centerOfMass());
	}

	// ---------------------------------------------------------------- synced data

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_STRUCTURE, BlockStructure.EMPTY);
		builder.define(DATA_ORIENTATION, new Quaternionf());
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_STRUCTURE.equals(accessor)) {
			this.structure = this.entityData.get(DATA_STRUCTURE);
			this.cachedCellBoxes = null;
			this.setBoundingBox(this.makeBoundingBox());
			// Structure changes come with a baked rotation; don't interpolate across that jump.
			this.renderRotation.set(this.entityData.get(DATA_ORIENTATION));
			this.renderRotationO.set(this.renderRotation);
		}
	}

	public Quaternionf getRenderOrientation(float partialTicks, Quaternionf dest) {
		return this.renderRotationO.slerp(this.renderRotation, partialTicks, dest);
	}

	// ---------------------------------------------------------------- ticking / physics

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) {
			this.renderRotationO.set(this.renderRotation);
			this.renderRotation.set(this.entityData.get(DATA_ORIENTATION));
			return;
		}
		if (this.getStructure().isEmpty()) {
			this.discard();
			return;
		}
		this.simulate((ServerLevel) this.level());
	}

	private void simulate(ServerLevel level) {
		BlockStructure s = this.getStructure();
		double mass = s.mass();
		Vec3 pos = this.position();

		// Buoyancy: per-cell submerged fraction times fluid density (Archimedes).
		double displaced = 0.0;
		double submerged = 0.0;
		for (BlockPos c : s.cells().keySet()) {
			double cx = pos.x + c.getX(), minY = pos.y + c.getY(), cz = pos.z + c.getZ();
			double water = this.fluidSurface(cx, minY + 0.05, cz, FluidTags.WATER);
			if (!Double.isNaN(water)) {
				double f = Mth.clamp(water - minY, 0.0, 1.0);
				displaced += f * Densities.WATER;
				submerged += f;
				continue;
			}
			double lava = this.fluidSurface(cx, minY + 0.05, cz, FluidTags.LAVA);
			if (!Double.isNaN(lava)) {
				double f = Mth.clamp(lava - minY, 0.0, 1.0);
				displaced += f * Densities.LAVA;
				submerged += f;
			}
		}
		double submergedFraction = submerged / s.size();
		boolean inFluid = submergedFraction > 0.0;

		Vec3 v = this.getDeltaMovement().add(0.0, -GRAVITY + GRAVITY * displaced / mass, 0.0);
		if (inFluid) {
			v = new Vec3(v.x * 0.9, v.y * 0.82, v.z * 0.9);
		}
		v = v.add(this.entityPushes(level, mass));
		this.setDeltaMovement(v);

		boolean wasOnGround = this.onGround();
		double fallSpeed = -v.y;
		this.move(MoverType.SELF, this.getDeltaMovement());
		v = this.getDeltaMovement();

		if (this.onGround()) {
			if (!wasOnGround && fallSpeed > 0.2) {
				this.onLanded(level, fallSpeed);
				v = this.getDeltaMovement();
			}
			if (this.tipTicks <= 0) {
				double friction = level.getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction() * 0.85;
				v = new Vec3(v.x * friction, v.y, v.z * friction);
			}
		} else {
			v = v.scale(0.98);
		}
		this.setDeltaMovement(v);

		this.updateRotation(level, inFluid);
	}

	/** World Y of the fluid surface above (x, y, z), or NaN if that point is not inside the fluid. */
	private double fluidSurface(double x, double y, double z, TagKey<Fluid> fluid) {
		BlockPos pos = BlockPos.containing(x, y, z);
		FluidState state = this.level().getFluidState(pos);
		if (!state.is(fluid)) {
			return Double.NaN;
		}
		double top = pos.getY() + state.getHeight(this.level(), pos);
		BlockPos above = pos.above();
		for (int i = 0; i < 2; i++) {
			FluidState aboveState = this.level().getFluidState(above);
			if (!aboveState.is(fluid)) {
				break;
			}
			top = above.getY() + aboveState.getHeight(this.level(), above);
			above = above.above();
		}
		return top;
	}

	/** Living entities walking into the body shove it; heavier bodies barely budge. */
	private Vec3 entityPushes(ServerLevel level, double mass) {
		AABB box = this.getBoundingBox();
		Vec3 com = this.getWorldCenterOfMass();
		Vec3 total = Vec3.ZERO;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box.inflate(0.12, 0.0, 0.12), e -> e.isAlive() && !e.isSpectator() && !e.noPhysics)) {
			if (e.getBoundingBox().minY >= box.maxY - 0.1) {
				continue; // standing on top
			}
			Vec3 toward = new Vec3(com.x - e.getX(), 0.0, com.z - e.getZ());
			if (toward.lengthSqr() < 1.0E-4) {
				continue;
			}
			toward = toward.normalize();
			Vec3 intent;
			if (e instanceof ServerPlayer player) {
				// Players are stopped by the collision, so use their movement input rather than their velocity.
				Input input = player.getLastClientInput();
				float forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
				float strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
				if (forward == 0 && strafe == 0) {
					continue;
				}
				float yaw = player.getYRot() * Mth.DEG_TO_RAD;
				float sin = Mth.sin(yaw), cos = Mth.cos(yaw);
				intent = new Vec3(strafe * cos - forward * sin, 0.0, forward * cos + strafe * sin).normalize().scale(player.isSprinting() ? 1.3 : 1.0);
			} else {
				intent = e.getDeltaMovement().multiply(5.0, 0.0, 5.0);
			}
			double along = intent.dot(toward);
			if (along > 0.2) {
				total = total.add(toward.scale(0.11 * along * Math.min(1.0, 1.4 / mass)));
			}
		}
		return total;
	}

	private void onLanded(ServerLevel level, double fallSpeed) {
		BlockState state = this.getStructure().primaryState();
		SoundType sound = state.getSoundType();
		this.playSound(sound.getFallSound(), (float) Math.min(1.5, 0.4 + fallSpeed), sound.getPitch() * 0.8F);
		AABB box = this.getBoundingBox();
		level.sendParticles(
			new BlockParticleOption(ParticleTypes.BLOCK, state),
			(box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2,
			(int) Math.min(40, 6 * this.getStructure().size() * fallSpeed),
			box.getXsize() / 4, 0.0, box.getZsize() / 4, 0.15
		);
		if (fallSpeed > 0.6) {
			Vec3 v = this.getDeltaMovement();
			this.setDeltaMovement(v.x, fallSpeed * 0.15, v.z);
		}
	}

	private void updateRotation(ServerLevel level, boolean inFluid) {
		float speed = this.angularVelocity.length();
		if (speed > 1.0E-4F) {
			Vector3f axis = new Vector3f(this.angularVelocity).div(speed);
			this.orientation.premul(new Quaternionf().rotationAxis(speed, axis)).normalize();
		}

		if (this.tipTicks > 0) {
			this.tipTicks--;
		}
		if (this.onGround() || inFluid) {
			if (this.onGround() && this.isAxisAligned()) {
				this.checkTipping(level);
			}
			if (this.tipTicks <= 0) {
				this.angularVelocity.mul(this.onGround() ? 0.7F : 0.9F);
				if (this.angularVelocity.length() < 0.08F) {
					this.settle();
				}
			}
		} else {
			this.angularVelocity.mul(0.995F);
		}

		if (!this.entityData.get(DATA_ORIENTATION).equals(this.orientation)) {
			this.entityData.set(DATA_ORIENTATION, new Quaternionf(this.orientation));
		}
	}

	private boolean isAxisAligned() {
		return Math.abs(this.orientation.w) > 0.9999F;
	}

	/** If the centre of mass hangs past the supported footprint, tip over that edge. */
	private void checkTipping(ServerLevel level) {
		BlockStructure s = this.getStructure();
		int minCellY = s.minY();
		double sMinX = Double.POSITIVE_INFINITY, sMinZ = Double.POSITIVE_INFINITY, sMaxX = Double.NEGATIVE_INFINITY, sMaxZ = Double.NEGATIVE_INFINITY;
		for (BlockPos c : s.cells().keySet()) {
			if (c.getY() != minCellY) {
				continue;
			}
			AABB foot = cellBox(this.position(), c);
			AABB probe = new AABB(foot.minX, foot.minY - 0.06, foot.minZ, foot.maxX, foot.minY, foot.maxZ);
			List<AABB> supports = new ArrayList<>();
			for (VoxelShape shape : level.getBlockCollisions(this, probe)) {
				supports.addAll(shape.toAabbs());
			}
			for (VoxelShape shape : level.getEntityCollisions(this, probe)) {
				supports.addAll(shape.toAabbs());
			}
			for (AABB support : supports) {
				if (!support.intersects(probe)) {
					continue;
				}
				AABB contact = support.intersect(probe);
				sMinX = Math.min(sMinX, contact.minX);
				sMaxX = Math.max(sMaxX, contact.maxX);
				sMinZ = Math.min(sMinZ, contact.minZ);
				sMaxZ = Math.max(sMaxZ, contact.maxZ);
			}
		}
		if (sMinX > sMaxX) {
			return;
		}
		Vec3 com = this.getWorldCenterOfMass();
		double cx = Mth.clamp(com.x, sMinX, sMaxX), cz = Mth.clamp(com.z, sMinZ, sMaxZ);
		Vec3 overhang = new Vec3(com.x - cx, 0.0, com.z - cz);
		if (overhang.lengthSqr() < 1.0E-4) {
			return;
		}
		Vec3 dir = overhang.normalize();
		// Rotating about (up x dir) swings the top of the body towards dir.
		this.angularVelocity.set((float) dir.z, 0.0F, (float) -dir.x).mul(0.14F);
		this.setDeltaMovement(this.getDeltaMovement().add(dir.scale(0.07)));
		this.tipTicks = 4;
	}

	/** Ease towards the nearest 90° orientation; once there, bake it into the cells. */
	private void settle() {
		Matrix3f snapped = snap(this.orientation);
		Quaternionf target = new Quaternionf().setFromNormalized(snapped);
		float dot = Math.abs(this.orientation.dot(target));
		if (dot > 0.99995F) {
			this.orientation.identity();
			this.angularVelocity.zero();
			if (Math.abs(target.w) < 0.9999F) {
				this.bakeRotation(snapped);
			}
		} else {
			this.orientation.slerp(target, 0.35F).normalize();
		}
	}

	private static Matrix3f snap(Quaternionf q) {
		Matrix3f m = new Matrix3f().set(q);
		Vector3f c0 = m.getColumn(0, new Vector3f());
		Vector3f c1 = m.getColumn(1, new Vector3f());
		int a0 = maxAxis(c0, -1);
		Vector3f e0 = unit(a0, Math.signum(c0.get(a0)));
		int a1 = maxAxis(c1, a0);
		Vector3f e1 = unit(a1, Math.signum(c1.get(a1)));
		Vector3f e2 = new Vector3f(e0).cross(e1);
		return new Matrix3f(e0, e1, e2);
	}

	private static int maxAxis(Vector3f v, int exclude) {
		int best = -1;
		for (int i = 0; i < 3; i++) {
			if (i != exclude && (best == -1 || Math.abs(v.get(i)) > Math.abs(v.get(best)))) {
				best = i;
			}
		}
		return best;
	}

	private static Vector3f unit(int axis, float sign) {
		Vector3f v = new Vector3f();
		v.setComponent(axis, sign == 0 ? 1 : sign);
		return v;
	}

	private void bakeRotation(Matrix3f rotation) {
		BlockStructure.Rotated rotated = this.getStructure().rotate(rotation);
		Vec3 pos = this.position().add(rotated.positionOffset());
		this.setStructure(rotated.structure());
		this.setPos(pos);
		// The visual rotation happened about the centre of mass, so the baked shape may poke into the ground.
		for (int i = 0; i < 20 && !this.level().noCollision(this, this.getBoundingBox().deflate(1.0E-3)); i++) {
			this.setPos(this.getX(), this.getY() + 0.1, this.getZ());
		}
	}

	// ---------------------------------------------------------------- interaction

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
		if (!this.isAxisAligned() || this.getStructure().size() >= MAX_CELLS) {
			return InteractionResult.FAIL;
		}

		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange() + 2.0));
		BlockHitResult best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos c : this.getStructure().cells().keySet()) {
			BlockHitResult hit = AABB.clip(List.of(cellBox(this.position(), c)), eye, end, c);
			if (hit != null) {
				double d = hit.getLocation().distanceToSqr(eye);
				if (d < bestDist) {
					bestDist = d;
					best = hit;
				}
			}
		}
		if (best == null) {
			return InteractionResult.FAIL;
		}

		BlockPos newCell = best.getBlockPos().relative(best.getDirection());
		AABB newBox = cellBox(this.position(), newCell).deflate(1.0E-3);
		if (this.getStructure().contains(newCell)
			|| !this.level().noCollision(this, newBox)
			|| !this.level().getEntities(this, newBox, e -> e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e.canBeCollidedWith(this))).isEmpty()) {
			return InteractionResult.FAIL;
		}

		BlockState state = placementState(blockItem.getBlock(), best.getDirection());
		this.setStructure(this.getStructure().with(newCell, state));
		SoundType sound = state.getSoundType();
		this.level().playSound(null, newBox.getCenter().x, newBox.getCenter().y, newBox.getCenter().z, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
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
		if (source.getDirectEntity() instanceof Player player) {
			if (player.isSecondaryUseActive()) {
				this.breakApart(level, !player.hasInfiniteMaterials());
				return true;
			}
			double k = Math.min(1.0, 1.4 / this.getStructure().mass());
			Vec3 look = player.getLookAngle();
			Vec3 dir = new Vec3(look.x, 0.0, look.z);
			dir = dir.lengthSqr() < 1.0E-4 ? Vec3.ZERO : dir.normalize();
			this.setDeltaMovement(this.getDeltaMovement().add(dir.scale(0.45 * k)).add(0.0, 0.2 * k, 0.0));
			this.angularVelocity.add((float) (dir.z * 0.4 * k), (float) ((this.random.nextDouble() - 0.5) * 0.2 * k), (float) (-dir.x * 0.4 * k));
			this.needsSync = true;
			SoundType sound = this.getStructure().primaryState().getSoundType();
			this.playSound(sound.getHitSound(), sound.getVolume(), sound.getPitch());
			return true;
		}
		return false;
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
		BlockStructure s = this.getStructure();
		Vec3 base = this.getDeltaMovement();
		Vec3 pos = this.position();
		Vec3 com = this.getWorldCenterOfMass();
		List<RealisticBlockEntity> pieces = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> cell : s.cells().entrySet()) {
			BlockPos c = cell.getKey();
			BlockState state = cell.getValue();
			Vec3 center = pos.add(c.getX(), c.getY() + 0.5, c.getZ());
			if (state.getBlock().getExplosionResistance() < 0.5F && power > 0.25) {
				level.levelEvent(LEVEL_EVENT_BLOCK_BREAK, BlockPos.containing(center), Block.getId(state));
				continue; // glass, leaves and the like shatter
			}
			Vec3 outward = center.subtract(com);
			outward = outward.lengthSqr() > 1.0E-4 ? outward.normalize() : new Vec3(this.random.nextGaussian(), 0.5, this.random.nextGaussian()).normalize();
			Vec3 velocity = base.add(impulse.scale(1.6 / Math.sqrt(Densities.of(state)))).add(outward.scale(0.3 * power)).add(0.0, 0.2 * power, 0.0);
			RealisticBlockEntity piece = new RealisticBlockEntity(level, pos.x + c.getX(), pos.y + c.getY(), pos.z + c.getZ(), BlockStructure.single(state));
			piece.setDeltaMovement(velocity);
			piece.angularVelocity.set(
				(float) (this.random.nextGaussian() * 0.35 * power),
				(float) (this.random.nextGaussian() * 0.35 * power),
				(float) (this.random.nextGaussian() * 0.35 * power)
			);
			pieces.add(piece);
		}

		if (pieces.size() == 1 && s.size() == 1) {
			RealisticBlockEntity only = pieces.getFirst();
			this.setDeltaMovement(only.getDeltaMovement());
			this.angularVelocity.set(only.angularVelocity);
			this.needsSync = true;
			return;
		}
		pieces.forEach(level::addFreshEntity);
		this.discard();
	}

	private void breakApart(ServerLevel level, boolean drop) {
		Vec3 pos = this.position();
		for (Map.Entry<BlockPos, BlockState> cell : this.getStructure().cells().entrySet()) {
			BlockPos c = cell.getKey();
			BlockPos worldPos = BlockPos.containing(pos.x + c.getX(), pos.y + c.getY() + 0.5, pos.z + c.getZ());
			level.levelEvent(LEVEL_EVENT_BLOCK_BREAK, worldPos, Block.getId(cell.getValue()));
		}
		if (drop) {
			BlockState state = this.getStructure().primaryState();
			int count = this.getStructure().size();
			while (count > 0) {
				int n = Math.min(count, state.getBlock().asItem().getDefaultMaxStackSize());
				this.spawnAtLocation(level, RealisticItems.realisticStackOf(state, n), 0.5F);
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
		return this.isAlive();
	}

	@Override
	public boolean canCollideWith(Entity entity) {
		return (entity instanceof LivingEntity && !entity.isSpectator()) || super.canCollideWith(entity);
	}

	@Override
	protected double getDefaultGravity() {
		return GRAVITY;
	}

	@Override
	protected Entity.MovementEmission getMovementEmission() {
		return Entity.MovementEmission.NONE;
	}

	// ---------------------------------------------------------------- persistence

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.store("Structure", BlockStructure.CODEC, this.getStructure());
		output.store("Orientation", ExtraCodecs.QUATERNIONF, new Quaternionf(this.orientation));
		output.putFloat("SpinX", this.angularVelocity.x);
		output.putFloat("SpinY", this.angularVelocity.y);
		output.putFloat("SpinZ", this.angularVelocity.z);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		input.read("Structure", BlockStructure.CODEC).ifPresent(this::setStructure);
		input.read("Orientation", ExtraCodecs.QUATERNIONF).ifPresent(this.orientation::set);
		this.angularVelocity.set(input.getFloatOr("SpinX", 0.0F), input.getFloatOr("SpinY", 0.0F), input.getFloatOr("SpinZ", 0.0F));
		this.entityData.set(DATA_ORIENTATION, new Quaternionf(this.orientation));
	}
}
