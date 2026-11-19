package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.item.RealisticItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Using a realistic item on the world: blocks become physics bodies, spawn eggs become ragdoll corpses. */
public final class RealisticPlacement {
	private RealisticPlacement() {
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isSpectator() || !RealisticItems.isRealistic(stack) || !(stack.getItem() instanceof BlockItem || stack.getItem() instanceof SpawnEggItem)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		BlockPos clicked = hit.getBlockPos();
		BlockPos target = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
		if (!level.mayInteract(player, target) || !player.mayUseItemAt(target, hit.getDirection(), stack)) {
			return InteractionResult.FAIL;
		}
		if (stack.getItem() instanceof SpawnEggItem) {
			return spawnCorpse((ServerLevel) level, player, stack, target);
		}

		BlockItem blockItem = (BlockItem) stack.getItem();
		AABB box = new AABB(target).deflate(1.0E-3);
		if (!level.getBlockState(target).canBeReplaced()
			|| !level.noCollision(box)
			|| !level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e.canBeCollidedWith(null))).isEmpty()) {
			return InteractionResult.FAIL;
		}

		BlockState state = RealisticBlockEntity.placementState(blockItem.getBlock(), hit.getDirection());
		BlockStructure single = BlockStructure.single(state);
		RealisticBlockEntity entity = new RealisticBlockEntity(level, Vec3.atCenterOf(target).add(single.centerOfMass()), single);
		level.addFreshEntity(entity);

		SoundType sound = state.getSoundType();
		level.playSound(null, target, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult spawnCorpse(ServerLevel level, Player player, ItemStack stack, BlockPos target) {
		EntityType<?> type = SpawnEggItem.getType(stack);
		if (type == null) {
			return InteractionResult.FAIL;
		}
		LivingEntity mob = RagdollEntity.createMob(level, type, target);
		if (mob == null) {
			return InteractionResult.FAIL;
		}
		// Face the player, like a mob spawned from an egg would be looked at.
		float yaw = player.getYRot() + 180.0F;
		RagdollEntity corpse = RagdollEntity.of(level, mob, Vec3.atBottomCenterOf(target).add(0, 0.01, 0), yaw);
		mob.discard();
		level.addFreshEntity(corpse);
		level.playSound(null, target, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.NEUTRAL, 1.0F, 0.8F);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
