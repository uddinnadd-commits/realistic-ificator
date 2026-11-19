package io.github.uddinnadd.realisticificator;

import io.github.uddinnadd.realisticificator.entity.BlockStructure;
import io.github.uddinnadd.realisticificator.entity.RealisticPlacement;
import io.github.uddinnadd.realisticificator.registry.ModComponents;
import io.github.uddinnadd.realisticificator.registry.ModEntities;
import io.github.uddinnadd.realisticificator.registry.ModItems;
import io.github.uddinnadd.realisticificator.registry.ModMenus;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityDataRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTabs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealisticIficator implements ModInitializer {
	public static final String MOD_ID = "realistic_ificator";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		FabricEntityDataRegistry.register(id("block_structure"), BlockStructure.SERIALIZER);
		FabricEntityDataRegistry.register(id("body_pose"), io.github.uddinnadd.realisticificator.entity.BodyPose.SERIALIZER);
		FabricEntityDataRegistry.register(id("ragdoll_info"), io.github.uddinnadd.realisticificator.entity.RagdollInfo.SERIALIZER);
		FabricEntityDataRegistry.register(id("ragdoll_poses"), io.github.uddinnadd.realisticificator.entity.RagdollInfo.POSES_SERIALIZER);
		ModComponents.init();
		ModItems.init();
		ModEntities.init();
		ModMenus.init();

		UseBlockCallback.EVENT.register(RealisticPlacement::onUseBlock);
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(ModItems.REALISTIC_IFICATOR);
			output.accept(ModItems.PHYSICS_GUN);
			output.accept(ModItems.STRUCTURE_WAND);
		});

		LOGGER.info("Realistic-Ificator loaded: blocks are about to get real.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
