package io.github.uddinnadd.realisticificator.registry;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.item.RealisticIficatorItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public final class ModItems {
	public static final ResourceKey<Item> REALISTIC_IFICATOR_KEY = ResourceKey.create(Registries.ITEM, RealisticIficator.id("realistic_ificator"));
	public static final Item REALISTIC_IFICATOR = Registry.register(
		BuiltInRegistries.ITEM,
		REALISTIC_IFICATOR_KEY,
		new RealisticIficatorItem(new Item.Properties().setId(REALISTIC_IFICATOR_KEY).stacksTo(1).rarity(Rarity.RARE))
	);

	private ModItems() {
	}

	public static void init() {
	}
}
