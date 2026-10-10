package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({AbstractMinecartContainer.class, MinecartFurnace.class})
public abstract class MinecartSpecialDragMixin {
    @Inject(method = "applyNaturalSlowdown", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$drag(CallbackInfo ci) {
        var cart = (AbstractMinecart) (Object) this;
        if (MinecartPhysics.enabled(cart)) { MinecartPhysics.slowdown(cart); ci.cancel(); }
    }
}
