package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.util.Mth;

/**
 * Makes windmill speed a continuous function of the sail count (DESIGN.md
 * §2.2): vanilla computes sailBlocks / windmillSailsPerRPM with integer
 * division, so sails below a full RPM step contribute nothing. Every sail now
 * shifts the no-load speed ω0 (and through the source characteristic, the
 * available torque).
 */
@Mixin(WindmillBearingBlockEntity.class)
public abstract class WindmillBearingBlockEntityMixin {

	@Shadow
	protected abstract float getAngleSpeedDirection();

	@Inject(method = "getGeneratedSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$continuousSailSpeed(CallbackInfoReturnable<Float> cir) {
		MechanicalBearingAccessor bearing = (MechanicalBearingAccessor) this;
		ControlledContraptionEntity contraption = bearing.differentialcreate$getMovedContraption();
		if (!bearing.differentialcreate$isRunning() || contraption == null)
			return; // keep vanilla behaviour (0, or lastGeneratedSpeed while re-assembling)
		float sails = ((BearingContraption) contraption.getContraption()).getSailBlocks()
			/ (float) AllConfigs.server().kinetics.windmillSailsPerRPM.get();
		cir.setReturnValue(Mth.clamp(sails, 1, 16) * getAngleSpeedDirection());
	}

}
