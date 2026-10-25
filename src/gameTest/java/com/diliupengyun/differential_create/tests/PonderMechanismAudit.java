package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Check displayed contact and subframe poses, independently of the motion command's duration. */
final class PonderMechanismAudit {
    private int bearingSamples, nestedSamples, pulleySamples, gantrySamples, reversals;
    private int forwardSamples, reverseSamples;
    private double firstPlankTravel, previousPlankAngle, previousOffset = Double.NaN, direction;

    void sample(PonderScene scene) {
        switch (scene.getId().getPath()) {
            case "mechanical_bearing" -> bearing(scene, new BlockPos(3, 2, 3), new BlockPos(4, 3, 2), true);
            case "bearing" -> bearing(scene, new BlockPos(2, 2, 2), new BlockPos(2, 3, 2), false);
            case "bearing_modes" -> bearing(scene, new BlockPos(5, 2, 2), new BlockPos(5, 1, 2), false);
            case "stabilized_bearings" -> nested(scene);
            case "rope_pulley", "rope_pulley_modes" -> pulley(scene);
            case "gantry_redstone" -> gantry(scene);
        }
    }
    private void bearing(PonderScene scene, BlockPos bearingPos, BlockPos plank, boolean firstOnly) {
        var world = scene.getWorld();
        if (!(world.getBlockEntity(bearingPos) instanceof MechanicalBearingBlockEntity bearing)) return;
        scene.forEachVisible(WorldSectionElement.class, section -> {
            var access = (PonderWorldSectionAccessor) section;
            if (!access.differentialcreate$selection().test(plank) || !PonderSimulation.of(world).mountedBlock(plank)) return;
            if (firstOnly && access.differentialcreate$selection().test(bearingPos.above())) return;
            int axis = bearing.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getAxis().ordinal();
            for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                double body = access.differentialcreate$previousRotation().lerp(section.getAnimatedRotation(), fraction)
                    .get(Direction.Axis.values()[axis]);
                // Create's top renderer calls getInterpolatedAngle(partialTick - 1).
                require(Math.abs(bearing.getInterpolatedAngle(fraction - 1) - body) < .001,
                    "bearing top and wooden arm have different subframe phases: " + scene.getId());
            }
            double current = section.getAnimatedRotation().get(Direction.Axis.values()[axis]);
            double change = current - access.differentialcreate$previousRotation().get(Direction.Axis.values()[axis]);
            if (change > .01) forwardSamples++;
            if (change < -.01) reverseSamples++;
            firstPlankTravel += Math.abs(current - previousPlankAngle);
            previousPlankAngle = current;
            bearingSamples++;
        });
    }
    private void nested(PonderScene scene) {
        var childPos = new BlockPos(3, 4, 2);
        if (!(scene.getWorld().getBlockEntity(childPos) instanceof MechanicalBearingBlockEntity child)) return;
        scene.forEachVisible(WorldSectionElement.class, section -> {
            var access = (PonderWorldSectionAccessor) section;
            if (access.differentialcreate$selection().test(childPos) && PonderSimulation.of(scene.getWorld()).mountedBlock(childPos)) {
                for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                    double parent = access.differentialcreate$previousRotation().lerp(section.getAnimatedRotation(), fraction).z;
                    require(Math.abs(child.getInterpolatedAngle(fraction - 1) + parent) < .001,
                        "nested bearing must counter-rotate against its parent at every subframe");
                }
                nestedSamples++;
            }
            if (access.differentialcreate$stabilization() != null) {
                var pose = new com.mojang.blaze3d.vertex.PoseStack();
                ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) section).transformMS(pose, .5f);
                var facing = pose.last().pose().transformDirection(new org.joml.Vector3f(0, 1, 0));
                require(facing.distanceSquared(new org.joml.Vector3f(0, 1, 0)) < 1e-8,
                    "stabilized belt must retain its world orientation");
            }
        });
    }
    private void pulley(PonderScene scene) {
        var world = scene.getWorld();
        var pulleyPos = new BlockPos(2, 4, 2);
        if (!(world.getBlockEntity(pulleyPos) instanceof PulleyBlockEntity pulley)) return;
        if (Double.isFinite(previousOffset)) {
            double change = pulley.offset - previousOffset;
            if (Math.abs(change) > 1e-5) {
                if (direction != 0 && direction * change < 0) {
                    require(Math.min(Math.abs(previousOffset), Math.abs(previousOffset - 2)) < 1e-4,
                        "pulley reversed before reaching the commanded endpoint: " + scene.getId() + " offset=" + previousOffset);
                    reversals++;
                }
                direction = Math.signum(change);
            }
        }
        previousOffset = pulley.offset;
        scene.forEachVisible(WorldSectionElement.class, section -> {
            var access = (PonderWorldSectionAccessor) section;
            boolean mounted = false;
            for (var pos : access.differentialcreate$selection())
                mounted |= !world.getBlockState(pos).isAir() && PonderSimulation.of(world).mountedBlock(pos);
            if (!mounted) return;
            for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                var offset = access.differentialcreate$previousOffset().lerp(section.getAnimatedOffset(), fraction);
                double cargoTop = Double.NEGATIVE_INFINITY;
                for (var pos : access.differentialcreate$selection()) if (!world.getBlockState(pos).isAir()) {
                    var p = Vec3.atLowerCornerOf(pos).add(offset);
                    if (Math.abs(p.x - pulleyPos.getX()) < 1e-6 && Math.abs(p.z - pulleyPos.getZ()) < 1e-6)
                        cargoTop = Math.max(cargoTop, p.y);
                }
                if (Double.isFinite(cargoTop)) require(Math.abs(cargoTop - (pulleyPos.getY() - 1 - pulley.getInterpolatedOffset(fraction))) < 1e-4,
                    "pulley head separated from cargo: " + scene.getId() + " tick=" + scene.getCurrentTime()
                        + " cargo=" + cargoTop + " rope=" + pulley.getInterpolatedOffset(fraction));
            }
            pulleySamples++;
        });
    }
    private void gantry(PonderScene scene) {
        var world = scene.getWorld(); var shaft = new BlockPos(4, 1, 2); var carriage = shaft.above();
        if (!world.getBlockState(shaft).getValue(GantryShaftBlock.POWERED)) return;
        var simulation = PonderSimulation.of(world);
        require(!simulation.mountedBlock(carriage), "redstone must release the assembled carriage's actor and mass bindings");
        scene.forEachVisible(WorldSectionElement.class, section -> {
            if (!((PonderWorldSectionAccessor) section).differentialcreate$selection().test(carriage)) return;
            double x = section.getAnimatedOffset().x;
            require(Math.abs(x - Math.rint(x)) < 1e-9, "powered gantry must settle onto the block grid");
            gantrySamples++;
        });
    }
    void verify(PonderScene scene) {
        switch (scene.getId().getPath()) {
            case "mechanical_bearing", "bearing" -> require(bearingSamples > 30 && firstPlankTravel > 10,
                "the first bearing example never bound or rotated: " + scene.getId());
            case "stabilized_bearings" -> require(nestedSamples > 30, "nested bearing compensation was not exercised");
            case "bearing_modes" -> require(forwardSamples > 20 && reverseSamples > 20,
                "manual reassembly must preserve shaft-to-structure direction when power reverses");
            case "rope_pulley", "rope_pulley_modes" -> require(pulleySamples > 30 && reversals >= 2
                && Math.abs(previousOffset - 2) < 1e-4, "pulley strokes did not finish with synchronized cargo: " + scene.getId());
            case "gantry_redstone" -> require(gantrySamples > 30, "gantry disassembly was not exercised");
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
