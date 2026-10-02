package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.simibubi.create.content.fluids.FluidNetwork;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidNetwork.class)
public abstract class FluidNetworkMixin {
    @Shadow BlockFace start;
    @Shadow Level world;
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$oneTransportOwner(CallbackInfo ci) {
        if (FluidKinetics.manages(world, start.getPos())) ci.cancel();
    }
}
