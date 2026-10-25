package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.DrainScenes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the player's initial pour; the connected pipe must remove the fluid. */
@Mixin(DrainScenes.class)
public abstract class DrainSceneMixin {
    @WrapOperation(method = "emptying", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static <T extends BlockEntity> void differentialcreate$actualDrain(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Class<T> type, Consumer<T> action, Operation<Void> original) {}
}
