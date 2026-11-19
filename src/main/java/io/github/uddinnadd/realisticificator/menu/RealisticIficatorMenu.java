package io.github.uddinnadd.realisticificator.menu;

import io.github.uddinnadd.realisticificator.item.RealisticItems;
import io.github.uddinnadd.realisticificator.registry.ModMenus;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Smithing-table style menu: one block input, one realistic output. Opened from the hand, so it has no block position. */
public class RealisticIficatorMenu extends ItemCombinerMenu {
	public static final int INPUT_SLOT = 0;
	public static final int RESULT_SLOT = 1;

	public RealisticIficatorMenu(int containerId, Inventory inventory) {
		super(
			ModMenus.REALISTIC_IFICATOR,
			containerId,
			inventory,
			ContainerLevelAccess.NULL,
			ItemCombinerMenuSlotDefinition.create()
				.withSlot(INPUT_SLOT, 44, 47, RealisticItems::canBeMadeRealistic)
				.withResultSlot(RESULT_SLOT, 116, 47)
				.build()
		);
	}

	@Override
	protected boolean isValidBlock(BlockState state) {
		return true;
	}

	@Override
	public boolean stillValid(Player player) {
		return player.isAlive();
	}

	@Override
	public void createResult() {
		ItemStack input = this.inputSlots.getItem(INPUT_SLOT);
		this.resultSlots.setItem(0, RealisticItems.canBeMadeRealistic(input) ? RealisticItems.makeRealistic(input) : ItemStack.EMPTY);
		this.broadcastChanges();
	}

	@Override
	protected void onTake(Player player, ItemStack carried) {
		carried.onCraftedBy(player, carried.getCount());
		this.inputSlots.setItem(INPUT_SLOT, ItemStack.EMPTY);
		if (!player.level().isClientSide()) {
			player.level().playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
		}
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		// ContainerLevelAccess.NULL makes the parent skip returning items, so hand the input back ourselves.
		this.clearContainer(player, this.inputSlots);
	}

	public boolean hasInvalidInput() {
		ItemStack input = this.inputSlots.getItem(INPUT_SLOT);
		return !input.isEmpty() && !RealisticItems.canBeMadeRealistic(input);
	}
}
