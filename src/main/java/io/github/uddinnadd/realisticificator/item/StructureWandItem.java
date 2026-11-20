package io.github.uddinnadd.realisticificator.item;

import io.github.uddinnadd.realisticificator.entity.AreaConversion;
import io.github.uddinnadd.realisticificator.registry.ModComponents;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Right-click two blocks to mark opposite corners of an area, then sneak and right-click to turn everything in it
 * realistic. Each connected group of blocks becomes its own physics body.
 */
public class StructureWandItem extends Item {
	private static final DustParticleOptions FIRST_CORNER = new DustParticleOptions(0x3FD7FF, 1.0F);
	private static final DustParticleOptions SECOND_CORNER = new DustParticleOptions(0xFF9F3F, 1.0F);
	private static final DustParticleOptions OUTLINE = new DustParticleOptions(0x9FFFEF, 0.8F);

	public StructureWandItem(Item.Properties properties) {
		super(properties);
	}

	private static WandSelection selection(ItemStack stack) {
		return stack.getOrDefault(ModComponents.WAND_SELECTION, WandSelection.EMPTY);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		if (player.isSecondaryUseActive()) {
			return this.convert(context.getLevel(), player, context.getItemInHand());
		}
		if (context.getLevel().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		ItemStack stack = context.getItemInHand();
		BlockPos pos = context.getClickedPos().immutable();
		WandSelection current = selection(stack);
		WandSelection next = current.first().isEmpty() || current.isComplete()
			? new WandSelection(Optional.of(pos), Optional.empty())
			: new WandSelection(current.first(), Optional.of(pos));
		stack.set(ModComponents.WAND_SELECTION, next);

		if (next.isComplete()) {
			BoundingBox box = next.box().orElseThrow();
			player.sendOverlayMessage(Component.translatable("message.realistic_ificator.wand.second", pos.toShortString(),
				box.getXSpan(), box.getYSpan(), box.getZSpan()).withStyle(ChatFormatting.GOLD));
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.wand.save_hint").withStyle(ChatFormatting.DARK_AQUA));
		} else {
			player.sendOverlayMessage(Component.translatable("message.realistic_ificator.wand.first", pos.toShortString()).withStyle(ChatFormatting.AQUA));
		}
		context.getLevel().playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, next.isComplete() ? 1.4F : 1.0F);
		if (player instanceof ServerPlayer serverPlayer) {
			drawSelection((ServerLevel) context.getLevel(), serverPlayer, next);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive()) {
			return this.convert(level, player, player.getItemInHand(hand));
		}
		return InteractionResult.PASS;
	}

	private InteractionResult convert(Level level, Player player, ItemStack stack) {
		WandSelection selection = selection(stack);
		if (!selection.isComplete()) {
			if (!level.isClientSide()) {
				player.sendOverlayMessage(Component.translatable("message.realistic_ificator.wand.incomplete").withStyle(ChatFormatting.RED));
			}
			return InteractionResult.FAIL;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		BoundingBox box = selection.box().orElseThrow();
		if (!player.mayBuild() || !level.mayInteract(player, new BlockPos(box.minX(), box.minY(), box.minZ()))
			|| !level.mayInteract(player, new BlockPos(box.maxX(), box.maxY(), box.maxZ()))) {
			return InteractionResult.FAIL;
		}
		AreaConversion.Result result = AreaConversion.convert((ServerLevel) level, box);
		switch (result) {
			case AreaConversion.Converted converted -> {
				stack.remove(ModComponents.WAND_SELECTION);
				player.sendOverlayMessage(Component.translatable("message.realistic_ificator.wand.done", converted.blocks(), converted.bodies()).withStyle(ChatFormatting.GREEN));
				level.playSound(null, player.blockPosition(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.2F);
				io.github.uddinnadd.realisticificator.command.StructureSaves.sparkleBox((ServerLevel) level, box, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK);
				if (player instanceof ServerPlayer serverPlayer) {
					io.github.uddinnadd.realisticificator.registry.ModAdvancements.award(serverPlayer, io.github.uddinnadd.realisticificator.registry.ModAdvancements.CONVERTED);
				}
			}
			case AreaConversion.Nothing nothing -> player.sendOverlayMessage(Component.translatable("message.realistic_ificator.wand.nothing").withStyle(ChatFormatting.RED));
			case AreaConversion.TooBig tooBig -> player.sendOverlayMessage(Component.translatable("message.realistic_ificator.wand.too_big", tooBig.blocks(), tooBig.limit()).withStyle(ChatFormatting.RED));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
		if (owner instanceof ServerPlayer player && (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) && player.tickCount % 10 == 0) {
			drawSelection(level, player, selection(stack));
		}
	}

	/** Particles only the holder can see: corner markers and the outline of the selected box. */
	private static void drawSelection(ServerLevel level, ServerPlayer player, WandSelection selection) {
		selection.first().ifPresent(p -> marker(level, player, p, FIRST_CORNER));
		selection.second().ifPresent(p -> marker(level, player, p, SECOND_CORNER));
		selection.box().ifPresent(box -> {
			double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX() + 1, y1 = box.maxY() + 1, z1 = box.maxZ() + 1;
			double[][] edges = {
				{x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
				{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
				{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}
			};
			int budget = 600;
			for (double[] e : edges) {
				double length = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
				int points = (int) Math.min(budget / 12, Math.max(1, length * 2));
				for (int i = 0; i <= points; i++) {
					double t = i / (double) points;
					level.sendParticles(player, OUTLINE, true, false, e[0] + (e[3] - e[0]) * t, e[1] + (e[4] - e[1]) * t, e[2] + (e[5] - e[2]) * t, 1, 0, 0, 0, 0);
				}
			}
		});
	}

	private static void marker(ServerLevel level, ServerPlayer player, BlockPos pos, DustParticleOptions color) {
		for (int i = 0; i < 8; i++) {
			level.sendParticles(player, color, true, false, pos.getX() + ((i & 1) == 0 ? 0 : 1), pos.getY() + ((i & 2) == 0 ? 0 : 1), pos.getZ() + ((i & 4) == 0 ? 0 : 1), 1, 0, 0, 0, 0);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		WandSelection selection = selection(stack);
		selection.first().ifPresent(p -> builder.accept(Component.translatable("tooltip.realistic_ificator.wand.first", p.toShortString()).withStyle(ChatFormatting.AQUA)));
		selection.second().ifPresent(p -> builder.accept(Component.translatable("tooltip.realistic_ificator.wand.second", p.toShortString()).withStyle(ChatFormatting.GOLD)));
		builder.accept(Component.translatable("tooltip.realistic_ificator.wand.help.1").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.translatable("tooltip.realistic_ificator.wand.help.2").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.translatable("tooltip.realistic_ificator.wand.help.3").withStyle(ChatFormatting.DARK_AQUA));
	}
}
