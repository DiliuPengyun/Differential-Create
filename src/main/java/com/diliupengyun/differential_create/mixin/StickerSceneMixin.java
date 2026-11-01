package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.RedstoneScenes;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RedstoneScenes.class)
public abstract class StickerSceneMixin {
    @WrapOperation(method = "sticker", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateBearing(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$assemblyClick(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        float degrees, int duration, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        builder.overlay().showControls(util.vector().blockSurface(pos, Direction.NORTH), Pointing.DOWN, 25).rightClick();
        original.call(world, pos, degrees, duration);
    }

    @WrapOperation(method = "sticker", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;idle(I)V"))
    private static void differentialcreate$detachBeforeToggling(CreateSceneBuilder scene, int ticks,
        Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        if (ticks != 90) { original.call(scene, ticks); return; }
        var bearingPos = new BlockPos(2, 1, 2);
        original.call(scene, 80);
        scene.world().setKineticSpeed(util.select().position(bearingPos), 0);
        // Removing power lets the bearing coast; it is the explicit right-click
        // below that disassembles the structure and stops its residual motion.
        original.call(scene, 10);
        scene.overlay().showControls(util.vector().blockSurface(bearingPos, Direction.NORTH), Pointing.DOWN, 25).rightClick();
        original.call(scene, 7);
        scene.addInstruction(s -> {
            var simulation = PonderSimulation.of(s.getWorld());
            s.forEachVisible(WorldSectionElement.class, section -> {
                var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
                if (selection == null || (!selection.test(new BlockPos(2, 2, 1)) && !selection.test(new BlockPos(2, 2, 2)))) return;
                double angle = Math.round(section.getAnimatedRotation().y / 90) * 90;
                simulation.release(section);
                section.setAnimatedRotation(new Vec3(0, angle, 0), true);
                if (s.getWorld().getBlockEntity(bearingPos) instanceof IBearingBlockEntity bearing) bearing.setAngle((float) angle);
            });
        });
        original.call(scene, 18);
    }
}
