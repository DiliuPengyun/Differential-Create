package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.diliupengyun.differential_create.client.ponder.PonderBearingTurn;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.MovementActorScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the native control and filter demonstrations at their intended physical poses. */
@Mixin(MovementActorScenes.class)
public abstract class ContraptionControlsSceneMixin {
    @WrapOperation(method = "contraptionControls", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateBearing(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$finiteTurn(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        float degrees, int duration, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        builder.addInstruction(new PonderBearingTurn(pos, degrees, duration, false));
    }

    @WrapOperation(method = "contraptionControls", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateSection(Lnet/createmod/ponder/api/element/ElementLink;DDDI)V"))
    private static void differentialcreate$settleBeforeControls(CreateSceneBuilder.WorldInstructions world,
        ElementLink<WorldSectionElement> link, double x, double y, double z, int duration, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        var bearing = new BlockPos(5, 1, 4);
        builder.addInstruction(scene -> {
            var section = scene.resolve(link);
            if (section != null && scene.getWorld().getBlockEntity(bearing) instanceof KineticBlockEntity be)
                PonderSimulation.of(scene.getWorld()).attach(section, be, true, 1, 1);
        });
        // Finite acceleration and braking need time before fixed-position click
        // cues or the next assembly. The tools retain their current control state.
        builder.idle(duration + 40);
        builder.addInstruction(new PonderAwait(scene -> PonderBearingTurn.parked(scene, bearing), 300));
    }
}
