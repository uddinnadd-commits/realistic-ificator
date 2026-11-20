package io.github.uddinnadd.realisticificator.client.mixin;

import io.github.uddinnadd.realisticificator.entity.RagdollEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a ragdoll stands in for an entity (knocked down or dying), only the ragdoll is drawn. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void realisticIficator$hideRagdolled(E entity, Frustum culler, double camX, double camY, double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
		if (RagdollEntity.hidesOnClient(entity)) {
			cir.setReturnValue(false);
		}
	}
}
