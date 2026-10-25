package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.BearingAnimation;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MechanicalBearingBlockEntity.class)
public abstract class MechanicalBearingRendererMixin {
	@Shadow protected float clientAngleDiff;

	@Inject(method = "getAngularSpeed", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$disableDuplicatePrediction(CallbackInfoReturnable<Float> cir) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || !self.getLevel().isClientSide || self.getMovedContraption() == null) return;
		if (ClientNetworkSpeeds.bearingRotationOf(self, self.getMovedContraption().getId()) != null) {
			// Snapshot playback applies the endpoint once per client tick. Advancing
			// it again here would move native collision/rider state past that pose.
			clientAngleDiff = 0;
			cir.setReturnValue(0f);
		}
	}

	@Inject(method = "getInterpolatedAngle", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$physicalPose(float partialTicks, CallbackInfoReturnable<Float> cir) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel() instanceof net.createmod.ponder.api.level.PonderLevel preview) {
			var angle = com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(preview).bearingAngle(self, partialTicks + 1);
			if (angle != null) cir.setReturnValue(angle.floatValue());
			return;
		}
		if (self.getLevel() == null || !self.getLevel().isClientSide || self.getMovedContraption() == null) return;
		var frame = ClientNetworkSpeeds.bearingRotationOf(self, self.getMovedContraption().getId());
		if (frame != null) {
			// Both Create top renderers pass partialTick - 1: the native method
			// extrapolates from the tick endpoint. Our frame starts at the previous
			// pose, so restore the render fraction before interpolating the segment.
			cir.setReturnValue(BearingAnimation.angleAt(frame, partialTicks + 1));
		}
	}
}
