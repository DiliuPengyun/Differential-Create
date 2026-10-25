package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.processing.basin.BasinBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BasinBlock.class)
public abstract class PonderBasinOutputMixin {
    @WrapOperation(method = "canOutputTo", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;get(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"))
    private static com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour differentialcreate$input(BlockGetter world,
        BlockPos target, com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType<?> type,
        Operation<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> original,
        BlockGetter ignored, BlockPos origin, Direction face) {
        return original.call(world, world instanceof Level level ? PhysicsPreview.relative(level, origin, target.subtract(origin)) : target, type);
    }
    @WrapOperation(method = "canOutputTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private static BlockEntity differentialcreate$inventory(BlockGetter world, BlockPos target, Operation<BlockEntity> original,
        BlockGetter ignored, BlockPos origin, Direction face) {
        return original.call(world, world instanceof Level level ? PhysicsPreview.relative(level, origin, target.subtract(origin)) : target);
    }
    @WrapOperation(method = "canOutputTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState differentialcreate$clearance(BlockGetter world, BlockPos target, Operation<BlockState> original,
        BlockGetter ignored, BlockPos origin, Direction face) {
        return original.call(world, world instanceof Level level ? PhysicsPreview.relative(level, origin, target.subtract(origin)) : target);
    }
}
