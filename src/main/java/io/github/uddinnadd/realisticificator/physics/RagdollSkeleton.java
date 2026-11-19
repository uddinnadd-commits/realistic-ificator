package io.github.uddinnadd.realisticificator.physics;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.phys.Vec3;

/**
 * Ragdoll skeleton templates, sized from the mob's hitbox. Shared by server (simulation) and client (rendering).
 * <p>
 * "Canonical" space: feet at the origin, facing +Z, the way a living entity model is drawn with zero body yaw.
 * Every bone starts unrotated in this space, so joint anchors are simple offsets.
 */
public final class RagdollSkeleton {
	public enum Kind {
		BIPED, QUADRUPED, BLOB;

		public static Kind byId(int id) {
			return values()[Math.floorMod(id, values().length)];
		}
	}

	public static final int TORSO = 0;
	public static final int HEAD = 1;
	/** Biped: right arm. Quadruped: right front leg. */
	public static final int LIMB_A = 2;
	/** Biped: left arm. Quadruped: left front leg. */
	public static final int LIMB_B = 3;
	/** Biped: right leg. Quadruped: right hind leg. */
	public static final int LIMB_C = 4;
	/** Biped: left leg. Quadruped: left hind leg. */
	public static final int LIMB_D = 5;

	public record Bone(Vec3 center, Vec3 half) {
		public double volume() {
			return 8 * this.half.x * this.half.y * this.half.z;
		}
	}

	/** Ball joint at {@code pivot}; {@code limit} caps the child's total rotation away from the parent's, in radians. */
	public record Joint(int parent, int child, Vec3 pivot, double limit) {
	}

	public final Kind kind;
	public final List<Bone> bones;
	public final List<Joint> joints;

	private RagdollSkeleton(Kind kind, List<Bone> bones, List<Joint> joints) {
		this.kind = kind;
		this.bones = bones;
		this.joints = joints;
	}

	public static Kind classify(Entity entity) {
		float w = entity.getBbWidth(), h = entity.getBbHeight();
		if (entity instanceof Slime || h < 0.5F || w > 2.5F) {
			return Kind.BLOB;
		}
		return h > w * 1.4F ? Kind.BIPED : Kind.QUADRUPED;
	}

	public static RagdollSkeleton build(Kind kind, float width, float height) {
		return switch (kind) {
			case BIPED -> biped(height / 2.0);
			case QUADRUPED -> quadruped(width, height);
			case BLOB -> new RagdollSkeleton(kind, List.of(new Bone(new Vec3(0, height / 2.0, 0), new Vec3(width / 2.0, height / 2.0, width / 2.0))), List.of());
		};
	}

	/** Humanoid proportions (the 32-pixel-tall player model), scaled. */
	private static RagdollSkeleton biped(double s) {
		Vec3 limbHalf = new Vec3(0.125 * s, 0.375 * s, 0.125 * s);
		List<Bone> bones = List.of(
			new Bone(new Vec3(0, 1.125 * s, 0), new Vec3(0.25 * s, 0.375 * s, 0.125 * s)),
			new Bone(new Vec3(0, 1.75 * s, 0), new Vec3(0.25 * s, 0.25 * s, 0.25 * s)),
			new Bone(new Vec3(-0.375 * s, 1.125 * s, 0), limbHalf),
			new Bone(new Vec3(0.375 * s, 1.125 * s, 0), limbHalf),
			new Bone(new Vec3(-0.125 * s, 0.375 * s, 0), limbHalf),
			new Bone(new Vec3(0.125 * s, 0.375 * s, 0), limbHalf)
		);
		List<Joint> joints = List.of(
			new Joint(TORSO, HEAD, new Vec3(0, 1.5 * s, 0), 0.9),
			new Joint(TORSO, LIMB_A, new Vec3(-0.375 * s, 1.375 * s, 0), 2.4),
			new Joint(TORSO, LIMB_B, new Vec3(0.375 * s, 1.375 * s, 0), 2.4),
			new Joint(TORSO, LIMB_C, new Vec3(-0.125 * s, 0.75 * s, 0), 1.5),
			new Joint(TORSO, LIMB_D, new Vec3(0.125 * s, 0.75 * s, 0), 1.5)
		);
		return new RagdollSkeleton(Kind.BIPED, bones, joints);
	}

	private static RagdollSkeleton quadruped(double w, double h) {
		double legH = h * 0.4, legW = Math.min(w * 0.3, 0.35), bodyH = h * 0.38, length = Math.max(w * 1.3, h * 0.9);
		double headSize = Math.min(w * 0.7, h * 0.4);
		double legX = w * 0.45 - legW / 2, legZ = length / 2 - legW / 2;
		Vec3 legHalf = new Vec3(legW / 2, legH / 2, legW / 2);
		List<Bone> bones = List.of(
			new Bone(new Vec3(0, legH + bodyH / 2, 0), new Vec3(w * 0.45, bodyH / 2, length / 2)),
			new Bone(new Vec3(0, legH + bodyH * 0.8 + headSize * 0.2, length / 2 + headSize * 0.45), new Vec3(headSize / 2, headSize / 2, headSize / 2)),
			new Bone(new Vec3(-legX, legH / 2, legZ), legHalf),
			new Bone(new Vec3(legX, legH / 2, legZ), legHalf),
			new Bone(new Vec3(-legX, legH / 2, -legZ), legHalf),
			new Bone(new Vec3(legX, legH / 2, -legZ), legHalf)
		);
		List<Joint> joints = List.of(
			new Joint(TORSO, HEAD, new Vec3(0, legH + bodyH * 0.7, length / 2), 0.8),
			new Joint(TORSO, LIMB_A, new Vec3(-legX, legH, legZ), 1.0),
			new Joint(TORSO, LIMB_B, new Vec3(legX, legH, legZ), 1.0),
			new Joint(TORSO, LIMB_C, new Vec3(-legX, legH, -legZ), 1.0),
			new Joint(TORSO, LIMB_D, new Vec3(legX, legH, -legZ), 1.0)
		);
		return new RagdollSkeleton(Kind.QUADRUPED, bones, joints);
	}
}
