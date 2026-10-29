package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.*;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.ElevatorScenes;
import com.simibubi.create.infrastructure.ponder.scenes.PulleyScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({PulleyScenes.class, ElevatorScenes.class})
public abstract class MultiPulleySceneMixin {
    @WrapOperation(method = "multiRope", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;movePulley(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$input(CreateSceneBuilder.WorldInstructions world, BlockPos pos, float distance, int ticks,
        Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder, @Local(argsOnly = true) SceneBuildingUtil util,
        @Share("stroke") LocalBooleanRef stroke, @Share("strokeCount") LocalIntRef strokeCount,
        @Share("extraTicks") LocalIntRef extraTicks) {
        if (pos.equals(PonderLiftScenes.MULTI_PRIMARY)) {
            // A player turns the crank back for every upward stroke. The
            // elevator instead selects a floor with an unchanged input drive.
            builder.addInstruction(scene -> {
                boolean elevator = scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
                var input = elevator ? util.select().position(pos) : util.select().fromTo(3, 4, 0, 3, 4, 1);
                PonderKinetics.of(scene.getWorld()).command(scene.getWorld(), input,
                    speed -> elevator ? 64f : Math.copySign(32f, distance));
            });
            stroke.set(ticks > 0);
            if (ticks > 0) {
                strokeCount.set(strokeCount.get() + 1);
                boolean rope = builder.getScene().getId().getPath().equals("rope_pulley_multi_rope");
                extraTicks.set(!rope ? 35 : distance < 0 ? 200 : strokeCount.get() == 1 ? 90 : 35);
            }
        }
        original.call(world, pos, distance, ticks);
    }
    @WrapOperation(method = "multiRope", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;moveSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/world/phys/Vec3;I)V"))
    private static void differentialcreate$payload(CreateSceneBuilder.WorldInstructions world, ElementLink<WorldSectionElement> link,
        Vec3 delta, int ticks, Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder) {
        PonderLiftScenes.multiAttach(builder, link);
    }
    @WrapOperation(method = "multiRope", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;idle(I)V"))
    private static void differentialcreate$arrival(CreateSceneBuilder builder, int ticks, Operation<Void> original,
        @Share("stroke") LocalBooleanRef stroke, @Share("extraTicks") LocalIntRef extraTicks) {
        if (!stroke.get()) { original.call(builder, ticks); return; }
        stroke.set(false);
        // The hand crank has a finite torque budget and the loaded return
        // stroke is slower than the native twenty-tick animation.
        // Reserve the measured return stroke without adding its long delay to
        // gravity-assisted descents or the initial unloaded rope deployment.
        original.call(builder, ticks + extraTicks.get());
        builder.addInstruction(new PonderAwait(scene -> PonderSimulation.of(scene.getWorld()).pulleysArrived(), 600));
    }
}
