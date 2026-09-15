package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.client.InstanceRotation;
import com.diliupengyun.differential_create.client.PortRotation;
import com.diliupengyun.differential_create.client.RotationFrame;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatingInstance;

import net.minecraft.core.Direction.Axis;

/**
 * Re-anchors rotating instances to the network's accumulated angle so
 * connected blocks stay angle-aligned and continuous through speed changes
 * (see ClientKineticAngles). The terminal setup overload funnels every
 * flywheel visual (shafts, cogwheels, water wheels, gearboxes, belt pulleys,
 * machines) through here.
 */
@Mixin(RotatingInstance.class)
public abstract class RotatingInstanceMixin {
	@Unique
	private PortRotation differentialcreate$portRotation = new PortRotation();
	@Unique
	private KineticBlockEntity differentialcreate$phaseOwner;
	@Unique
	private final InstanceRotation differentialcreate$instanceRotation = new InstanceRotation();

	@Inject(method = "setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction$Axis;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;",
		at = @At("HEAD"))
	private void differentialcreate$beginSetup(KineticBlockEntity blockEntity, Axis axis, float speed,
		CallbackInfoReturnable<RotatingInstance> cir) {
		differentialcreate$instanceRotation.beginSetup();
	}

	@ModifyVariable(method = "setRotationOffset", at = @At("HEAD"), argsOnly = true)
	private float differentialcreate$preservePhase(float modelOffset) {
		return differentialcreate$instanceRotation.setModelOffset(modelOffset);
	}

	@Inject(method = "setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction$Axis;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;",
		at = @At("RETURN"))
	private void differentialcreate$continuousAngle(KineticBlockEntity blockEntity, Axis axis, float speed,
		CallbackInfoReturnable<RotatingInstance> cir) {
		if (differentialcreate$phaseOwner != blockEntity) {
			differentialcreate$phaseOwner = blockEntity;
			differentialcreate$portRotation = new PortRotation();
		}
		RotationFrame frame = ClientKineticAngles.rotationOf(blockEntity, speed, differentialcreate$portRotation);
		RotatingInstance instance = cir.getReturnValue();
		if (frame != null) instance.setRotationalSpeed(frame.shaderSpeed());
		// This is the final shader coefficient, not a model alignment passed to
		// setRotationOffset. Later model setters retain this phase compensation.
		instance.rotationOffset = differentialcreate$instanceRotation.finishSetup(frame);
	}

}
