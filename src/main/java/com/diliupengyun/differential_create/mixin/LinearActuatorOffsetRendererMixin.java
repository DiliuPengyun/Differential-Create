package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LinearActuatorBlockEntity.class)
public abstract class LinearActuatorOffsetRendererMixin {
    @Inject(method = "getInterpolatedOffset", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$confirmedOffset(float partialTicks, CallbackInfoReturnable<Float> cir) {
        var self = (LinearActuatorBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide || self.movedContraption == null) return;
        var frame = ClientNetworkSpeeds.linearMotionOf(self, self.movedContraption.getId());
        if (frame != null) cir.setReturnValue((float) frame.angleAt(frame.tick() + (double) partialTicks));
    }
}
