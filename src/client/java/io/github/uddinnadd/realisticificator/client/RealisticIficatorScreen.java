package io.github.uddinnadd.realisticificator.client;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.menu.RealisticIficatorMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public class RealisticIficatorScreen extends ItemCombinerScreen<RealisticIficatorMenu> {
	private static final Identifier TEXTURE = RealisticIficator.id("textures/gui/container/realistic_ificator.png");
	private static final Identifier ERROR_SPRITE = Identifier.withDefaultNamespace("container/smithing/error");
	private static final Component HINT = Component.translatable("container.realistic_ificator.hint");
	private static final Component INVALID = Component.translatable("container.realistic_ificator.invalid");

	public RealisticIficatorScreen(RealisticIficatorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, TEXTURE);
		this.titleLabelX = 8;
		this.titleLabelY = 6;
	}

	@Override
	protected void extractErrorIcon(GuiGraphicsExtractor graphics, int xo, int yo) {
		if (this.menu.hasInvalidInput()) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ERROR_SPRITE, xo + 66, yo + 45, 28, 21);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		super.extractLabels(graphics, xm, ym);
		Component line = this.menu.hasInvalidInput() ? INVALID : HINT;
		graphics.centeredText(this.font, line, this.imageWidth / 2, 24, this.menu.hasInvalidInput() ? 0xFFB02E26 : 0xFF404040);
	}
}
