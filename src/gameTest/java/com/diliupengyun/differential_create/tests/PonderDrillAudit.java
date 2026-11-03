package com.diliupengyun.differential_create.tests;

import java.util.HashMap;
import java.util.Map;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.drill.DrillBlock;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Check the displayed drill phase through both cutting strokes and the intervening return. */
final class PonderDrillAudit {
    private record Pose(WorldSectionElement owner, Vec3 point, double angle) {}
    private final Map<BlockPos, Pose> previous = new HashMap<>();
    private final java.util.Set<BlockPos> returned = new java.util.HashSet<>(), resumed = new java.util.HashSet<>();
    private int forward, backward, stationary;

    void sample(PonderScene scene) {
        if (!scene.getId().getPath().equals("mechanical_drill_contraption")) return;
        var simulation = PonderSimulation.of(scene.getWorld());
        scene.forEachVisible(WorldSectionElement.class, section -> {
            var pose = new PoseStack();
            ((WorldSectionElementImpl) section).transformMS(pose, 1);
            var matrix = pose.last().pose();
            for (var cursor : ((PonderWorldSectionAccessor) section).differentialcreate$selection()) {
                var pos = cursor.immutable();
                var state = scene.getWorld().getBlockState(pos);
                if (!(state.getBlock() instanceof DrillBlock) || !simulation.mountedTool(pos)) continue;
                var facing = Vec3.atLowerCornerOf(state.getValue(DrillBlock.FACING).getNormal());
                var local = Vec3.atCenterOf(pos).add(facing.scale(.65));
                var point = new Vec3(matrix.transformPosition(local.toVector3f()));
                double start = simulation.mountedToolAngle(pos, 0), end = simulation.mountedToolAngle(pos, 1);
                var old = previous.put(pos, new Pose(section, point, end));
                if (old == null || old.owner() != section) continue;
                require(Math.abs(start - old.angle()) < 1e-7, scene, "drill phase jumps between ticks", pos);
                var worldMotion = point.subtract(old.point());
                double travel = worldMotion.length();
                var localMotion = new Vec3(new org.joml.Matrix4f(matrix).invert().transformDirection(worldMotion.toVector3f()));
                double along = localMotion.dot(facing);
                if (travel < 1e-7 || along < -.99 * travel) {
                    for (float partial : new float[]{0, .25f, .5f, .75f, 1})
                        require(Math.abs(simulation.mountedToolAngle(pos, partial) - start) < 1e-7,
                            scene, "stationary or returning drill still spins", pos);
                    if (travel < 1e-7) stationary++;
                    else { backward++; returned.add(pos); }
                } else if (along > .99 * travel && travel > .003) {
                    require(Math.abs(end - start) > 1e-5, scene, "advancing drill does not spin", pos);
                    // Native animation speed is degrees/second: this feed band is 100 deg/s.
                    if (travel < .09) require(Math.abs(Math.abs(end - start) - 5) < 1e-5,
                        scene, "drill animation treats degrees/second as RPM", pos);
                    forward++;
                    if (returned.contains(pos)) resumed.add(pos);
                }
            }
        });
    }

    void verify(PonderScene scene) {
        if (!scene.getId().getPath().equals("mechanical_drill_contraption")) return;
        require(forward > 100 && backward > 80 && stationary > 40 && resumed.size() == 4,
            scene, "missing forward, return, hold or resumed cutting coverage", BlockPos.ZERO);
        DifferentialCreate.LOGGER.info("DC_PONDER_DRILL_PASS: {} forward, {} returning, {} stationary samples; {} drills resumed cutting",
            forward, backward, stationary, resumed.size());
    }

    private static void require(boolean condition, PonderScene scene, String message, BlockPos pos) {
        if (!condition) throw new AssertionError(message + " at tick " + scene.getCurrentTime() + " " + pos);
    }
}
