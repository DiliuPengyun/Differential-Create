package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.GantryScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GantryScenes.class)
public abstract class GantrySceneMixin {
    @WrapOperation(method = "subgantry", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;moveSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/world/phys/Vec3;I)V", ordinal = 2))
    private static void differentialcreate$raiseBothCarriages(CreateSceneBuilder.WorldInstructions world,
        ElementLink<WorldSectionElement> section, Vec3 delta, int duration, Operation<Void> original,
        @Local(argsOnly = true) SceneBuildingUtil util) {
        // The native storyboard prescribes an upward animation while its input
        // still turns downward from the previous stroke. Reverse the real drive.
        world.modifyKineticSpeed(util.select().everywhere(), speed -> -speed);
        original.call(world, section, delta, duration);
    }

    @WrapOperation(method = "subgantry", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;Ljava/util/function/UnaryOperator;)V", ordinal = 2))
    private static void differentialcreate$driveHorizontalStage(CreateSceneBuilder.WorldInstructions world,
        Selection selection, java.util.function.UnaryOperator<Float> operation, Operation<Void> original,
        @Local(argsOnly = true) SceneBuildingUtil util) {
        // Locking the vertical screw couples its rotation into the carriage.
        // Command its actual input; the previously unpowered horizontal shafts
        // cannot retroactively command their newly connected source.
        world.setKineticSpeed(util.select().column(5, 3), 32);
        original.call(world, selection, operation);
    }

    @Inject(method = "subgantry", at = @At("RETURN"))
    private static void differentialcreate$finishCascadedTravel(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        builder.idle(80);
        builder.addInstruction(new PonderAwait(scene -> PonderSimulation.of(scene.getWorld())
            .point(new BlockPos(3, 2, 2)).x < .001, 240));
    }

    @WrapOperation(method = "intro", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;markAsFinished()V"))
    private static void differentialcreate$finishAfterTravel(CreateSceneBuilder scene, Operation<Void> original) {
        // The native scene marks completion only 20 ticks into a 60-tick move.
        // Keep that travel in the visible timeline, then check the actual load.
        scene.idle(40);
        scene.addInstruction(new PonderAwait(ponder -> Math.abs(PonderSimulation.of(ponder.getWorld())
            .point(new BlockPos(4, 2, 2)).x) < .001, 240));
        original.call(scene);
    }

    @WrapOperation(method = "redstone", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;showSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)V", ordinal = 2))
    private static void differentialcreate$outputAtSettledCarriage(CreateSceneBuilder.WorldInstructions world,
        Selection selection, Direction direction, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        var output = world.showIndependentSection(selection, direction);
        builder.addInstruction(scene -> {
            var carriage = PonderSimulation.of(scene.getWorld()).point(new BlockPos(4, 2, 2));
            scene.resolve(output).setAnimatedOffset(carriage.subtract(new Vec3(1, 2, 2)), true);
        });
    }
}
