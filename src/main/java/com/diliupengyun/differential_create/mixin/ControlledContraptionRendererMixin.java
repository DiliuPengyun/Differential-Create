package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.BearingAnimation;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both Create renderers and rider transforms use the same signed angle segment. */
@Mixin(ControlledContraptionEntity.class)
public abstract class ControlledContraptionRendererMixin {
	@Unique private MechanicalBearingBlockEntity differentialcreate$controller;

	@Inject(method = "getAngle", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$physicalPose(float partialTicks, CallbackInfoReturnable<Float> cir) {
		var self = (ControlledContraptionEntity) (Object) this;
		if (!self.level().isClientSide) return;
		if (((ControlledContraptionAccessor) self).differentialcreate$controller()
			instanceof com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity clock) {
			var frame = ClientNetworkSpeeds.clockRotationOf(clock, self.getId());
			if (frame != null) cir.setReturnValue(BearingAnimation.angleAt(frame, partialTicks));
			return;
		}
		if (!(self.getContraption() instanceof BearingContraption)) return;
		var bearing = differentialcreate$controller;
		if (bearing == null || bearing.isRemoved()) {
			differentialcreate$controller = bearing = ((ControlledContraptionAccessor) self).differentialcreate$controller()
				instanceof MechanicalBearingBlockEntity controller ? controller : null;
		}
		if (bearing != null && bearing.getMovedContraption() == self) {
			var frame = ClientNetworkSpeeds.bearingRotationOf(bearing, self.getId());
			if (frame != null) cir.setReturnValue(BearingAnimation.angleAt(frame, partialTicks));
		}
	}
}
