package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Recipe selection uses the same inventories and filters as a placed machine. */
@Mixin(BasinOperatingBlockEntity.class)
public abstract class PonderBasinOperatorMixin {
    @WrapOperation(method = "updateBasin", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$recipes(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
    @WrapOperation(method = "getBasin", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity differentialcreate$contact(Level world, BlockPos pos, Operation<BlockEntity> original) {
        var origin = ((BlockEntity) (Object) this).getBlockPos();
        return original.call(world, PhysicsPreview.relative(world, origin, pos.subtract(origin)));
    }
}
