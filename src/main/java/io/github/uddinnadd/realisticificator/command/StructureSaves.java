package io.github.uddinnadd.realisticificator.command;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.entity.AreaConversion;
import io.github.uddinnadd.realisticificator.item.StructureWandItem;
import io.github.uddinnadd.realisticificator.item.WandSelection;
import io.github.uddinnadd.realisticificator.registry.ModAdvancements;
import io.github.uddinnadd.realisticificator.registry.ModComponents;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Ten structure save slots per player. {@code /save <slot>} copies the Structure Wand selection (blocks, block contents
 * and entities); {@code /load <slot>} pastes it where the player is looking, turned to face the way they face.
 * Saves are stored as structure files in the world folder, so they survive restarts.
 */
public final class StructureSaves {
	public static final int SLOTS = 10;
	public static final int MAX_VOLUME = 128 * 128 * 64;
	private static final int REACH = 64;

	/** What the last paste replaced, so it can be undone. */
	private record Undo(ServerLevel level, BlockPos min, StructureTemplate before, Set<UUID> spawned) {
	}

	private static final Map<UUID, Undo> UNDO = new ConcurrentHashMap<>();

	private StructureSaves() {
	}

	private static Identifier slotId(Player player, int slot) {
		return RealisticIficator.id("saves/" + player.getUUID() + "/slot_" + slot);
	}

	/** The selection of the wand in hand, or of any wand in the inventory with a full selection. */
	private static @Nullable BoundingBox selection(Player player) {
		List<ItemStack> stacks = new ArrayList<>();
		stacks.add(player.getMainHandItem());
		stacks.add(player.getOffhandItem());
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			stacks.add(player.getInventory().getItem(i));
		}
		for (ItemStack stack : stacks) {
			if (stack.getItem() instanceof StructureWandItem) {
				WandSelection selection = stack.getOrDefault(ModComponents.WAND_SELECTION, WandSelection.EMPTY);
				if (selection.isComplete()) {
					return selection.box().orElseThrow();
				}
			}
		}
		return null;
	}

	public static boolean save(ServerPlayer player, int slot) {
		BoundingBox box = selection(player);
		if (box == null) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.save.no_selection").withStyle(ChatFormatting.RED));
			return false;
		}
		long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
		if (volume > MAX_VOLUME) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.save.too_big", volume, MAX_VOLUME).withStyle(ChatFormatting.RED));
			return false;
		}
		ServerLevel level = player.level();
		StructureTemplateManager manager = level.getStructureTemplateManager();
		Identifier id = slotId(player, slot);
		StructureTemplate template = manager.getOrCreate(id);
		BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
		template.fillFromWorld(level, min, new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan()), true, List.of(Blocks.STRUCTURE_VOID));
		// The facing at save time lets /load turn the build to match the way the player faces then.
		template.setAuthor(player.getName().getString() + ";" + player.getDirection().get2DDataValue());
		if (!manager.save(id)) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.save.failed").withStyle(ChatFormatting.RED));
			return false;
		}
		int blocks = 0;
		for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (!level.getBlockState(p).isAir()) {
				blocks++;
			}
		}
		player.sendSystemMessage(Component.translatable("message.realistic_ificator.save.done",
				Component.literal("#" + slot).withStyle(ChatFormatting.AQUA), blocks, size(box))
			.withStyle(ChatFormatting.GREEN)
			.append(" ")
			.append(button("message.realistic_ificator.saves.load", "/load " + slot, ChatFormatting.GOLD)));
		level.playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 1.0F, 1.0F);
		sparkleBox(level, box, ParticleTypes.WAX_ON);
		ModAdvancements.award(player, ModAdvancements.SAVED);
		return true;
	}

	public static boolean load(ServerPlayer player, int slot, boolean realistic) {
		if (!player.isCreative() && !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.load.not_allowed").withStyle(ChatFormatting.RED));
			return false;
		}
		ServerLevel level = player.level();
		Optional<StructureTemplate> found = level.getStructureTemplateManager().get(slotId(player, slot));
		if (found.isEmpty() || found.get().getSize().equals(Vec3i.ZERO)) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.load.empty", slot, slot).withStyle(ChatFormatting.RED));
			return false;
		}
		StructureTemplate template = found.get();
		Direction facing = player.getDirection();
		Rotation rotation = Rotation.values()[Math.floorMod(facing.get2DDataValue() - savedFacing(template).get2DDataValue(), 4)];
		StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(false);
		Vec3i size = template.getSize(rotation);
		BlockPos min = targetMin(player, facing, size);
		BoundingBox atZero = template.getBoundingBox(settings, BlockPos.ZERO);
		BlockPos pos = min.offset(-atZero.minX(), -atZero.minY(), -atZero.minZ());
		BoundingBox box = template.getBoundingBox(settings, pos);
		if (!level.isInWorldBounds(new BlockPos(box.minX(), box.minY(), box.minZ())) || !level.isInWorldBounds(new BlockPos(box.maxX(), box.maxY(), box.maxZ()))) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.load.out_of_world").withStyle(ChatFormatting.RED));
			return false;
		}

		StructureTemplate before = new StructureTemplate();
		BlockPos boxMin = new BlockPos(box.minX(), box.minY(), box.minZ());
		before.fillFromWorld(level, boxMin, new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan()), false, List.of());
		AABB area = AABB.of(box);
		Set<UUID> existing = new HashSet<>();
		for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player))) {
			existing.add(e.getUUID());
		}
		template.placeInWorld(level, pos, pos, settings, level.getRandom(), Block.UPDATE_CLIENTS);
		Set<UUID> spawned = new HashSet<>();
		for (Entity e : level.getEntities((Entity) null, area.inflate(1.0), e -> !(e instanceof Player))) {
			if (!existing.contains(e.getUUID())) {
				spawned.add(e.getUUID());
			}
		}

		MutableComponent message = Component.translatable("message.realistic_ificator.load.done",
			Component.literal("#" + slot).withStyle(ChatFormatting.AQUA), size(box));
		if (realistic) {
			AreaConversion.Result result = AreaConversion.convert(level, box);
			if (result instanceof AreaConversion.Converted converted) {
				message.append(" ").append(Component.translatable("message.realistic_ificator.load.realistic", converted.bodies()));
			} else if (result instanceof AreaConversion.TooBig tooBig) {
				message.append(" ").append(Component.translatable("message.realistic_ificator.wand.too_big", tooBig.blocks(), tooBig.limit()).withStyle(ChatFormatting.RED));
			}
		}
		UNDO.put(player.getUUID(), new Undo(level, boxMin, before, spawned));
		player.sendSystemMessage(message.withStyle(ChatFormatting.GREEN).append(" ")
			.append(button("message.realistic_ificator.load.undo", "/load undo", ChatFormatting.YELLOW)));
		level.playSound(null, box.getCenter(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.0F);
		level.playSound(null, box.getCenter(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.4F);
		sparkleBox(level, box, ParticleTypes.END_ROD);
		ModAdvancements.award(player, ModAdvancements.LOADED);
		return true;
	}

	public static boolean undo(ServerPlayer player) {
		Undo undo = UNDO.remove(player.getUUID());
		if (undo == null) {
			player.sendSystemMessage(Component.translatable("message.realistic_ificator.load.nothing_to_undo").withStyle(ChatFormatting.RED));
			return false;
		}
		for (UUID id : undo.spawned()) {
			Entity e = undo.level().getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		// Anything a realistic paste turned into physics bodies that is still in the area goes too.
		Vec3i size = undo.before().getSize();
		AABB area = new AABB(undo.min().getX(), undo.min().getY(), undo.min().getZ(),
			undo.min().getX() + size.getX(), undo.min().getY() + size.getY(), undo.min().getZ() + size.getZ());
		for (Entity e : undo.level().getEntities((Entity) null, area, e -> e instanceof io.github.uddinnadd.realisticificator.entity.RealisticBlockEntity)) {
			e.discard();
		}
		undo.before().placeInWorld(undo.level(), undo.min(), undo.min(), new StructurePlaceSettings(), undo.level().getRandom(), Block.UPDATE_CLIENTS);
		player.sendSystemMessage(Component.translatable("message.realistic_ificator.load.undone").withStyle(ChatFormatting.GREEN));
		undo.level().playSound(null, player.blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1.0F, 0.8F);
		return true;
	}

	public static void list(ServerPlayer player) {
		StructureTemplateManager manager = player.level().getStructureTemplateManager();
		player.sendSystemMessage(Component.translatable("message.realistic_ificator.saves.header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (int slot = 1; slot <= SLOTS; slot++) {
			MutableComponent line = Component.literal(" #" + slot + "  ").withStyle(ChatFormatting.AQUA);
			Optional<StructureTemplate> found = manager.get(slotId(player, slot));
			if (found.isPresent() && !found.get().getSize().equals(Vec3i.ZERO)) {
				Vec3i s = found.get().getSize();
				line.append(Component.literal(s.getX() + "x" + s.getY() + "x" + s.getZ() + "  ").withStyle(ChatFormatting.WHITE))
					.append(button("message.realistic_ificator.saves.load", "/load " + slot, ChatFormatting.GREEN)).append(" ")
					.append(button("message.realistic_ificator.saves.load_realistic", "/load " + slot + " realistic", ChatFormatting.LIGHT_PURPLE)).append(" ")
					.append(button("message.realistic_ificator.saves.overwrite", "/save " + slot, ChatFormatting.GRAY));
			} else {
				line.append(Component.translatable("message.realistic_ificator.saves.empty").withStyle(ChatFormatting.DARK_GRAY)).append("  ")
					.append(button("message.realistic_ificator.saves.save_here", "/save " + slot, ChatFormatting.GRAY));
			}
			player.sendSystemMessage(line);
		}
	}

	private static Direction savedFacing(StructureTemplate template) {
		String author = template.getAuthor();
		int split = author.lastIndexOf(';');
		if (split >= 0) {
			try {
				return Direction.from2DDataValue(Integer.parseInt(author.substring(split + 1)));
			} catch (NumberFormatException ignored) {
			}
		}
		return Direction.NORTH;
	}

	/**
	 * Where the paste goes: resting on the block the player looks at (or a few blocks ahead), centred on that spot and
	 * stretching away from the player, so it never lands on top of them.
	 */
	private static BlockPos targetMin(ServerPlayer player, Direction facing, Vec3i size) {
		HitResult hit = player.pick(REACH, 1.0F, false);
		BlockPos anchor;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			anchor = blockHit.getBlockPos().relative(blockHit.getDirection());
		} else {
			anchor = player.blockPosition().relative(facing, 3);
		}
		int x = anchor.getX() - size.getX() / 2;
		int z = anchor.getZ() - size.getZ() / 2;
		switch (facing) {
			case EAST -> x = anchor.getX();
			case WEST -> x = anchor.getX() - size.getX() + 1;
			case SOUTH -> z = anchor.getZ();
			default -> z = anchor.getZ() - size.getZ() + 1;
		}
		return new BlockPos(x, anchor.getY(), z);
	}

	private static String size(BoundingBox box) {
		return box.getXSpan() + "x" + box.getYSpan() + "x" + box.getZSpan();
	}

	private static MutableComponent button(String key, String command, ChatFormatting color) {
		return Component.literal("[").append(Component.translatable(key)).append("]").withStyle(style -> style.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	/** A burst of sparkles over the edges of a box. */
	public static void sparkleBox(ServerLevel level, BoundingBox box, net.minecraft.core.particles.SimpleParticleType particle) {
		double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX() + 1, y1 = box.maxY() + 1, z1 = box.maxZ() + 1;
		double[][] edges = {
			{x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
			{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
			{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}
		};
		for (double[] e : edges) {
			double length = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
			int points = (int) Math.min(48, Math.max(2, length * 2));
			for (int i = 0; i <= points; i++) {
				double t = i / (double) points;
				level.sendParticles(particle, e[0] + (e[3] - e[0]) * t, e[1] + (e[4] - e[1]) * t, e[2] + (e[5] - e[2]) * t, 1, 0.05, 0.05, 0.05, 0.01);
			}
		}
	}
}
