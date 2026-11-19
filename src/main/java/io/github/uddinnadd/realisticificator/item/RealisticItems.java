package io.github.uddinnadd.realisticificator.item;

import io.github.uddinnadd.realisticificator.registry.ModComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Helpers for converting block item stacks into their "realistic" form. */
public final class RealisticItems {
	private RealisticItems() {
	}

	public static boolean isRealistic(ItemStack stack) {
		return !stack.isEmpty() && stack.has(ModComponents.REALISTIC);
	}

	/** Solid, single-block, model-rendered blocks (what a rigid body can represent), or spawn eggs (ragdoll corpses). */
	public static boolean canBeMadeRealistic(ItemStack stack) {
		if (stack.isEmpty() || isRealistic(stack)) {
			return false;
		}
		if (stack.getItem() instanceof SpawnEggItem) {
			return SpawnEggItem.getType(stack) != null;
		}
		return stack.getItem() instanceof BlockItem blockItem && canBeRealistic(blockItem.getBlock().defaultBlockState());
	}

	public static boolean canBeRealistic(BlockState state) {
		Block block = state.getBlock();
		return !(block instanceof EntityBlock)
			&& state.getRenderShape() == RenderShape.MODEL
			&& !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
			&& !state.hasProperty(BlockStateProperties.BED_PART)
			&& !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	public static ItemStack makeRealistic(ItemStack input) {
		ItemStack result = input.copy();
		Component baseName = input.getItem().getName(input);
		result.set(ModComponents.REALISTIC, Unit.INSTANCE);
		result.set(DataComponents.ITEM_NAME, Component.translatable("item.realistic_ificator.realistic_prefix", baseName));
		result.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		String lore = input.getItem() instanceof SpawnEggItem ? "tooltip.realistic_ificator.realistic_egg" : "tooltip.realistic_ificator.realistic";
		result.set(DataComponents.LORE, new ItemLore(List.of(
			Component.translatable(lore + ".1").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.AQUA)),
			Component.translatable(lore + ".2").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY))
		)));
		return result;
	}

	public static ItemStack realisticStackOf(BlockState state, int count) {
		return makeRealistic(new ItemStack(state.getBlock(), count));
	}
}
