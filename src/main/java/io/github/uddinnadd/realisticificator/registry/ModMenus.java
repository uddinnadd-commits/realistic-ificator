package io.github.uddinnadd.realisticificator.registry;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.menu.RealisticIficatorMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
	public static final MenuType<RealisticIficatorMenu> REALISTIC_IFICATOR = Registry.register(
		BuiltInRegistries.MENU,
		RealisticIficator.id("realistic_ificator"),
		new MenuType<>(RealisticIficatorMenu::new, FeatureFlags.VANILLA_SET)
	);

	private ModMenus() {
	}

	public static void init() {
	}
}
