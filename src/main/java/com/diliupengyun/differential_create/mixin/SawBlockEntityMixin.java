package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SawBlockEntity.class)
public abstract class SawBlockEntityMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
	private float differentialcreate$shaftTravel(float original) {
		return Math.abs(((SawBlockEntity) (Object) this).getSpeed()) / 24;
	}
}
