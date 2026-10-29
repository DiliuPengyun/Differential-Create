package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LinearActuatorBlockEntity.class, MechanicalPistonBlockEntity.class, ElevatorPulleyBlockEntity.class})
public abstract class LinearActuatorRendererMixin {
    @Inject(method = "getMovementSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$confirmedTravel(CallbackInfoReturnable<Float> cir) {
        var self = (LinearActuatorBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide || self.movedContraption == null) return;
        if (ClientNetworkSpeeds.linearMotionOf(self, self.movedContraption.getId()) != null) cir.setReturnValue(0f);
    }
}
