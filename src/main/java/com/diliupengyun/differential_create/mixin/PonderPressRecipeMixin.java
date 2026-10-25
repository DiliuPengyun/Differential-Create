package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PressingBehaviour.class)
public abstract class PonderPressRecipeMixin {
    @WrapOperation(method = {"tick", "applyOnBasin", "applyInWorld"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$recipe(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
}
