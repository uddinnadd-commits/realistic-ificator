package io.github.uddinnadd.realisticificator.registry;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Unit;

public final class ModComponents {
	/** Marks a block item stack as "realistic": placing it spawns a physics block instead of a normal block. */
	public static final DataComponentType<Unit> REALISTIC = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		RealisticIficator.id("realistic"),
		DataComponentType.<Unit>builder().persistent(Unit.CODEC).networkSynchronized(StreamCodec.unit(Unit.INSTANCE)).build()
	);

	private ModComponents() {
	}

	public static void init() {
	}
}
