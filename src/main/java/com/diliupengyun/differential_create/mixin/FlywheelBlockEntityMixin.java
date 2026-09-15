package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The network supplies inertia and angle; the flywheel needs no visual chaser. */
@Mixin(FlywheelBlockEntity.class)
public abstract class FlywheelBlockEntityMixin {
	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V",
		shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$skipIndependentAnimation(CallbackInfo ci) {
		ci.cancel();
	}
}
