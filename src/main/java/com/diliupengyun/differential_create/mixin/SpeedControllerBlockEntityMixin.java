package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Rebuilds the Rotation Speed Controller as an ideal CVT (DESIGN.md §7): an
 * adjustable-ratio edge in the network graph instead of a speed-locking
 * source. The dial value becomes the ratio r = dial/16 (16 = 1:1, sign
 * reverses), so both sides share one network and the per-member ratios
 * captured by the propagation carry the gearing. The RSC is not a source in
 * vanilla either (getGeneratedSpeed is not overridden), so nothing else is
 * needed to keep it out of network.sources.
 */
@Mixin(SpeedControllerBlockEntity.class)
public abstract class SpeedControllerBlockEntityMixin {
	@Inject(method = "addBehaviours", at = @At("RETURN"))
	private void differentialcreate$ratioControl(java.util.List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours,
		CallbackInfo ci) {
		SpeedControllerBlockEntity self = (SpeedControllerBlockEntity) (Object) this;
		var old = self.targetSpeed;
		var ratio = new com.diliupengyun.differential_create.kinetics.RatioValueBehaviour(self, old.getSlotPositioning());
		ratio.value = old.value;
		ratio.withCallback(value -> com.diliupengyun.differential_create.kinetics.NetworkTopology.mark(self));
		behaviours.remove(old);
		behaviours.add(ratio);
		self.targetSpeed = ratio;
	}

	/** RPM on the dial that corresponds to a 1:1 ratio. */
	private static final float DIAL_PER_RATIO = 16f;

	/**
	 * Replaces the vanilla speed-pinning special case with the ratio edge.
	 * targetingController=true: cog -> controller, the controller spins at
	 * r × wheel speed. targetingController=false: controller -> cog
	 * (backdrive through the ideal gearbox), the cog spins at controller / r.
	 * A zero dial is an open edge and conveys nothing either way.
	 */
	@Inject(method = "getConveyedSpeed", at = @At("HEAD"), cancellable = true)
	private static void differentialcreate$cvtConveyed(KineticBlockEntity cogWheel, KineticBlockEntity speedControllerIn,
		boolean targetingController, CallbackInfoReturnable<Float> cir) {
		if (!(speedControllerIn instanceof SpeedControllerBlockEntity controller)) {
			cir.setReturnValue(0f);
			return;
		}
		float ratio = controller.targetSpeed.getValue() / DIAL_PER_RATIO;
		if (targetingController) {
			cir.setReturnValue(ratio * cogWheel.getTheoreticalSpeed());
			return;
		}
		cir.setReturnValue(ratio == 0 ? 0 : controller.getTheoreticalSpeed() / ratio);
	}

	/** A dial change rebuilds the physical constraint and referred inertia. */
	@Inject(method = "updateTargetRotation", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$logRatioChange(CallbackInfo ci) {
		SpeedControllerBlockEntity controller = (SpeedControllerBlockEntity) (Object) this;
		com.diliupengyun.differential_create.kinetics.NetworkTopology.mark(controller);
		ci.cancel();
		Level level = controller.getLevel();
		if (level == null || level.isClientSide)
			return;
		float input = 0;
		BlockEntity above = level.getBlockEntity(controller.getBlockPos().above());
		if (above instanceof KineticBlockEntity cog)
			input = cog.getSpeed();
		DifferentialCreate.LOGGER.debug("RSC at {} ratio changed to r={} (input {} RPM, output {} RPM)",
			controller.getBlockPos().toShortString(), controller.targetSpeed.getValue() / DIAL_PER_RATIO,
			String.format("%.2f", input), String.format("%.2f", controller.getSpeed()));
	}

}
