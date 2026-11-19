package io.github.uddinnadd.realisticificator.physics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Surface properties used by the contact solver. */
public final class Materials {
	private Materials() {
	}

	/** Coulomb friction coefficient, derived from vanilla slipperiness (default 0.6 -> 0.6, ice 0.98 -> 0.03). */
	public static double friction(BlockState state) {
		if (state.is(Blocks.HONEY_BLOCK)) return 1.2;
		return Math.clamp((1.0 - state.getBlock().getFriction()) * 1.5, 0.015, 1.0);
	}

	/** Coefficient of restitution: how much of the impact speed comes back as bounce. */
	public static double restitution(BlockState state) {
		if (state.is(Blocks.SLIME_BLOCK)) return 0.85;
		if (state.is(Blocks.HONEY_BLOCK)) return 0.0;
		if (state.is(BlockTags.BEDS)) return 0.55;
		if (state.is(BlockTags.WOOL) || state.is(BlockTags.LEAVES)) return 0.02;
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (id.contains("sponge") || id.contains("hay") || id.contains("moss") || id.contains("snow")) return 0.02;
		if (id.contains("mud") || id.contains("dirt") || id.contains("sand") || id.contains("gravel")) return 0.05;
		return 0.15;
	}

	public static double combineFriction(double a, double b) {
		return Math.min(a, b); // a slippery surface on either side wins
	}

	public static double combineRestitution(double a, double b) {
		return Math.min(1.0, Math.max(a, b)); // so slime bounces on anything, and anything bounces on slime
	}
}
