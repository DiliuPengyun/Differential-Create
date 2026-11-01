package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.DetectorScenes;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the length demonstration inside the lesson's camera framing. */
@Mixin(DetectorScenes.class)
public abstract class ThresholdSwitchSceneMixin {
    @WrapOperation(method = "thresholdSwitch", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;movePulley(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$visibleStroke(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        float distance, int ticks, Operation<Void> original) {
        original.call(world, pos, 3f, ticks);
    }
}
