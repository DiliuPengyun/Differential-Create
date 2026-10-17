package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * A gearshift renders a shaft half on each face. Their phases belong to the
 * input/output networks; the native renderer instead derives both from the
 * client render clock, which visibly twists the face stub against a Ponder
 * shaft driven by the accumulated solver angle.
 */
final class PonderGearshiftAudit {
    private final List<String> failures = new ArrayList<>();

    void sample(PonderScene scene) {
        var world = scene.getWorld();
        for (var be : world.getBlockEntities()) {
            if (!(be instanceof GearshiftBlockEntity gearshift) || gearshift.isRemoved()) continue;
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(gearshift);
            if (renderer == null) {
                fail(scene, "missing split-shaft renderer");
                continue;
            }
            var actual = new Vertices();
            renderer.render(gearshift, AnimationTickHolder.getPartialTicks(world), new PoseStack(),
                ignored -> actual, 0xf000f0, 0);

            var expected = new Vertices();
            var axis = KineticBlockEntityRenderer.getRotationAxisOf(gearshift);
            for (var face : Iterate.directionsInAxis(axis)) {
                float angle = ClientKineticAngles.splitShaftAngle(gearshift, face);
                CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, gearshift.getBlockState(), face)
                    .rotateCentered(angle, Direction.get(Direction.AxisDirection.POSITIVE, axis))
                    .light(0xf000f0)
                    .renderInto(new PoseStack(), expected);
            }
            if (actual.points.size() != expected.points.size()) {
                fail(scene, "gearshift at " + gearshift.getBlockPos().toShortString() + " rendered "
                    + actual.points.size() + " vertices instead of " + expected.points.size());
                continue;
            }
            for (int i = 0; i < actual.points.size(); i++) {
                if (expected.points.get(i).distanceToSqr(actual.points.get(i)) > 1e-10) {
                    fail(scene, "gearshift face phase at " + gearshift.getBlockPos().toShortString()
                        + " differs on vertex " + i + " actual=" + actual.points.get(i)
                        + " expected=" + expected.points.get(i));
                    break;
                }
            }
        }
    }

    void verify(PonderScene scene) {
        if (!failures.isEmpty())
            throw new AssertionError("Gearshift face phase: " + String.join("; ", failures));
    }

    private void fail(PonderScene scene, String message) {
        if (failures.size() < 12) failures.add("tick " + scene.getCurrentTime() + ": " + message);
    }

    private static final class Vertices implements VertexConsumer {
        final List<Vec3> points = new ArrayList<>();

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            points.add(new Vec3(x, y, z));
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
