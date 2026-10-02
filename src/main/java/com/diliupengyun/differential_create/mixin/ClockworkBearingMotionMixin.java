package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClockworkBearingBlockEntity.class)
public abstract class ClockworkBearingMotionMixin {
	@Shadow protected float hourAngle;
	@Shadow protected float minuteAngle;
	@Shadow protected abstract void applyRotations();
	@Inject(method = "attach", at = @At("RETURN"))
	private void differentialcreate$recoverPaidTravel(com.simibubi.create.content.contraptions.ControlledContraptionEntity root, CallbackInfo ci) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		if (self.getLevel().isClientSide || !ContraptionMotion.restored(root)) return;
		double pending = ContraptionMotion.angular(root);
		if (((ClockworkBearingAccessor) this).differentialcreate$hour() == root) hourAngle = (float) ((hourAngle + pending) % 360);
		else if (((ClockworkBearingAccessor) this).differentialcreate$minute() == root) minuteAngle = (float) ((minuteAngle + pending) % 360);
		else return;
		applyRotations(); ContraptionMotion.consume(root); self.sendData();
	}
	@Inject(method = "tick", at = @At("RETURN"))
	private void differentialcreate$consumeOutputTravel(CallbackInfo ci) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		ContraptionMotion.consume(((ClockworkBearingAccessor) this).differentialcreate$hour());
		ContraptionMotion.consume(((ClockworkBearingAccessor) this).differentialcreate$minute());
	}
	@Inject(method = "getHourArmSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$hourTravel(CallbackInfoReturnable<Float> cir) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		Double angle = ContraptionMotion.angular(((ClockworkBearingAccessor) this).differentialcreate$hour());
		if (angle != null) cir.setReturnValue(angle.floatValue());
	}
	@Inject(method = "getMinuteArmSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$minuteTravel(CallbackInfoReturnable<Float> cir) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		Double angle = ContraptionMotion.angular(((ClockworkBearingAccessor) this).differentialcreate$minute());
		if (angle != null) cir.setReturnValue(angle.floatValue());
	}
}
