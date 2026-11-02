package com.diliupengyun.differential_create.tests;

import java.util.IdentityHashMap;
import java.util.Map;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/** Check the displayed control operations and their actual tool results in every assembly. */
final class PonderControlsAudit {
    private static final BlockPos ARM = new BlockPos(5, 2, 4), CONTROL = new BlockPos(1, 2, 5);
    private static final BlockPos FILTERED = new BlockPos(2, 2, 5), WHEAT = new BlockPos(2, 1, 1);
    private static final BlockPos STONE = new BlockPos(3, 1, 2), HARVESTER = new BlockPos(1, 1, 4);
    private final Map<WorldSectionElement, Integer> assemblies = new IdentityHashMap<>();
    private final boolean[] cutStone = new boolean[4], cutWheat = new boolean[4];
    private int assembly = -1, disabledSamples, filteredSamples, redstoneSamples;
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
            if (assembly >= 0) require(Math.abs(lastAngle + 90) < .03, "assembly was replaced before its quarter-turn finished: " + lastAngle);
            assembly = next; previousDisabled = false;
            require(section.getAnimatedRotation().length() < .03, "new assembly inherited the preceding rotation");
        }
        lastAngle = section.getAnimatedRotation().y;
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
                parked(section, control.disabled ? -15 : -75);
                if (control.disabled) switchedOff = true; else switchedOn = true;
            }
            if (control.disabled) {
                require(stone && mature, "disabled tools still changed their targets");
                require(((HarvesterBlockEntity) world.getBlockEntity(HARVESTER)).getAnimatedSpeed() == 0,
                    "disabled harvester keeps spinning in the displayed structure");
                if (lastAngle < -20 && lastAngle > -70) disabledSamples++;
            }
        }
        if (assembly == 2 && filtered != null && filtered.disabled) {
            require(stone, "drill-only filter did not preserve the stone");
            if (lastAngle < -20 && lastAngle > -70) filteredSamples++;
        }
        if (assembly == 3 && control != null && control.disabled) {
            require(mature, "initial redstone disable did not preserve the crop");
            require(((HarvesterBlockEntity) world.getBlockEntity(HARVESTER)).getAnimatedSpeed() == 0,
                "redstone-disabled harvester still has an active animation");
            if (lastAngle < -20 && lastAngle > -70) redstoneSamples++;
        }
        previousDisabled = control != null && control.disabled;
    }

    private static void parked(WorldSectionElement section, double target) {
        double previous = ((PonderWorldSectionAccessor) section).differentialcreate$previousRotation().y;
        require(Math.abs(section.getAnimatedRotation().y - target) < .03 && Math.abs(previous - section.getAnimatedRotation().y) < 1e-4,
            "control click missed its parked target " + target + ": " + previous + " -> " + section.getAnimatedRotation().y);
    }

    void verify(PonderScene scene) {
        if (!scene.getId().getPath().equals("contraption_controls")) return;
        require(assembly == 3 && switchedOff && switchedOn, "all control demonstrations must run");
        require(disabledSamples > 10 && filteredSamples > 10 && redstoneSamples > 10, "disabled tools were not carried through their targets");
        require(cutStone[0] && cutWheat[0], "default actors must actually drill and harvest");
        require(!cutStone[1] && !cutWheat[1], "all-off demonstration changed a target");
        require(!cutStone[2] && cutWheat[2], "drill filter must leave harvesting enabled");
        require(cutStone[3] && !cutWheat[3], "harvester filter must leave drilling enabled");
        require(Math.abs(lastAngle + 90) < .03, "final control demonstration did not finish its turn");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
