package io.github.uddinnadd.realisticificator.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Vector3d;

/** Small rigid-body helpers shared by block bodies and ragdoll bones. */
public final class RigidMath {
	private RigidMath() {
	}

	public static Vector3d vec(Vec3 v) {
		return new Vector3d(v.x, v.y, v.z);
	}

	public static Vec3 vec(Vector3d v) {
		return new Vec3(v.x, v.y, v.z);
	}

	/** n . ((I^-1 (r x n)) x r): the angular part of the effective mass along n. */
	public static double angularTerm(Matrix3d invI, Vector3d r, Vector3d n) {
		Vector3d rn = new Vector3d(r).cross(n);
		invI.transform(rn);
		return rn.cross(r).dot(n);
	}

	/** 3x3 matrix K with K * impulse = velocity change at offset r, for a body with inverse mass and world inverse inertia. */
	public static Matrix3d pointMassMatrix(double invMass, Matrix3d invI, Vector3d r) {
		Matrix3d skew = skew(r);
		Matrix3d skewT = new Matrix3d(skew).transpose();
		Matrix3d k = new Matrix3d(skew).mul(invI).mul(skewT);
		k.m00 += invMass;
		k.m11 += invMass;
		k.m22 += invMass;
		return k;
	}

	public static Matrix3d skew(Vector3d r) {
		// JOML is column-major: mXY is column X, row Y.
		return new Matrix3d(
			0, r.z, -r.y,
			-r.z, 0, r.x,
			r.y, -r.x, 0
		);
	}
}
