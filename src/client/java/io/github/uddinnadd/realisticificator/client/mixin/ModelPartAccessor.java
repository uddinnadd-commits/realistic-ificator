package io.github.uddinnadd.realisticificator.client.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the ragdoll renderer split a mob model into per-bone pieces without touching the shared model. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> realisticIficator$getCubes();

	@Accessor("children")
	Map<String, ModelPart> realisticIficator$getChildren();
}
