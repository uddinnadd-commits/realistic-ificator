package io.github.uddinnadd.realisticificator.registry;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;

/** The mod's code-awarded advancements; each uses an impossible trigger with a single criterion named "done". */
public final class ModAdvancements {
	public static final String RAGDOLLED = "ragdolled";
	public static final String YEET = "yeet";
	public static final String CONVERTED = "structural_integrity";
	public static final String SAVED = "save_point";
	public static final String LOADED = "copy_paste";

	private ModAdvancements() {
	}

	public static void award(ServerPlayer player, String name) {
		AdvancementHolder holder = player.level().getServer().getAdvancements().get(RealisticIficator.id(name));
		if (holder != null) {
			player.getAdvancements().award(holder, "done");
		}
	}
}
