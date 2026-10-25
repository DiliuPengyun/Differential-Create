package com.diliupengyun.differential_create.mixin;

import net.createmod.ponder.foundation.element.MinecartElementImpl;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecartElementImpl.class)
public interface PonderMinecartAccessor {
    @Accessor("entity") AbstractMinecart differentialcreate$cart();
    @Accessor("location") Vec3 differentialcreate$origin();
}
