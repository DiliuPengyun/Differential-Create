package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.FluidTankScenes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FluidTankScenes.class)
public abstract class FluidTankSceneMixin {
    // The real filling callback now owns the splash and its timing.
    @WrapOperation(method = "storage", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$EffectInstructions;emitParticles(Lnet/minecraft/world/phys/Vec3;Lnet/createmod/ponder/api/ParticleEmitter;FI)V"))
    private static void differentialcreate$actualParticles(CreateSceneBuilder.EffectInstructions effects, net.minecraft.world.phys.Vec3 pos,
        net.createmod.ponder.api.ParticleEmitter emitter, float count, int duration, Operation<Void> original) {}
    @WrapOperation(method = "storage", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBeltLike(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V", ordinal = 1))
    private static void differentialcreate$filledBucket(CreateSceneBuilder.WorldInstructions world, BlockPos pos, net.minecraft.core.Direction side,
        net.minecraft.world.item.ItemStack stack, Operation<Void> original) {}
    @WrapOperation(method = "storage", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;removeItemsFromBelt(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$keepBucket(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = "storage", at = {
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V", ordinal = 2),
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V", ordinal = 3)})
    private static <T extends BlockEntity> void differentialcreate$level(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Class<T> type, Consumer<T> script, Operation<Void> original) {}
}
