package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;

/** Measure actual rendered transforms, independently of the parent binding. */
final class PonderSectionJoinAudit {
    private int joiningSamples, mergedSamples;
    private double firstAngle, maximumAngleChange;
    private boolean stickerExtended;
    private Double releasedPlankAngle, releasedStickerAngle;
    private double detachedTravel;
    private int detachedSamples;
    private int contactOnSamples, contactOffSamples;
    private double maximumRopeLength;

    void sample(PonderScene scene) {
        if (scene.getId().getPath().equals("threshold_switch")) { ropeLength(scene); return; }
        if (scene.getId().getPath().equals("sticker")) { sticker(scene); return; }
        if (scene.getId().getPath().equals("redstone_contact")) { contact(scene); return; }
        if (!scene.getId().getPath().equals("mechanical_harvester")) return;
        var sections = new ArrayList<WorldSectionElement>();
        scene.forEachVisible(WorldSectionElement.class, sections::add);
        var arm = sections.stream().filter(section -> contains(section, new BlockPos(1, 1, 5))).findFirst().orElse(null);
        var chest = sections.stream().filter(section -> contains(section, new BlockPos(1, 2, 5))).findFirst().orElse(null);
        if (arm == null || chest == null) return;
        if (arm == chest) { mergedSamples++; return; }
        for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
            var parentPose = new PoseStack(); var childPose = new PoseStack();
            ((WorldSectionElementImpl) arm).transformMS(parentPose, partial);
            ((WorldSectionElementImpl) chest).transformMS(childPose, partial);
            require(parentPose.last().pose().equals(childPose.last().pose(), 1e-5f), scene,
                "incoming chest detaches from the rotating arm at partial " + partial);
        }
        if (joiningSamples++ == 0) firstAngle = arm.getAnimatedRotation().y;
        maximumAngleChange = Math.max(maximumAngleChange, Math.abs(arm.getAnimatedRotation().y - firstAngle));
    }

    void verify(PonderScene scene) {
        if (scene.getId().getPath().equals("threshold_switch"))
            require(maximumRopeLength > 2.99, scene, "length demonstration did not reach its visible endpoint");
        if (scene.getId().getPath().equals("redstone_contact"))
            require(contactOnSamples > 5 && contactOffSamples > 50, scene, "contact did not exercise both touching and separated states");
        if (scene.getId().getPath().equals("sticker"))
            require(detachedSamples > 50 && detachedTravel > 90, scene,
                "sticker did not rotate independently after releasing its plank");
        if (scene.getId().getPath().equals("mechanical_harvester"))
            require(joiningSamples >= 10 && maximumAngleChange > 1 && mergedSamples > 30, scene,
                "moving fade-in and subsequent merge were not both exercised");
    }

    private void ropeLength(PonderScene scene) {
        if (!(scene.getWorld().getBlockEntity(new BlockPos(2, 2, 3)) instanceof
            com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley) || pulley.offset <= 0) return;
        maximumRopeLength = Math.max(maximumRopeLength, pulley.offset);
        require(pulley.offset <= 3.0001, scene, "rope leaves the lesson's visible range");
        int level = scene.getWorld().getBlockState(new BlockPos(1, 1, 3))
            .getValue(com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlock.LEVEL);
        require(Math.abs(level - pulley.offset * 5 / 3) < 1.0001, scene, "length indicator is out of step with the rope");
    }

    private void contact(PonderScene scene) {
        scene.forEachVisible(WorldSectionElement.class, section -> {
            if (!contains(section, new BlockPos(1, 2, 2))) return;
            double total = section.getAnimatedRotation().y;
            if (total < 360) return;
            double angle = Math.abs(net.minecraft.util.Mth.wrapDegrees(total));
            boolean powered = scene.getWorld().getBlockState(new BlockPos(1, 1, 2))
                .getValue(com.simibubi.create.content.redstone.contact.RedstoneContactBlock.POWERED);
            // Generous geometric margins avoid asserting the exact activation boundary.
            if (angle < 7) { require(powered, scene, "aligned redstone contact is dark"); contactOnSamples++; }
            if (angle > 20) { require(!powered, scene, "separated redstone contact remains powered"); contactOffSamples++; }
        });
    }

    private void sticker(PonderScene scene) {
        var state = scene.getWorld().getBlockState(new BlockPos(2, 2, 2));
        if (!state.hasProperty(com.simibubi.create.content.contraptions.chassis.StickerBlock.EXTENDED)) return;
        boolean extended = state.getValue(com.simibubi.create.content.contraptions.chassis.StickerBlock.EXTENDED);
        stickerExtended |= extended;
        if (!stickerExtended || extended) return;
        var sections = new ArrayList<WorldSectionElement>();
        scene.forEachVisible(WorldSectionElement.class, sections::add);
        var plank = sections.stream().filter(section -> contains(section, new BlockPos(2, 2, 1))).findFirst().orElse(null);
        var sticker = sections.stream().filter(section -> contains(section, new BlockPos(2, 2, 2))).findFirst().orElse(null);
        if (plank == null || sticker == null) return;
        if (releasedPlankAngle == null) {
            releasedPlankAngle = plank.getAnimatedRotation().y;
            releasedStickerAngle = sticker.getAnimatedRotation().y;
        }
        for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
            double angle = ((PonderWorldSectionAccessor) plank).differentialcreate$previousRotation()
                .lerp(plank.getAnimatedRotation(), partial).y;
            require(Math.abs(angle - releasedPlankAngle) < 1e-5, scene, "released plank still rotates with the sticker");
        }
        detachedTravel = Math.max(detachedTravel, Math.abs(sticker.getAnimatedRotation().y - releasedStickerAngle));
        detachedSamples++;
    }

    private static boolean contains(WorldSectionElement section, BlockPos position) {
        var selected = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
        return selected != null && selected.test(position);
    }
    private static void require(boolean ok, PonderScene scene, String message) {
        if (!ok) throw new AssertionError(scene.getId() + " tick=" + scene.getCurrentTime() + ": " + message);
    }
}
