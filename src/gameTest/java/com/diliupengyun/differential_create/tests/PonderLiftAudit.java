package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** World-space contact checks include camera translations and cosmetic pulley heads. */
final class PonderLiftAudit {
    private double previous = Double.NaN, direction, maximum;
    private int samples, up, down, reversals;
    private boolean wasMounted;
    void sample(PonderScene scene) {
        String name = scene.getId().getPath();
        boolean elevator = name.equals("elevator_pulley"), multi = name.endsWith("pulley_multi_rope");
        if (!elevator && !multi) return;
        var world = scene.getWorld(); var sim = PonderSimulation.of(world);
        var primary = elevator ? new BlockPos(3, 18, 2) : new BlockPos(3, 4, 1);
        var cargo = elevator ? new BlockPos(3, 17, 2) : new BlockPos(3, 1, 1);
        if (!(world.getBlockEntity(primary) instanceof PulleyBlockEntity pulley)) return;
        boolean mounted = sim.mountedBlock(cargo);
        if (mounted) {
            double delta = wasMounted && Double.isFinite(previous) ? pulley.offset - previous : 0;
            if (delta > 1e-5) down++;
            if (delta < -1e-5) up++;
            if (Math.abs(delta) > 1e-5) {
                if (direction != 0 && direction * delta < 0) {
                    double[] ends = elevator ? new double[]{1, 7, 13} : new double[]{0, 2};
                    require(java.util.Arrays.stream(ends).anyMatch(end -> Math.abs(previous - end) < 1e-4),
                        scene, "reversed before arrival at " + previous);
                    reversals++;
                }
                direction = Math.signum(delta);
            }
            maximum = Math.max(maximum, pulley.offset);
            for (var pos : multi ? new BlockPos[]{primary, new BlockPos(3, 4, 3), new BlockPos(1, 4, 3)} : new BlockPos[]{primary}) {
                var rope = (PulleyBlockEntity) world.getBlockEntity(pos);
                for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                    Vec3 hook = point(scene, pos, fraction).add(0, -1 - rope.getInterpolatedOffset(fraction), 0);
                    double[] gap = {Double.POSITIVE_INFINITY};
                    scene.forEachVisible(WorldSectionElement.class, section -> {
                        var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
                        if (!selection.test(cargo)) return;
                        for (var cell : selection) if (!world.getBlockState(cell).isAir()) {
                            var p = point(scene, cell, fraction);
                            if (Math.abs(p.x - hook.x) < 1e-5 && Math.abs(p.z - hook.z) < 1e-5)
                                gap[0] = Math.min(gap[0], Math.abs(p.y - hook.y));
                        }
                    });
                    require(gap[0] < 2e-4, scene, "rope head lost cargo contact at " + pos + ", gap=" + gap[0] + ", partial=" + fraction);
                }
            }
            samples++;
        }
        if (!mounted) direction = 0;
        wasMounted = mounted;
        previous = pulley.offset;
    }
    private static Vec3 point(PonderScene scene, BlockPos pos, float fraction) {
        Vec3[] result = {Vec3.atLowerCornerOf(pos)};
        scene.forEachVisible(WorldSectionElement.class, section -> {
            if (!((PonderWorldSectionAccessor) section).differentialcreate$selection().test(pos)) return;
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) section).transformMS(pose, fraction);
            result[0] = new Vec3(pose.last().pose().transformPosition(new org.joml.Vector3f(pos.getX(), pos.getY(), pos.getZ())));
        });
        return result[0];
    }
    void verify(PonderScene scene) {
        String name = scene.getId().getPath();
        if (!name.equals("elevator_pulley") && !name.endsWith("pulley_multi_rope")) return;
        require(samples > 50 && up > 5 && down > 5 && reversals >= 2, scene,
            "lift did not complete both directions: samples=" + samples + ", up=" + up + ", down=" + down + ", reversals=" + reversals);
        require(maximum >= (name.equals("elevator_pulley") ? 13 : 2) - 1e-4, scene, "lowest floor never reached: " + maximum);
    }
    private static void require(boolean ok, PonderScene scene, String message) {
        if (!ok) throw new AssertionError(scene.getId() + " tick=" + scene.getCurrentTime() + ": " + message);
    }
}
