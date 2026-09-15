package com.diliupengyun.differential_create.mixin;

import java.util.List;

import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** These tooltip overrides never reach KineticBlockEntity's common readouts. */
@Mixin({SpeedGaugeBlockEntity.class, StressGaugeBlockEntity.class, PoweredShaftBlockEntity.class})
public abstract class KineticGoggleOverridesMixin {

	@Unique
	private int differentialcreate$tooltipStart;

	@Inject(method = "addToGoggleTooltip", at = @At("HEAD"))
	private void differentialcreate$captureTooltipStart(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		differentialcreate$tooltipStart = tooltip.size();
	}

	@Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$physicalViews(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		// Replace the readouts while retaining the stress gauge's observation packet.
		tooltip.subList(differentialcreate$tooltipStart, tooltip.size()).clear();
		KineticGoggleInfo.append((KineticBlockEntity) (Object) this, tooltip, isPlayerSneaking);
		cir.setReturnValue(true);
	}
}
