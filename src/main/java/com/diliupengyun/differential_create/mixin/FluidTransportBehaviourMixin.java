package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidTransportBehaviour.class)
public abstract class FluidTransportBehaviourMixin {
    @Inject(method = "initialize", at = @At("RETURN"))
    private void differentialcreate$restorePressureSources(CallbackInfo ci) {
        var self = (FluidTransportBehaviour) (Object) this;
        if (self.getWorld().isClientSide) return;
        // Saved pressures do not contain live pump references. A pipe may load
        // while its pump stays loaded, so a pump's first speed notification is insufficient.
        FluidPropagator.propagateChangedPipe(self.getWorld(), self.getPos(), self.blockEntity.getBlockState());
    }
}
