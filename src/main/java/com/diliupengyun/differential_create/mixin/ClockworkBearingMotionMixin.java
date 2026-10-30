package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.diliupengyun.differential_create.kinetics.ClockworkTargets;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClockworkBearingBlockEntity.class)
public abstract class ClockworkBearingMotionMixin {
	@Shadow protected float hourAngle;
	@Shadow protected float minuteAngle;
	@Shadow protected boolean assembleNextTick;
	@Shadow protected abstract void applyRotations();
	// Keep the native time read so Ponder's demonstration clock and the dimension
	// multiplier still apply; replace only the whole-hour/minute rounding.
	@ModifyReturnValue(method = "getHourTarget", at = @At("RETURN"))
	private float differentialcreate$continuousHour(float original, boolean cycle24,
		@Local(ordinal = 0) int dayTime, @Local(ordinal = 2) int direction) {
		return ClockworkTargets.hour(dayTime, cycle24, direction);
	}
	@ModifyReturnValue(method = "getMinuteTarget", at = @At("RETURN"))
	private float differentialcreate$continuousMinute(float original,
		@Local(ordinal = 0) int dayTime, @Local(ordinal = 2) int direction) {
		return ClockworkTargets.minute(dayTime, direction);
	}
	@Redirect(method = "onSpeedChanged", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
		target = "Lcom/simibubi/create/content/contraptions/bearing/ClockworkBearingBlockEntity;assembleNextTick:Z"))
	private void differentialcreate$keepAcceptedTravel(ClockworkBearingBlockEntity self, boolean requested) {
		// Continuous load changes notify RPM every tick. Create's assembly branch
		// returns before applying either hand, discarding already-paid travel.
		// Retain the native auto-assembly request only before a body is attached.
		if (self.isRunning() && self.getLevel() != null && !self.getLevel().isClientSide
			&& ContraptionMotion.managed(((ClockworkBearingAccessor) self).differentialcreate$hour())) return;
		assembleNextTick = requested;
	}
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

	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$retainReloadedTravel(CallbackInfo ci) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		// The input network can notify speed before either saved entity attaches.
		// Once attached, that assembly flag must not skip the first physical step.
		if (self.isRunning() && self.getLevel() != null && !self.getLevel().isClientSide
			&& ContraptionMotion.managed(((ClockworkBearingAccessor) this).differentialcreate$hour())) assembleNextTick = false;
	}
	@Inject(method = "getHourArmSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$hourTravel(CallbackInfoReturnable<Float> cir) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		Double angle = ContraptionMotion.angular(((ClockworkBearingAccessor) this).differentialcreate$hour());
		if (angle != null) cir.setReturnValue(angle.floatValue());
		else if (!ContraptionMotion.samplingGeometry()) cir.setReturnValue(0f);
	}
	@Inject(method = "getMinuteArmSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$minuteTravel(CallbackInfoReturnable<Float> cir) {
		var self = (ClockworkBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		Double angle = ContraptionMotion.angular(((ClockworkBearingAccessor) this).differentialcreate$minute());
		if (angle != null) cir.setReturnValue(angle.floatValue());
		else if (!ContraptionMotion.samplingGeometry()) cir.setReturnValue(0f);
	}
}
