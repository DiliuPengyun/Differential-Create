package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.SteamBoilerWater;
import com.simibubi.create.content.fluids.tank.BoilerData;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BoilerData.BoilerFluidHandler.class)
public abstract class BoilerFluidHandlerMixin {
    @Shadow(remap = false) @Final private BoilerData this$0;
    @Inject(method = "fill", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$paidCylinderFeed(FluidStack resource, FluidAction action, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(SteamBoilerWater.fill(this$0, resource, action.execute()));
    }
}
