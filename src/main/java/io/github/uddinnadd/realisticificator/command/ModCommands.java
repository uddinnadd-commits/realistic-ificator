package io.github.uddinnadd.realisticificator.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.uddinnadd.realisticificator.entity.RagdollEntity;
import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** /save, /load and /saves for structure slots; /ragdoll and /yeet for operators. */
public final class ModCommands {
	private ModCommands() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("save")
			.then(Commands.argument("slot", IntegerArgumentType.integer(1, StructureSaves.SLOTS))
				.executes(c -> StructureSaves.save(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "slot")) ? 1 : 0)));

		// "/load 3", "/load save 3" and "/load save3" all work.
		LiteralArgumentBuilder<CommandSourceStack> load = Commands.literal("load")
			.then(slotArgument())
			.then(Commands.literal("save").then(slotArgument()))
			.then(Commands.literal("undo").executes(c -> StructureSaves.undo(c.getSource().getPlayerOrException()) ? 1 : 0));
		for (int slot = 1; slot <= StructureSaves.SLOTS; slot++) {
			int s = slot;
			load.then(Commands.literal("save" + slot)
				.executes(c -> load(c, s, false))
				.then(Commands.literal("realistic").executes(c -> load(c, s, true))));
		}
		dispatcher.register(load);

		dispatcher.register(Commands.literal("saves").executes(c -> {
			StructureSaves.list(c.getSource().getPlayerOrException());
			return 1;
		}));

		dispatcher.register(Commands.literal("ragdoll")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.executes(c -> knockDown(c.getSource(), List.of(c.getSource().getEntityOrException()), 0))
			.then(Commands.argument("targets", EntityArgument.entities())
				.executes(c -> knockDown(c.getSource(), EntityArgument.getEntities(c, "targets"), 0))));

		dispatcher.register(Commands.literal("yeet")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.argument("targets", EntityArgument.entities())
				.executes(c -> knockDown(c.getSource(), EntityArgument.getEntities(c, "targets"), 1.0F))
				.then(Commands.argument("power", FloatArgumentType.floatArg(0.0F, 5.0F))
					.executes(c -> knockDown(c.getSource(), EntityArgument.getEntities(c, "targets"), FloatArgumentType.getFloat(c, "power"))))));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> slotArgument() {
		return Commands.argument("slot", IntegerArgumentType.integer(1, StructureSaves.SLOTS))
			.executes(c -> load(c, IntegerArgumentType.getInteger(c, "slot"), false))
			.then(Commands.literal("realistic").executes(c -> load(c, IntegerArgumentType.getInteger(c, "slot"), true)));
	}

	private static int load(CommandContext<CommandSourceStack> c, int slot, boolean realistic) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return StructureSaves.load(player, slot, realistic) ? 1 : 0;
	}

	/** Knocks the targets down, flinging them the way the command's user is looking (or straight up) when power > 0. */
	private static int knockDown(CommandSourceStack source, Collection<? extends Entity> targets, float power) {
		Vec3 look = source.getEntity() != null ? source.getEntity().getLookAngle() : new Vec3(0, 1, 0);
		Vec3 fling = power <= 0 ? Vec3.ZERO : new Vec3(look.x * 1.2, Math.max(0.6, look.y + 0.6), look.z * 1.2).scale(power);
		int count = 0;
		for (Entity e : targets) {
			if (e instanceof LivingEntity living && !(e instanceof RagdollEntity) && living.isAlive()
				&& RagdollEntity.knockDown(source.getLevel(), living, living.getDeltaMovement(), fling, null) != null) {
				count++;
			} else if (e instanceof RagdollEntity ragdoll) {
				ragdoll.fling(fling, null);
				count++;
			}
		}
		int n = count;
		source.sendSuccess(() -> Component.translatable(power > 0 ? "commands.realistic_ificator.yeet" : "commands.realistic_ificator.ragdoll", n), true);
		return count;
	}
}
