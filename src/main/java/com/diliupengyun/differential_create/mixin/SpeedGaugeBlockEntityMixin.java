package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SpeedGaugeBlockEntity.class)
public abstract class SpeedGaugeBlockEntityMixin {
	@Redirect(method = "addToGoggleTooltip", at = @At(value = "FIELD",
		target = "Lcom/simibubi/create/content/kinetics/gauge/SpeedGaugeBlockEntity;speed:F"))
	private float differentialcreate$physicalReadout(SpeedGaugeBlockEntity self) { return self.getSpeed(); }
}
