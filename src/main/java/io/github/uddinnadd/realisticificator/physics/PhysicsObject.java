package io.github.uddinnadd.realisticificator.physics;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Something made of one or more rigid parts that the physics gun can hold and other simulations can bump into.
 * Parts are indexed; single-body objects only have part 0.
 */
public interface PhysicsObject {
	record PartHit(int part, Vec3 point) {
	}

	/** Penetration of a world point into this object: depth, world-space direction to push the point out, and the part hit. */
	record Penetration(int part, double depth, Vec3 outwardNormal) {
	}

	@Nullable PartHit raycastPart(Vec3 from, Vec3 to);

	Vec3 partToWorld(int part, Vec3 local);

	Vec3 worldToPart(int part, Vec3 world);

	Vec3 partVelocityAt(int part, Vec3 world);

	void applyPartImpulse(int part, Vec3 impulse, Vec3 world);

	/** Drives the velocity of a world point on a part towards {@code desired}, accounting for the part's mass and inertia. */
	void drivePoint(int part, Vec3 world, Vec3 desired, double strength);

	void dampPartSpin(int part, double factor);

	double partMass(int part);

	/** Called every tick while held so the object stays awake and ignores freezing. */
	void markHeld();

	void setFrozen(boolean frozen);

	boolean isFrozen();

	@Nullable Penetration penetration(Vec3 world);
}
