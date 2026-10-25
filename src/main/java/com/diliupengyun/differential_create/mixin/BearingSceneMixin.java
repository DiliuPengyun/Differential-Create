package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.BearingScenes;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the native stages and timing; assembly and disassembly now require an empty-hand click. */
@Mixin(BearingScenes.class)
public abstract class BearingSceneMixin {
    @WrapOperation(method = "windmillsAsSource", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateSection(Lnet/createmod/ponder/api/element/ElementLink;DDDI)V"))
    private static void differentialcreate$disassembleWindmill(CreateSceneBuilder.WorldInstructions world,
        ElementLink<WorldSectionElement> section, double x, double y, double z, int duration, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        if (duration != 0) { original.call(world, section, x, y, z, duration); return; }
        // A right-click disassembles the windmill back into grid-aligned blocks.
        // The old -45 degree correction only matched the native scripted angle.
        builder.addInstruction(scene -> {
            var structure = scene.resolve(section);
            if (structure == null) return;
            com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).release(structure);
            structure.setAnimatedRotation(Vec3.ZERO, true);
            if (scene.getWorld().getBlockEntity(util.grid().at(3, 2, 1))
                instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity bearing) bearing.setAngle(0);
        });
    }

    @Inject(method = "mechanicalBearing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateBearing(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$assemble(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        builder.overlay().showControls(util.vector().blockSurface(util.grid().at(3, 2, 3), Direction.WEST),
            Pointing.DOWN, 40).rightClick();
    }

    @Inject(method = "mechanicalBearing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;hideIndependentSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$disassemble(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        builder.overlay().showControls(util.vector().blockSurface(util.grid().at(3, 2, 3), Direction.WEST),
            Pointing.DOWN, 30).rightClick();
    }

    @Inject(method = "bearingModes", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$manualModes(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        com.diliupengyun.differential_create.client.ponder.PonderBearingModes.build(builder, util);
        ci.cancel();
    }
}
