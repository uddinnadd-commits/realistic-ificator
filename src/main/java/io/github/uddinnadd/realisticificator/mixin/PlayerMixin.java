package io.github.uddinnadd.realisticificator.mixin;

import io.github.uddinnadd.realisticificator.entity.RagdollEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A knocked-down player can't sneak their way out of their own ragdoll; they get up when it settles. */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void realisticIficator$stayKnockedDown(CallbackInfoReturnable<Boolean> cir) {
		if (((Player) (Object) this).getVehicle() instanceof RagdollEntity) {
			cir.setReturnValue(false);
		}
	}
}
