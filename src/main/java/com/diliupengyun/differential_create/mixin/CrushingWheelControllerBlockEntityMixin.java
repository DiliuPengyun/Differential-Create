package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrushingWheelControllerBlockEntity.class)
public abstract class CrushingWheelControllerBlockEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$captureWork(CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.MachineActivity.beforeCrusherTick(
			(CrushingWheelControllerBlockEntity) (Object) this);
	}
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
	private float differentialcreate$batchWork(float original) {
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		int count = self.inventory.appliedRecipe ? 1 : Math.max(1, self.inventory.getStackInSlot(0).getCount());
		return Math.abs(self.crushingspeed) * 4 / count;
	}
}
