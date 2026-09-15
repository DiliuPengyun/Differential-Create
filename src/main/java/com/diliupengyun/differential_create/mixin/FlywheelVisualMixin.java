package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import com.simibubi.create.content.kinetics.flywheel.FlywheelVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The instanced wheel body follows the shaft's shared phase, including at rest. */
@Mixin(FlywheelVisual.class)
public abstract class FlywheelVisualMixin extends KineticBlockEntityVisual<FlywheelBlockEntity> {
	@Shadow @Final
	protected TransformedInstance wheel;
	@Shadow @Final
	protected Matrix4f baseTransform;
	@Unique
	private float differentialcreate$lastNetworkAngle = Float.NaN;

	protected FlywheelVisualMixin(VisualizationContext context, FlywheelBlockEntity be, float partialTick) {
		super(context, be, partialTick);
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void differentialcreate$initializeWheelAngle(VisualizationContext context, FlywheelBlockEntity be,
		float partialTick, CallbackInfo ci) {
		differentialcreate$updateWheelAngle();
	}

	@Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$sharedWheelAngle(DynamicVisual.Context context, CallbackInfo ci) {
		differentialcreate$updateWheelAngle();
		ci.cancel();
	}

	@Unique
	private void differentialcreate$updateWheelAngle() {
		float angle = ClientKineticAngles.shaftAngleOf(blockEntity);
		if (angle == differentialcreate$lastNetworkAngle) return;
		wheel.setTransform(baseTransform)
			.rotateY(angle)
			.uncenter()
			.setChanged();
		differentialcreate$lastNetworkAngle = angle;
	}
}
