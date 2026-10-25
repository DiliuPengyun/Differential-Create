package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({HarvesterMovementBehaviour.class, BlockBreakingMovementBehaviour.class, com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour.class})
public abstract class PonderActorBehaviourMixin {
    @WrapOperation(method = "visitNewPosition", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$actualContact(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
}
