package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
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

	@Inject(method = "setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction$Axis;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;",
		at = @At("RETURN"))
	private void differentialcreate$continuousAngle(KineticBlockEntity blockEntity, Axis axis, float speed,
		CallbackInfoReturnable<RotatingInstance> cir) {
		ClientKineticAngles.apply(cir.getReturnValue(), blockEntity, axis, speed);
	}

}
