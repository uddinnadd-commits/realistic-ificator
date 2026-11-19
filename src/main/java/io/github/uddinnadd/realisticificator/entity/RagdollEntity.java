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
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
	private static final double MAX_SPEED = 2.5;
	private static final double MAX_SPIN = 1.5;
	private static final Vector3d UP = new Vector3d(0, 1, 0);

	private static final EntityDataAccessor<RagdollInfo> DATA_INFO = SynchedEntityData.defineId(RagdollEntity.class, RagdollInfo.SERIALIZER);
	private static final EntityDataAccessor<List<BodyPose>> DATA_POSES = SynchedEntityData.defineId(RagdollEntity.class, RagdollInfo.POSES_SERIALIZER);

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
	private List<RealisticBlockEntity> nearbyBodies = List.of();

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
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		mob.saveWithoutId(output);
		CompoundTag data = output.buildResult();
		for (String key : List.of("UUID", "Pos", "Motion", "Rotation", "Passengers", "Leash")) {
			data.remove(key);
		}
		RagdollSkeleton.Kind kind = RagdollSkeleton.classify(mob);
		RagdollInfo info = new RagdollInfo(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), data, kind.ordinal(), mob.getBbWidth(), mob.getBbHeight());

		RagdollEntity ragdoll = new RagdollEntity(ModEntities.RAGDOLL, level);
		ragdoll.setInfo(info);
		if (mob.hasCustomName()) {
			ragdoll.setCustomName(mob.getCustomName());
		}
		ragdoll.layOutBones(feet, yaw);
		ragdoll.setOldPos();
		return ragdoll;
	}

	/** Stands the skeleton up in its rest pose with its feet at {@code feet}, facing {@code yaw}. */
	private void layOutBones(Vec3 feet, float yaw) {
		Quaterniond facing = new Quaterniond().rotationY(-Math.toRadians(yaw));
		for (int i = 0; i < this.bones().length; i++) {
			Bone bone = this.bones()[i];
			Vector3d c = facing.transform(RigidMath.vec(this.skeleton().bones.get(i).center()));
			bone.pos.set(feet.x + c.x, feet.y + c.y, feet.z + c.z);
			bone.rot.set(facing);
			bone.linVel.zero();
			bone.angVel.zero();
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
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_INFO.equals(accessor)) {
			this.rebuild(this.entityData.get(DATA_INFO));
			this.clientInitialized = false;
		}
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
			this.layOutBones(this.position(), this.getYRot());
		}
		if (this.collider == null) {
			this.collider = new WorldCollider(this.level());
		}
		this.collider.clear();
		Vec3 oldPos = this.position();
		Quaterniond oldRot = new Quaterniond(this.bones()[RagdollSkeleton.TORSO].rot);
		this.nearbyBodies = this.level().getEntitiesOfClass(RealisticBlockEntity.class, this.getBoundingBox().inflate(1.5), Entity::isAlive);

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
		if (local != null && !local.isSpectator() && EntityContact.moved(oldTorso, oldRot, newTorso, newRot)) {
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
			double vn = bone.velocityAt(c.r).dot(c.normal);
			c.closingSpeed = Math.max(0.0, -vn);
			double fade = Mth.clamp((-vn - 0.06) / 0.12, 0.0, 1.0);
			c.targetVelocity = -RESTITUTION * fade * vn;
		}

		for (int iteration = 0; iteration < SOLVER_ITERATIONS; iteration++) {
			for (JointState js : joints) {
				this.solveJoint(js, invI);
			}
			for (Contact c : contacts) {
				this.solveContact(c, invI[c.bone]);
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
			bone.pos.fma(this.dt, bone.linVel);
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
						WorldCollider.Hit hit = world.penetration(wp);
						if (hit != null) {
							contacts.add(new Contact(b, wp, new Vector3d(hit.normal().getStepX(), hit.normal().getStepY(), hit.normal().getStepZ()), hit.depth(), null, 0));
							continue;
						}
						for (RealisticBlockEntity body : this.nearbyBodies) {
							if (body.getBoundingBox().contains(wp)) {
								Penetration pen = body.penetration(wp);
								if (pen != null) {
									contacts.add(new Contact(b, wp, RigidMath.vec(pen.outwardNormal()), pen.depth(), body, 0));
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
		return contacts;
	}

	/** Shallowest way out of a bone for a world point; the normal is the bone's outward face normal. */
	private @Nullable Penetration bonePenetration(int b, Vec3 world, Vector3d prefer, boolean rejectAmbiguous) {
		Bone bone = this.bones()[b];
		Vec3 l = bone.toLocal(world);
		if (Math.abs(l.x) > bone.half.x || Math.abs(l.y) > bone.half.y || Math.abs(l.z) > bone.half.z) {
			return null;
		}
		double[] depths = {bone.half.x - l.x, bone.half.x + l.x, bone.half.y - l.y, bone.half.y + l.y, bone.half.z - l.z, bone.half.z + l.z};
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
			bones[i].pos.add(corrections[i]);
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

	private void solveContact(Contact c, Matrix3d invI) {
		Bone bone = this.bones()[c.bone];
		Vector3d v = bone.velocityAt(c.r);
		double vn = v.dot(c.normal);
		double newImpulse = Math.max(c.normalImpulse + (c.targetVelocity - vn) / c.normalMass, 0.0);
		double delta = newImpulse - c.normalImpulse;
		c.normalImpulse = newImpulse;
		bone.applyImpulse(new Vector3d(c.normal).mul(delta), c.r, invI);

		v = bone.velocityAt(c.r);
		Vector3d vt = new Vector3d(v).fma(-v.dot(c.normal), c.normal);
		double slide = vt.length();
		if (slide < 1.0E-9) {
			return;
		}
		Vector3d t = new Vector3d(vt).div(slide);
		double tangentMass = 1.0 / bone.mass + RigidMath.angularTerm(invI, c.r, t);
		Vector3d newTangent = new Vector3d(c.tangentImpulse).fma(-slide / tangentMass, t);
		double max = FRICTION * c.normalImpulse;
		if (newTangent.length() > max) {
			newTangent.normalize(max);
		}
		Vector3d tangentDelta = new Vector3d(newTangent).sub(c.tangentImpulse);
		c.tangentImpulse.set(newTangent);
		bone.applyImpulse(tangentDelta, c.r, invI);
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
		if (this.isRemoved() || this.isInvulnerableToBase(source) || source.is(DamageTypeTags.IS_EXPLOSION)) {
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
