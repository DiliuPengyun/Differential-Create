package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** The native clutch layout, with separate power, coast and empty-hand operations. */
public final class PonderBearingModes {
    private PonderBearingModes() {}

    public static void build(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder);
        scene.title("bearing_modes", "Assembling and Disassembling the Mechanical Bearing");
        scene.configureBasePlate(1, 1, 6);
        scene.setSceneOffsetY(-1);
        var column = util.select().fromTo(6, 1, 3, 6, 4, 3);
        var clutch = util.select().fromTo(5, 3, 1, 5, 4, 2);
        var bearingPos = new BlockPos(5, 2, 2);
        var leverPos = new BlockPos(5, 3, 1);
        scene.world().setKineticSpeed(util.select().position(7, 0, 3), 4);
        scene.world().setKineticSpeed(column, -4);
        scene.world().setKineticSpeed(clutch, 8);
        scene.world().toggleRedstonePower(clutch);
        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(5);
        scene.world().showSection(column, Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(clutch, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(bearingPos), Direction.UP);
        scene.idle(10);
        var arm = scene.world().showIndependentSection(util.select().fromTo(5, 1, 2, 2, 1, 2), Direction.EAST);
        scene.world().configureCenterOfRotation(arm, util.vector().centerOf(bearingPos));
        scene.idle(20);

        click(scene, util, bearingPos);
        caption(scene, util, bearingPos, "bearing_assemble", 60);
        scene.idle(7);
        assemble(scene, bearingPos, arm);
        scene.idle(60);
        scene.world().toggleRedstonePower(clutch);
        scene.effects().indicateRedstone(leverPos);
        caption(scene, util, leverPos, "bearing_power", 70);
        scene.idle(80);

        scene.world().toggleRedstonePower(clutch);
        scene.effects().indicateRedstone(leverPos);
        caption(scene, util, leverPos, "bearing_coast", 80);
        scene.idle(90);
        click(scene, util, bearingPos);
        caption(scene, util, bearingPos, "bearing_disassemble", 80);
        scene.idle(7);
        scene.addInstruction(s -> {
            var section = s.resolve(arm);
            double angle = Math.round(section.getAnimatedRotation().y / 90) * 90;
            PonderSimulation.of(s.getWorld()).release(section);
            section.setAnimatedRotation(new Vec3(0, angle, 0), true);
            if (s.getWorld().getBlockEntity(bearingPos) instanceof IBearingBlockEntity bearing) bearing.setAngle((float) angle);
        });
        scene.idle(85);

        click(scene, util, bearingPos);
        caption(scene, util, bearingPos, "bearing_reassemble", 70);
        scene.idle(7);
        assemble(scene, bearingPos, arm);
        scene.idle(70);
        scene.world().modifyKineticSpeed(util.select().everywhere(), f -> -f);
        scene.world().toggleRedstonePower(clutch);
        scene.effects().indicateRedstone(leverPos);
        caption(scene, util, leverPos, "bearing_power", 70);
        scene.idle(80);
        scene.world().toggleRedstonePower(clutch);
        scene.effects().indicateRedstone(leverPos);
        caption(scene, util, leverPos, "bearing_coast", 100);
        scene.idle(110);
        scene.markAsFinished();
    }
    private static void assemble(CreateSceneBuilder scene, BlockPos pos,
        net.createmod.ponder.api.element.ElementLink<net.createmod.ponder.api.element.WorldSectionElement> arm) {
        scene.addInstruction(s -> {
            if (s.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity bearing)
                PonderSimulation.of(s.getWorld()).attach(s.resolve(arm), bearing, true, 1, 1);
        });
    }
    private static void click(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos) {
        scene.overlay().showControls(util.vector().blockSurface(pos, Direction.NORTH), Pointing.DOWN, 30).rightClick();
    }
    private static void caption(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, String key, int ticks) {
        scene.overlay().showText(ticks).pointAt(util.vector().blockSurface(pos, Direction.NORTH))
            .placeNearTarget().attachKeyFrame().sharedText(ResourceLocation.fromNamespaceAndPath("differentialcreate", key));
    }
}
