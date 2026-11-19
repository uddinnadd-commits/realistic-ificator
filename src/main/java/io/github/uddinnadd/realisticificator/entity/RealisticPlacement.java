package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.item.RealisticItems;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/** Placing a realistic block item against the world spawns a physics body instead of a normal block. */
public final class RealisticPlacement {
	private RealisticPlacement() {
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isSpectator() || !RealisticItems.isRealistic(stack) || !(stack.getItem() instanceof BlockItem blockItem)) {
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
		AABB box = new AABB(target).deflate(1.0E-3);
		if (!level.getBlockState(target).canBeReplaced()
			|| !level.noCollision(box)
			|| !level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e.canBeCollidedWith(null))).isEmpty()) {
			return InteractionResult.FAIL;
		}

		BlockState state = RealisticBlockEntity.placementState(blockItem.getBlock(), hit.getDirection());
		RealisticBlockEntity entity = new RealisticBlockEntity(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, BlockStructure.single(state));
		level.addFreshEntity(entity);

		SoundType sound = state.getSoundType();
		level.playSound(null, target, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
