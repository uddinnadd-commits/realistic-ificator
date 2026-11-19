package io.github.uddinnadd.realisticificator.item;

import io.github.uddinnadd.realisticificator.menu.RealisticIficatorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public class RealisticIficatorItem extends Item {
	public static final Component TITLE = Component.translatable("container.realistic_ificator.realistic_ificator");

	public RealisticIficatorItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide()) {
			player.openMenu(new SimpleMenuProvider((containerId, inventory, p) -> new RealisticIficatorMenu(containerId, inventory), TITLE));
		}
		return InteractionResult.SUCCESS;
	}
}
