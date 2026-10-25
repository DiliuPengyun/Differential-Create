package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.PulleyScenes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PulleyScenes.class)
public abstract class PulleySceneMixin {
    @WrapOperation(method = {"movement", "movementModes"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;movePulley(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$stroke(CreateSceneBuilder.WorldInstructions world, net.minecraft.core.BlockPos pos,
        float distance, int ticks, Operation<Void> original, @Share("stroke") LocalBooleanRef stroke) {
        original.call(world, pos, distance, ticks);
        stroke.set(ticks > 0);
    }
    @WrapOperation(method = {"movement", "movementModes"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;idle(I)V"))
    private static void differentialcreate$finishStroke(CreateSceneBuilder builder, int ticks, Operation<Void> original,
        @Share("stroke") LocalBooleanRef stroke) {
        if (!stroke.get()) { original.call(builder, ticks); return; }
        stroke.set(false);
        // Reserve the slower loaded stroke in the timeline, then check the actual endpoint.
        original.call(builder, ticks + 35);
        builder.addInstruction(new PonderAwait(scene -> PonderSimulation.of(scene.getWorld()).pulleysArrived(), 600));
    }
}
