package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Players and mobs that get hit or blown up fall over as ragdolls, flung by the hit, and get back up afterwards. */
public final class KnockDowns {
	public static final GameRule<Boolean> RAGDOLL_PLAYERS = GameRuleBuilder.forBoolean(true)
		.category(GameRuleCategory.PLAYER)
		.buildAndRegister(RealisticIficator.id("ragdoll_players"));
	public static final GameRule<Boolean> RAGDOLL_MOBS = GameRuleBuilder.forBoolean(true)
		.category(GameRuleCategory.MOBS)
		.buildAndRegister(RealisticIficator.id("ragdoll_mobs"));
	/** Smallest attack (in half-hearts) that knocks someone down. Explosions always do. */
	public static final GameRule<Integer> RAGDOLL_MIN_DAMAGE = GameRuleBuilder.forInteger(2)
		.category(GameRuleCategory.MOBS)
		.buildAndRegister(RealisticIficator.id("ragdoll_min_damage"));
	/** How long bodies of things killed by attacks or explosions stay, in seconds. 0 turns death bodies off. */
	public static final GameRule<Integer> CORPSE_SECONDS = GameRuleBuilder.forInteger(60)
		.category(GameRuleCategory.MOBS)
		.buildAndRegister(RealisticIficator.id("corpse_seconds"));

	private KnockDowns() {
	}

	public static void init() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(KnockDowns::rememberMotion);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(KnockDowns::afterDamage);
		ServerLivingEntityEvents.ALLOW_DEATH.register(KnockDowns::rememberLethalDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register(KnockDowns::afterDeath);
	}

	/**
	 * Motion from just before the hit. Vanilla applies attack knockback inside the damage call, before the damage
	 * event, and the fling already stands in for it, so the body must not inherit it as well.
	 */
	private static final Map<LivingEntity, Vec3> PRE_HIT_MOTION = new WeakHashMap<>();

	private static boolean rememberMotion(LivingEntity entity, DamageSource source, float amount) {
		PRE_HIT_MOTION.put(entity, entity.getDeltaMovement());
		return true;
	}

	private static Vec3 preHitMotion(LivingEntity entity) {
		Vec3 motion = PRE_HIT_MOTION.remove(entity);
		return motion != null ? motion : entity.getDeltaMovement();
	}

	private static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		Vec3 motion = preHitMotion(entity);
		if (blocked || damageTaken <= 0 || entity.isDeadOrDying() || !(entity.level() instanceof ServerLevel level) || !canRagdoll(level, entity)) {
			return;
		}
		boolean explosion = source.is(DamageTypeTags.IS_EXPLOSION);
		if (!explosion && (source.getEntity() == null || damageTaken < level.getGameRules().get(RAGDOLL_MIN_DAMAGE))) {
			return;
		}
		if (explosion && entity.getVehicle() instanceof RagdollEntity) {
			return; // the ragdoll itself is caught in the blast and gets pushed by it
		}
		RagdollEntity.knockDown(level, entity, motion, fling(entity, source, damageTaken), hitFrom(source));
	}

	private static void afterDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level)) {
			return;
		}
		int lifetime = level.getGameRules().get(CORPSE_SECONDS) * 20;
		boolean violent = source.is(DamageTypeTags.IS_EXPLOSION) || source.getEntity() != null;
		boolean knockedDown = entity.getVehicle() instanceof RagdollEntity;
		if (!knockedDown && (!violent || !canRagdoll(level, entity))) {
			return;
		}
		// Whatever the last hit was, it was big enough to kill: fling at least as hard as a few hearts would.
		float damage = Math.max(6.0F, LAST_LETHAL_DAMAGE.getOrDefault(entity, 0.0F));
		LAST_LETHAL_DAMAGE.remove(entity);
		Vec3 fling = violent ? fling(entity, source, damage) : null;
		RagdollEntity.onDeath(level, entity, preHitMotion(entity), fling, hitFrom(source), Math.max(lifetime, knockedDown ? 20 : 0));
	}

	private static final Map<LivingEntity, Float> LAST_LETHAL_DAMAGE = new WeakHashMap<>();

	private static boolean rememberLethalDamage(LivingEntity entity, DamageSource source, float amount) {
		LAST_LETHAL_DAMAGE.put(entity, amount);
		return true;
	}

	private static boolean canRagdoll(ServerLevel level, LivingEntity entity) {
		if (entity instanceof ArmorStand || entity instanceof EnderDragon || entity instanceof WitherBoss || entity.isBaby()) {
			return false;
		}
		if (entity.getBbWidth() > 4.0F || entity.getBbHeight() > 4.0F || entity.isSpectator()) {
			return false;
		}
		if (entity instanceof Player player) {
			return !player.isCreative() && level.getGameRules().get(RAGDOLL_PLAYERS);
		}
		return level.getGameRules().get(RAGDOLL_MOBS);
	}

	private static @Nullable Vec3 hitFrom(DamageSource source) {
		return source.getSourcePosition();
	}

	/** Fling velocity: away from the attacker or blast, harder the more damage it did, softened by knockback resistance. */
	private static Vec3 fling(LivingEntity entity, DamageSource source, float damage) {
		boolean explosion = source.is(DamageTypeTags.IS_EXPLOSION);
		Vec3 from = source.getSourcePosition();
		Vec3 away;
		if (from != null) {
			away = entity.position().subtract(from).multiply(1, 0, 1);
		} else if (source.getEntity() instanceof Entity attacker) {
			away = attacker.getLookAngle().multiply(1, 0, 1);
		} else {
			away = Vec3.ZERO;
		}
		away = away.lengthSqr() > 1.0E-4 ? away.normalize() : Vec3.ZERO;
		double resistance = entity.getAttributeValue(explosion ? Attributes.EXPLOSION_KNOCKBACK_RESISTANCE : Attributes.KNOCKBACK_RESISTANCE);
		double scale = Math.max(0.0, 1.0 - resistance);
		// Ragdolls have little air drag, so these stay small: a few blocks for a punch, ~15 for point-blank TNT.
		double d = Math.min(damage, 30.0F);
		double horizontal = explosion ? 0.25 + 0.015 * d : 0.1 + 0.025 * d;
		double up = explosion ? 0.2 + 0.012 * d : 0.1 + 0.01 * d;
		Vec3 v = away.scale(Math.min(horizontal, 0.75)).add(0, Math.min(up, 0.56), 0).scale(scale);
		return v.length() > 1.0 ? v.normalize() : v;
	}
}
