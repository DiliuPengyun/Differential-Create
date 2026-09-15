package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PoweredShaftBlockEntity.class)
public abstract class PoweredShaftBlockEntityMixin {
	@Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$continuousSteam(CallbackInfoReturnable<Float> cir) {
		PoweredShaftBlockEntity self = (PoweredShaftBlockEntity) (Object) this;
		cir.setReturnValue((float) (com.diliupengyun.differential_create.kinetics.SourcePhysics.steamCurve(self).noLoadSpeed() / 2));
	}
}
