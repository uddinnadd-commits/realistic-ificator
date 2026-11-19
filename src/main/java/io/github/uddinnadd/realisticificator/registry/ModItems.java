package io.github.uddinnadd.realisticificator.registry;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.item.PhysicsGunItem;
import io.github.uddinnadd.realisticificator.item.RealisticIficatorItem;
import io.github.uddinnadd.realisticificator.item.StructureWandItem;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.UseEffects;

public final class ModItems {
	public static final ResourceKey<Item> REALISTIC_IFICATOR_KEY = ResourceKey.create(Registries.ITEM, RealisticIficator.id("realistic_ificator"));
	public static final Item REALISTIC_IFICATOR = Registry.register(
		BuiltInRegistries.ITEM,
		REALISTIC_IFICATOR_KEY,
		new RealisticIficatorItem(new Item.Properties().setId(REALISTIC_IFICATOR_KEY).stacksTo(1).rarity(Rarity.RARE))
	);
	public static final ResourceKey<Item> PHYSICS_GUN_KEY = ResourceKey.create(Registries.ITEM, RealisticIficator.id("physics_gun"));
	public static final Item PHYSICS_GUN = Registry.register(
		BuiltInRegistries.ITEM,
		PHYSICS_GUN_KEY,
		new PhysicsGunItem(new Item.Properties().setId(PHYSICS_GUN_KEY).stacksTo(1).rarity(Rarity.EPIC)
			// Walk and sprint at full speed while holding something, like in Gmod.
			.component(DataComponents.USE_EFFECTS, new UseEffects(true, false, 1.0F)))
	);
	public static final ResourceKey<Item> STRUCTURE_WAND_KEY = ResourceKey.create(Registries.ITEM, RealisticIficator.id("structure_wand"));
	public static final Item STRUCTURE_WAND = Registry.register(
		BuiltInRegistries.ITEM,
		STRUCTURE_WAND_KEY,
		new StructureWandItem(new Item.Properties().setId(STRUCTURE_WAND_KEY).stacksTo(1).rarity(Rarity.RARE))
	);

	private ModItems() {
	}

	public static void init() {
	}
}
