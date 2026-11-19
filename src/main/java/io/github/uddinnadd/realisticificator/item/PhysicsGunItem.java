package io.github.uddinnadd.realisticificator.item;

import io.github.uddinnadd.realisticificator.physics.PhysicsObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Gmod-style physics gun. Hold use on a realistic block or corpse to pick it up; it follows your crosshair and keeps
 * whatever velocity you swing it with when you let go. Release while sneaking to freeze it in mid-air. While holding,
 * sneak and walk forward/back to push it away or pull it closer.
 */
public class PhysicsGunItem extends Item {
	public static final double RANGE = 32.0;
	private static final DustParticleOptions BEAM = new DustParticleOptions(0x3FD7FF, 0.7F);

	private record Grab(int entityId, int part, Vec3 local, double distance) {
		Grab withDistance(double d) {
			return new Grab(this.entityId, this.part, this.local, d);
		}
	}

	private static final Map<UUID, Grab> GRABS = new ConcurrentHashMap<>();

	public PhysicsGunItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand);
		if (player instanceof ServerPlayer serverPlayer) {
			GRABS.remove(serverPlayer.getUUID());
			tryGrab(serverPlayer);
		}
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	@Override
	public void onUseTick(Level level, LivingEntity user, ItemStack stack, int ticksRemaining) {
		if (!(user instanceof ServerPlayer player) || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		Grab grab = GRABS.get(player.getUUID());
		if (grab == null) {
			grab = tryGrab(player); // sweep the beam onto something to catch it
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		if (grab == null) {
			if (player.tickCount % 2 == 0) {
				drawBeam(serverLevel, eye, eye.add(look.scale(6)));
			}
			return;
		}
		Entity entity = level.getEntity(grab.entityId());
		if (!(entity instanceof PhysicsObject target) || !entity.isAlive()) {
			GRABS.remove(player.getUUID());
			return;
		}

		Input input = player.getLastClientInput();
		if (input.shift()) {
			double change = (input.forward() ? 0.25 : 0) - (input.backward() ? 0.25 : 0);
			if (change != 0) {
				grab = grab.withDistance(Math.clamp(grab.distance() + change, 1.5, RANGE));
				GRABS.put(player.getUUID(), grab);
			}
		}

		Vec3 point = target.partToWorld(grab.part(), grab.local());
		Vec3 aim = eye.add(look.scale(grab.distance()));
		Vec3 desired = aim.subtract(point).scale(0.45);
		if (desired.length() > 2.5) {
			desired = desired.normalize().scale(2.5);
		}
		target.markHeld();
		target.drivePoint(grab.part(), point, desired, 0.9);
		target.dampPartSpin(grab.part(), 0.85);
		if (player.tickCount % 2 == 0) {
			drawBeam(serverLevel, eye.add(0, -0.25, 0), point);
		}
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remainingTime) {
		if (user instanceof ServerPlayer player) {
			Grab grab = GRABS.remove(player.getUUID());
			if (grab != null && level.getEntity(grab.entityId()) instanceof PhysicsObject target) {
				if (player.isShiftKeyDown()) {
					target.setFrozen(true);
					level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.5F, 1.8F);
				}
			}
		}
		return true;
	}

	private static @Nullable Grab tryGrab(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(RANGE));
		AABB area = player.getBoundingBox().expandTowards(player.getLookAngle().scale(RANGE)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, area, e -> e instanceof PhysicsObject && e.isAlive(), RANGE * RANGE);
		if (hit == null || !(hit.getEntity() instanceof PhysicsObject target)) {
			return null;
		}
		PhysicsObject.PartHit part = target.raycastPart(eye, end);
		if (part == null) {
			return null;
		}
		Grab grab = new Grab(hit.getEntity().getId(), part.part(), target.worldToPart(part.part(), part.point()), eye.distanceTo(part.point()));
		GRABS.put(player.getUUID(), grab);
		target.markHeld();
		player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.4F, 2.0F);
		return grab;
	}

	private static void drawBeam(ServerLevel level, Vec3 from, Vec3 to) {
		Vec3 step = to.subtract(from);
		int points = (int) Math.min(24, Math.max(4, step.length() * 2));
		for (int i = 1; i <= points; i++) {
			Vec3 p = from.add(step.scale(i / (double) points));
			level.sendParticles(BEAM, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
	}
}
