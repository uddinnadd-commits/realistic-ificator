package io.github.uddinnadd.realisticificator.physics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Approximate real-world densities in g/cm³ (water = 1.0). Anything below 1 floats in water,
 * anything below ~3.1 floats in lava.
 */
public final class Densities {
	public static final double WATER = 1.0;
	public static final double LAVA = 3.1;

	private Densities() {
	}

	public static double of(BlockState state) {
		if (state.is(BlockTags.LEAVES)) return 0.2;
		if (state.is(BlockTags.WOOL)) return 0.25;
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return 0.6;
		if (state.is(BlockTags.ICE)) return 0.92;

		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (id.contains("sponge")) return 0.15;
		if (id.contains("hay") || id.contains("snow") || id.contains("moss")) return 0.3;
		if (id.contains("bamboo")) return 0.5;
		if (id.contains("wood") || id.contains("stem") || id.contains("hyphae") || id.contains("bookshelf")) return 0.6;
		if (id.contains("cactus") || id.contains("pumpkin") || id.contains("melon") || id.contains("dried_kelp")) return 0.85;
		if (id.contains("slime")) return 1.1;
		if (id.contains("honey") || id.contains("dirt") || id.contains("grass") || id.contains("mud") || id.contains("podzol") || id.contains("mycelium")) return 1.4;
		if (id.endsWith("_ore") || id.startsWith("raw_")) return 3.4;
		if (id.contains("netherite")) return 15.0;
		if (id.contains("gold")) return 19.3;
		if (id.contains("copper")) return 8.9;
		if (id.contains("iron") || id.contains("anvil")) return 7.8;
		if (id.contains("diamond")) return 3.5;
		if (id.contains("emerald")) return 2.7;
		if (id.contains("lapis")) return 2.8;
		if (id.contains("redstone")) return 3.0;
		if (id.contains("sand")) return id.contains("stone") ? 2.3 : 1.6;
		if (id.contains("gravel") || id.contains("clay") || id.contains("terracotta") || id.contains("brick")) return 1.9;
		if (id.contains("glass")) return 2.5;
		if (id.contains("obsidian")) return 2.4;
		if (id.contains("concrete")) return 2.4;

		// Unknown blocks: soft blocks are light, everything else is treated as stone.
		return state.getBlock().getExplosionResistance() < 1.0F ? 0.8 : 2.6;
	}
}
