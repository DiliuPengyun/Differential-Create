package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LinearActuatorBlockEntity.class, MechanicalPistonBlockEntity.class, ElevatorPulleyBlockEntity.class})
public abstract class LinearActuatorMotionMixin {
	@Inject(method = "getMovementSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$integratedStructureTravel(CallbackInfoReturnable<Float> cir) {
		var self = (LinearActuatorBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		Vec3 motion = ContraptionMotion.linear(self.movedContraption);
		if (motion != null) cir.setReturnValue((float) motion.dot(((LinearActuatorAccessor) self).differentialcreate$toMotionVector(1)));
	}
}
