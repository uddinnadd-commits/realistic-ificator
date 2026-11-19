package io.github.uddinnadd.realisticificator.physics;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** How simulated bodies treat the entities standing on and bumping into them. */
public final class EntityContact {
	private EntityContact() {
	}

	/** True when the entity's feet rest on top of one of the boxes. */
	public static boolean isStandingOn(Entity e, List<AABB> boxes) {
		AABB eb = e.getBoundingBox();
		AABB probe = new AABB(eb.minX + 0.02, eb.minY - 0.08, eb.minZ + 0.02, eb.maxX - 0.02, eb.minY + 0.02, eb.maxZ - 0.02);
		for (AABB box : boxes) {
			if (box.intersects(probe) && box.maxY >= eb.minY - 0.08 && box.maxY <= eb.minY + 0.05) {
				return true;
			}
		}
		return false;
	}

	/** Moves an entity rigidly with a body between two poses, turning it with the body too. */
	public static void carry(Entity e, Vec3 oldPos, Quaterniond oldRot, Vec3 newPos, Quaterniond newRot) {
		Vec3 p = e.position();
		Vector3d rel = oldRot.transformInverse(new Vector3d(p.x - oldPos.x, p.y - oldPos.y, p.z - oldPos.z));
		Vector3d np = newRot.transform(rel).add(newPos.x, newPos.y, newPos.z);
		double moved = np.distanceSquared(p.x, p.y, p.z);
		if (moved < 1.0E-8 || moved > 4.0) {
			return;
		}
		e.setPos(np.x, np.y, np.z);
		Vector3d forward = new Quaterniond(newRot).mul(new Quaterniond(oldRot).conjugate()).transform(new Vector3d(1, 0, 0));
		float yawDelta = (float) Math.toDegrees(Math.atan2(forward.z, forward.x));
		if (Math.abs(yawDelta) > 0.01F && Math.abs(yawDelta) < 45) {
			e.setYRot(e.getYRot() + yawDelta);
			if (e instanceof LivingEntity living) {
				living.setYHeadRot(living.getYHeadRot() + yawDelta);
				living.yBodyRot += yawDelta;
			}
		}
	}

	/**
	 * Shoves an entity out of a body that moved into it, along the shortest way out. Only call this when the body
	 * actually moved: a still body can't be inside anything that vanilla collision didn't already stop, and nudging
	 * players near stairs and slabs for nothing is what made their position jump.
	 */
	public static void pushOut(Entity e, List<AABB> boxes) {
		for (int i = 0; i < 4; i++) {
			AABB eb = e.getBoundingBox();
			AABB core = eb.deflate(0.01);
			AABB hit = null;
			for (AABB box : boxes) {
				if (box.intersects(core)) {
					hit = box;
					break;
				}
			}
			if (hit == null) {
				return;
			}
			double up = hit.maxY - eb.minY;
			double east = hit.maxX - eb.minX, west = eb.maxX - hit.minX, south = hit.maxZ - eb.minZ, north = eb.maxZ - hit.minZ;
			double min = Math.min(Math.min(up - 0.05, east), Math.min(Math.min(west, south), north));
			if (min > 0.9) {
				return; // deeply inside: leave it to vanilla rather than teleporting
			}
			Vec3 move = min == up - 0.05 ? new Vec3(0, up + 1.0E-3, 0)
				: min == east ? new Vec3(east + 1.0E-3, 0, 0)
				: min == west ? new Vec3(-west - 1.0E-3, 0, 0)
				: min == south ? new Vec3(0, 0, south + 1.0E-3)
				: new Vec3(0, 0, -north - 1.0E-3);
			e.setPos(e.position().add(move));
		}
	}

	public static boolean moved(Vec3 oldPos, Quaterniond oldRot, Vec3 newPos, Quaterniond newRot) {
		return oldPos.distanceToSqr(newPos) > 1.0E-8 || Math.abs(Math.abs(oldRot.dot(newRot)) - 1.0) > 1.0E-9;
	}
}
