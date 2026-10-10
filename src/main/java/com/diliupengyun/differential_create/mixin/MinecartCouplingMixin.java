package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import com.simibubi.create.content.contraptions.minecart.CouplingPhysics;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CouplingPhysics.class)
public abstract class MinecartCouplingMixin {
    @Inject(method = "softCollisionStep", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$massReaction(Level level, Couple<AbstractMinecart> carts, double length, CallbackInfo ci) {
        if (!level.isClientSide) { MinecartPhysics.coupling(level, carts, length); ci.cancel(); }
    }
}
