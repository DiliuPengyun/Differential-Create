package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.KineticsScenes;

import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The native panel demonstration changes the ratio instead of prescribing an output RPM. */
@Mixin(KineticsScenes.class)
public abstract class SpeedControllerSceneMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "speedController", at = @At("HEAD"))
    private static void differentialcreate$prepareInput(SceneBuilder builder, SceneBuildingUtil util,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        new CreateSceneBuilder(builder).world().multiplyKineticSpeed(util.select().everywhere(), .5f);
    }
    @WrapOperation(method = "speedController", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;multiplyKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V"))
    private static void differentialcreate$adjustRatio(CreateSceneBuilder.WorldInstructions world,
        Selection selection, float factor, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        builder.addInstruction(scene -> {
            for (var be : scene.getWorld().getBlockEntities()) {
                if (!(be instanceof SpeedControllerBlockEntity controller)) continue;
                // The first instruction initializes the original input-speed demonstration.
                // Later instructions multiply the top cog's speed: lower/top ratio is its inverse.
                int ratio = factor == .5f ? 16 : Math.clamp(Math.round(controller.targetSpeed.getValue() / factor), -256, 256);
                controller.targetSpeed.setValue(ratio);
            }
        });
        if (factor != .5f) original.call(world, selection, factor);
    }
}
