package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The windmill structure turns with its shaft, independently of its source command. */
@Mixin(MechanicalBearingBlockEntity.class)
public abstract class MechanicalBearingBlockEntityMixin {
	@Redirect(method = "getAngularSpeed", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/bearing/MechanicalBearingBlockEntity;getGeneratedSpeed()F"))
	private float differentialcreate$physicalWindmillSpeed(MechanicalBearingBlockEntity bearing) {
		// This call is only in Create's windmill branch. Keep its degree/tick
		// conversion and client angle reconciliation, and leave getGeneratedSpeed
		// itself available to the sail-dependent torque characteristic.
		return bearing.getSpeed();
	}
}
