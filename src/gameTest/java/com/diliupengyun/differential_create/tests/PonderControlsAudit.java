package com.diliupengyun.differential_create.tests;

import java.util.IdentityHashMap;
import java.util.Map;
import com.diliupengyun.differential_create.client.ponder.PonderAttachedControls;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;

/** Controls change actual tool results without parking their carrier or detaching the click cues. */
final class PonderControlsAudit {
    private static final BlockPos ARM = new BlockPos(5, 2, 4), CONTROL = new BlockPos(1, 2, 5);
    private static final BlockPos FILTERED = new BlockPos(2, 2, 5), WHEAT = new BlockPos(2, 1, 1);
    private static final BlockPos STONE = new BlockPos(3, 1, 2), HARVESTER = new BlockPos(1, 1, 4);
    private final Map<WorldSectionElement, Integer> assemblies = new IdentityHashMap<>();
    private final boolean[] cutStone = new boolean[4], cutWheat = new boolean[4];
    private final int[] moving = new int[4];
    private int assembly = -1, disabledSamples, filteredSamples, redstoneSamples, hintSamples;
    private boolean previousDisabled, switchedOff, switchedOn;
    private double lastAngle;

    void sample(PonderScene scene) {
        if (!scene.getId().getPath().equals("contraption_controls")) return;
        WorldSectionElement[] current = {null};
        scene.forEachVisible(WorldSectionElement.class, section -> {
            if (!((PonderWorldSectionAccessor) section).differentialcreate$selection().test(ARM)) return;
            assemblies.computeIfAbsent(section, ignored -> assemblies.size());
            if (current[0] == null || assemblies.get(section) > assemblies.get(current[0])) current[0] = section;
        });
        var section = current[0];
        if (section == null) return;
        int next = assemblies.get(section);
        require(next < 4, "unexpected extra control assembly");
        if (next != assembly) {
            if (assembly >= 0) require(lastAngle < -95, "preceding demonstration did not carry its tools through the targets");
            assembly = next; previousDisabled = false;
            require(section.getAnimatedRotation().length() < .03, "new assembly inherited the preceding rotation");
        }
        lastAngle = section.getAnimatedRotation().y;
        double previous = ((PonderWorldSectionAccessor)section).differentialcreate$previousRotation().y;
        double step = lastAngle - previous;
        if (lastAngle < -3) {
            require(step < -1e-4, "continuous input was replaced by a scripted stop: assembly=" + assembly + " angle=" + lastAngle);
            moving[assembly]++;
        }
        var world = scene.getWorld();
        boolean stone = world.getBlockState(STONE).is(Blocks.COBBLESTONE);
        var crop = world.getBlockState(WHEAT);
        boolean mature = crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) == 7;
        cutStone[assembly] |= !stone;
        cutWheat[assembly] |= !mature;
        var control = (ContraptionControlsBlockEntity) world.getBlockEntity(CONTROL);
        var filtered = (ContraptionControlsBlockEntity) world.getBlockEntity(FILTERED);
        if (assembly == 1 && control != null) {
            if (control.disabled != previousDisabled) {
                require(step < -.01, "the control operation parked its carrier");
                if (control.disabled) switchedOff = true; else switchedOn = true;
            }
            if (control.disabled) {
                require(stone && mature, "disabled tools still changed their targets");
                stoppedTool(scene, new BlockPos(2, 1, 4)); stoppedTool(scene, HARVESTER);
                require(((HarvesterBlockEntity) world.getBlockEntity(HARVESTER)).getAnimatedSpeed() == 0,
                    "disabled harvester keeps spinning in the displayed structure");
                if (lastAngle < -20 && lastAngle > -70) disabledSamples++;
            }
        }
        if (assembly == 2 && filtered != null && filtered.disabled && lastAngle < -3) {
            require(stone, "drill-only filter did not preserve the stone");
            stoppedTool(scene, new BlockPos(2, 1, 4));
            if (lastAngle < -20 && lastAngle > -70) filteredSamples++;
        }
        if (assembly == 3 && control != null && control.disabled && lastAngle < -3) {
            require(mature, "initial redstone disable did not preserve the crop");
            stoppedTool(scene, HARVESTER);
            require(((HarvesterBlockEntity) world.getBlockEntity(HARVESTER)).getAnimatedSpeed() == 0,
                "redstone-disabled harvester still has an active animation");
            if (lastAngle < -20 && lastAngle > -70) redstoneSamples++;
        }
        scene.forEachVisible(PonderAttachedControls.class, hint -> {
            require(step < -.01, "moving click cue only demonstrated a parked button");
            for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
                var pose = new com.mojang.blaze3d.vertex.PoseStack();
                ((net.createmod.ponder.foundation.element.WorldSectionElementImpl)section).transformMS(pose, partial);
                var local = Vec3.atLowerCornerOf(hint.button()).add(.5, .85, .5);
                var rendered = new Vec3(pose.last().pose().transformPosition(local.toVector3f()));
                require(hint.anchor(scene, partial).distanceTo(rendered) < 1e-6, "click cue detached from the rendered button");
            }
            hintSamples++;
        });
        previousDisabled = control != null && control.disabled;
    }

    private static void stoppedTool(PonderScene scene, BlockPos tool) {
        var simulation = PonderSimulation.of(scene.getWorld());
        var before = simulation.mountedToolAngle(tool, 0);
        var after = simulation.mountedToolAngle(tool, 1);
        require(before != null && after != null && Math.abs(after - before) < 1e-9, "disabled tool retains rendered spin: " + tool);
    }

    void verify(PonderScene scene) {
        if (!scene.getId().getPath().equals("contraption_controls")) return;
        require(assembly == 3 && switchedOff && switchedOn, "all control demonstrations must run");
        for (int samples : moving) require(samples > 100, "each assembled demonstration must keep moving");
        require(hintSamples > 30, "both moving click cues must actually be displayed");
        require(disabledSamples > 10 && filteredSamples > 10 && redstoneSamples > 10, "disabled tools were not carried through their targets");
        require(cutStone[0] && cutWheat[0], "default actors must actually drill and harvest");
        require(!cutStone[1] && !cutWheat[1], "all-off demonstration changed a target");
        require(!cutStone[2] && cutWheat[2], "drill filter must leave harvesting enabled");
        require(cutStone[3] && !cutWheat[3], "harvester filter must leave drilling enabled");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
