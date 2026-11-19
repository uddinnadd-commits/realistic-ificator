package io.github.uddinnadd.realisticificator.client;

import io.github.uddinnadd.realisticificator.registry.ModEntities;
import io.github.uddinnadd.realisticificator.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

public class RealisticIficatorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.REALISTIC_BLOCK, RealisticBlockRenderer::new);
		MenuScreens.register(ModMenus.REALISTIC_IFICATOR, RealisticIficatorScreen::new);
	}
}
