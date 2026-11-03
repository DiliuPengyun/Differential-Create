package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.instruction.ShowInputInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Native layout and six captions, with continuous bearing input and real actor selection. */
public final class PonderControlsScene {
    private static final BlockPos BEARING = new BlockPos(5, 1, 4), STONE = new BlockPos(3, 1, 2);
    private static final BlockPos WHEAT = new BlockPos(2, 1, 1), CONTROL = new BlockPos(1, 2, 5), FILTERED = new BlockPos(2, 2, 5);
    private PonderControlsScene() {}

    public static void build(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder);
        scene.title("contraption_controls", "Using Contraption Controls");
        scene.configureBasePlate(1, 0, 6);
        scene.scaleSceneView(.8f);
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).useExplicitSources());
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().position(WHEAT).add(util.select().position(STONE)), Direction.UP);
        scene.idle(10);
        scene.world().showSection(util.select().position(BEARING), Direction.DOWN);
        scene.idle(5);
        var arm = arm(scene, util, false);
        scene.idle(15);
        start(scene, arm);
        scene.overlay().showText(100).independent(20).attachKeyFrame()
            .text("Actors on moving contraptions are always active by default");
        passTargets(scene, arm);
        hide(scene, arm);

        restore(scene, util);
        arm = arm(scene, util, true);
        scene.idle(20);
        scene.effects().superGlue(CONTROL, Direction.DOWN, true);
        scene.overlay().showText(70).pointAt(util.vector().topOf(CONTROL)).placeNearTarget().attachKeyFrame()
            .text("Contraption Controls can be used to toggle them on the fly");
        scene.idle(75);
        start(scene, arm);
        scene.idle(8);
        click(scene, arm, CONTROL);
        scene.overlay().showText(70).independent(20).attachKeyFrame()
            .sharedText(ResourceLocation.fromNamespaceAndPath("differentialcreate", "controls_keep_moving"));
        scene.idle(90);
        var moving = arm;
        scene.addInstruction(new PonderAwait(s -> s.resolve(moving).getAnimatedRotation().y < -95, 300));
        click(scene, arm, CONTROL);
        scene.idle(5);
        hide(scene, arm);

        restore(scene, util);
        arm = arm(scene, util, true);
        scene.world().showSectionAndMerge(util.select().position(FILTERED), Direction.DOWN, arm);
        scene.idle(20);
        scene.effects().superGlue(FILTERED, Direction.DOWN, true);
        scene.overlay().showText(60).pointAt(util.vector().topOf(FILTERED)).placeNearTarget().attachKeyFrame()
            .text("They can be attached anywhere on the contraption");
        scene.idle(65);
        scene.overlay().showControls(util.vector().topOf(FILTERED), Pointing.DOWN, 30).rightClick().withItem(AllBlocks.MECHANICAL_DRILL.asStack());
        scene.overlay().showControls(util.vector().centerOf(CONTROL), Pointing.UP, 30).rightClick().withItem(AllBlocks.MECHANICAL_HARVESTER.asStack());
        scene.idle(7);
        scene.world().setFilterData(util.select().position(FILTERED), ContraptionControlsBlockEntity.class, AllBlocks.MECHANICAL_DRILL.asStack());
        scene.world().setFilterData(util.select().position(CONTROL), ContraptionControlsBlockEntity.class, AllBlocks.MECHANICAL_HARVESTER.asStack());
        scene.overlay().showText(90).independent(20).attachKeyFrame()
            .text("While disassembled, the filter can be changed to target specific types of actors");
        scene.idle(95);
        scene.overlay().showControls(util.vector().topOf(FILTERED), Pointing.DOWN, 25).rightClick();
        scene.idle(7);
        scene.world().modifyBlockEntity(FILTERED, ContraptionControlsBlockEntity.class, control -> control.disabled = true);
        scene.effects().indicateRedstone(FILTERED);
        scene.idle(20);
        start(scene, arm);
        passTargets(scene, arm);
        hide(scene, arm);

        restore(scene, util);
        arm = arm(scene, util, true);
        scene.world().showSectionAndMerge(util.select().position(FILTERED), Direction.DOWN, arm);
        var lever = util.select().fromTo(0, 0, 5, 0, 2, 5);
        scene.world().showSection(lever, Direction.EAST);
        scene.idle(20);
        scene.overlay().showText(50).independent(20).attachKeyFrame()
            .text("If it is redstone-activated during assembly...");
        scene.idle(30);
        scene.world().toggleRedstonePower(lever);
        scene.effects().indicateRedstone(new BlockPos(0, 2, 5));
        scene.world().modifyBlockEntity(CONTROL, ContraptionControlsBlockEntity.class, control -> control.disabled = true);
        scene.idle(30);
        start(scene, arm);
        scene.overlay().showText(90).independent(20).attachKeyFrame()
            .text("...targeted actors will be turned off from the start");
        passTargets(scene, arm);
        scene.markAsFinished();
    }

    private static ElementLink<WorldSectionElement> arm(CreateSceneBuilder scene, SceneBuildingUtil util, boolean controls) {
        var selection = util.select().fromTo(5, 2, 4, 3, 2, 5).add(util.select().fromTo(3, 1, 5, 1, 1, 4));
        var arm = scene.world().showIndependentSection(selection, Direction.DOWN);
        scene.world().configureCenterOfRotation(arm, util.vector().centerOf(BEARING));
        if (controls) scene.world().showSectionAndMerge(util.select().position(CONTROL), Direction.DOWN, arm);
        return arm;
    }
    private static void restore(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().restoreBlocks(util.select().position(STONE).add(util.select().position(WHEAT)));
        for (var button : java.util.List.of(CONTROL, FILTERED))
            scene.world().modifyBlockEntity(button, ContraptionControlsBlockEntity.class, control -> control.disabled = false);
        scene.idle(5);
    }
    private static void start(CreateSceneBuilder scene, ElementLink<WorldSectionElement> arm) {
        scene.addInstruction(s -> {
            var bearing = (KineticBlockEntity)s.getWorld().getBlockEntity(BEARING);
            PonderSimulation.of(s.getWorld()).attach(s.resolve(arm), bearing, true, 1, 1);
            PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), BEARING, -2.5f);
        });
    }
    private static void passTargets(CreateSceneBuilder scene, ElementLink<WorldSectionElement> arm) {
        scene.idle(150);
        scene.addInstruction(new PonderAwait(s -> s.resolve(arm).getAnimatedRotation().y < -105, 300));
    }
    private static void click(CreateSceneBuilder scene, ElementLink<WorldSectionElement> arm, BlockPos button) {
        scene.addInstruction(new ShowInputInstruction(new PonderAttachedControls(arm, button), 30));
        scene.idle(7);
        scene.addInstruction(s -> {
            var world = s.getWorld();
            PonderSimulation.of(world).toggleActors((KineticBlockEntity)world.getBlockEntity(BEARING), button);
            ((ContraptionControlsBlockEntity)world.getBlockEntity(button)).pressButton();
        });
        scene.idle(25);
    }
    private static void hide(CreateSceneBuilder scene, ElementLink<WorldSectionElement> arm) {
        scene.world().hideIndependentSection(arm, Direction.UP);
        scene.idle(15);
        scene.addInstruction(s -> PonderSimulation.of(s.getWorld()).release(s.resolve(arm)));
        scene.idle(5);
    }
}
