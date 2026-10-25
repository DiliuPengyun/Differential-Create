package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only opted-in tutorials run the real intake and recipe callbacks in their virtual world. */
@Mixin(CrushingWheelControllerBlockEntity.class)
public abstract class PonderCrushingMixin {
    @WrapOperation(method = "tick", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$virtualIntake(Level world, Operation<Boolean> original) {
        return original.call(world) && !(world instanceof PonderLevel);
    }
}
