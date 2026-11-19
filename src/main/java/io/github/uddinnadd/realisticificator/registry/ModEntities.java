package io.github.uddinnadd.realisticificator.registry;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.entity.RealisticBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> REALISTIC_BLOCK_KEY = ResourceKey.create(Registries.ENTITY_TYPE, RealisticIficator.id("realistic_block"));
	public static final EntityType<RealisticBlockEntity> REALISTIC_BLOCK = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		REALISTIC_BLOCK_KEY,
		EntityType.Builder.<RealisticBlockEntity>of(RealisticBlockEntity::new, MobCategory.MISC)
			.noLootTable()
			.sized(1.0F, 1.0F)
			.fireImmune()
			.clientTrackingRange(10)
			.updateInterval(1)
			.build(REALISTIC_BLOCK_KEY)
	);

	private ModEntities() {
	}

	public static void init() {
	}
}
