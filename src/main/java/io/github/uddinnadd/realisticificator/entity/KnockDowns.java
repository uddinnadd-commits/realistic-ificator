package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
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
	/** How hard hits and explosions throw bodies, in percent. */
	public static final GameRule<Integer> FLING_STRENGTH = GameRuleBuilder.forInteger(100)
		.category(GameRuleCategory.MOBS)
		.buildAndRegister(RealisticIficator.id("fling_strength"));
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
		// Vanilla has just applied its knockback, which includes sprinting, Knockback enchantments and maces: hits
		// that knock back harder than a plain punch launch further.
		double knockback = entity.getDeltaMovement().subtract(motion).horizontalDistance();
		double boost = explosion ? 1.0 : Mth.clamp(knockback / 0.4, 1.0, 3.0);
		RagdollEntity.knockDown(level, entity, motion, fling(level, entity, source, damageTaken).multiply(boost, 1.0 + (boost - 1.0) * 0.3, boost), hitFrom(source));
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
		Vec3 fling = violent ? fling(level, entity, source, damage) : null;
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

	/**
	 * Fling velocity: away from the attacker or blast and up into a big comedy arc, harder the more damage it did,
	 * softened by knockback resistance and scaled by the fling_strength rule.
	 */
	public static Vec3 fling(ServerLevel level, LivingEntity entity, DamageSource source, float damage) {
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
		double scale = Math.max(0.0, 1.0 - resistance) * level.getGameRules().get(FLING_STRENGTH) / 100.0;
		// Ragdolls have little air drag: an 8-damage hit sends someone ~15-20 blocks, nearby TNT 15-50.
		double d = Math.min(damage, 30.0F);
		double horizontal = explosion ? 0.4 + 0.03 * d : 0.3 + 0.05 * d;
		double up = explosion ? 0.4 + 0.02 * d : 0.3 + 0.02 * d;
		return away.scale(Math.min(horizontal, 1.2)).add(0, Math.min(up, 0.9), 0).scale(scale);
	}
}
