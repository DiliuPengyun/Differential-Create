package com.diliupengyun.differential_create.mixin;

import java.util.List;

import com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeScale;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpeedGaugeBlockEntity.class)
public abstract class SpeedGaugeBlockEntityMixin {
	@Inject(method = "addBehaviours", at = @At("RETURN"))
	private void differentialcreate$rangeControl(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
		behaviours.add(new SpeedGaugeRangeBehaviour((SpeedGaugeBlockEntity) (Object) this));
	}

	@Redirect(method = "onSpeedChanged", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/gauge/SpeedGaugeBlockEntity;getDialTarget(F)F"))
	private float differentialcreate$linearDial(float speed) {
		return SpeedGaugeScale.fraction(speed, SpeedGaugeRangeBehaviour.rangeOf((SpeedGaugeBlockEntity) (Object) this));
	}

	@Redirect(method = "onSpeedChanged", at = @At(value = "INVOKE",
		target = "Lnet/createmod/catnip/theme/Color;mixColors(IIF)I"))
	private int differentialcreate$rangeColor(int original, int tint, float amount) {
		SpeedGaugeBlockEntity self = (SpeedGaugeBlockEntity) (Object) this;
		return SpeedGaugeScale.color(self.getSpeed(), SpeedGaugeRangeBehaviour.rangeOf(self));
	}

	@Redirect(method = "addToGoggleTooltip", at = @At(value = "FIELD",
		target = "Lcom/simibubi/create/content/kinetics/gauge/SpeedGaugeBlockEntity;speed:F"))
	private float differentialcreate$physicalReadout(SpeedGaugeBlockEntity self) { return self.getSpeed(); }
}
