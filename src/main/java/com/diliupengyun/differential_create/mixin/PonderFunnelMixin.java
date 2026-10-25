package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FunnelBlockEntity.class)
public abstract class PonderFunnelMixin {
    @WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$transfer(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
}
