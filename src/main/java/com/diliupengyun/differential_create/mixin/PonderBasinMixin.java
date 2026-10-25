package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BasinBlockEntity.class)
public abstract class PonderBasinMixin {
    // Keep tick's client particles, but also run the actual output queue.
    @WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD, ordinal = 1))
    private boolean differentialcreate$output(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
    @WrapOperation(method = "lazyTick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$inventory(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
    @WrapOperation(method = {"getOperator", "tryClearingSpoutputOverflow", "acceptOutputsInner"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity differentialcreate$contact(Level world, BlockPos pos, Operation<BlockEntity> original) {
        var origin = ((BlockEntity) (Object) this).getBlockPos();
        return original.call(world, PhysicsPreview.relative(world, origin, pos.subtract(origin)));
    }
}
