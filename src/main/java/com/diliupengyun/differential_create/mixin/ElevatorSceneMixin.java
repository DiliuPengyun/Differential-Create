package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.*;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.ElevatorScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ElevatorScenes.class)
public abstract class ElevatorSceneMixin {
    @WrapOperation(method = "elevator", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;moveSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/world/phys/Vec3;I)V"))
    private static void differentialcreate$frame(CreateSceneBuilder.WorldInstructions world, ElementLink<WorldSectionElement> link,
        Vec3 delta, int ticks, Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder) {
        if (ticks == 0) { original.call(world, link, delta, ticks); return; }
        if (Math.abs(delta.y) == 6 || Math.abs(delta.y) == 12) {
            builder.addInstruction(scene -> PonderLiftScenes.elevatorAssembly(scene, true));
            return;
        }
        // Returning from the middle floor originally combines +6 cabin travel
        // with -7 diagram travel. The rope solver supplies that +6 itself.
        PonderLiftScenes.presentation(builder, link, delta.y == -1 ? new Vec3(0, -7, 0) : delta, ticks);
    }
    @WrapOperation(method = "elevator", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;movePulley(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$floor(CreateSceneBuilder.WorldInstructions world, BlockPos pos, float distance, int ticks,
        Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder, @Share("remaining") LocalIntRef remaining) {
        if (ticks == 0 && distance < 0) builder.addInstruction(scene -> PonderLiftScenes.elevatorAssembly(scene, false));
        original.call(world, pos, distance, ticks);
        if (ticks == 0 && distance > 0) builder.addInstruction(scene -> PonderLiftScenes.elevatorAssembly(scene, true));
        remaining.set(ticks);
    }
    @WrapOperation(method = "elevator", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;idle(I)V"))
    private static void differentialcreate$arrival(CreateSceneBuilder builder, int ticks, Operation<Void> original,
        @Share("remaining") LocalIntRef remaining) {
        boolean arrival = remaining.get() > 0 && ticks >= remaining.get();
        remaining.set(Math.max(0, remaining.get() - ticks));
        original.call(builder, ticks + (arrival ? 35 : 0));
        if (arrival) builder.addInstruction(new PonderAwait(scene -> PonderSimulation.of(scene.getWorld()).pulleysArrived(), 1200));
    }
}
