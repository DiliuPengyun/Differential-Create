package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.client.ClientShaftAngles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Compares actual face meshes against the adjoining full shaft, independently of the face resolver. */
final class PonderShaftAudit {
    private final List<String> failures = new ArrayList<>();

    void sample(PonderScene scene) {
        var world = scene.getWorld();
        for (var be : world.getBlockEntities()) {
            if (!(be instanceof KineticBlockEntity kinetic) || kinetic.isRemoved()
                || !ClientShaftAngles.hasSeparateFaces(kinetic)) continue;
            try { verifyMesh(kinetic); }
            catch (AssertionError failure) { fail(scene, failure.getMessage()); }
        }
    }

    void verify(PonderScene scene) {
        if (!failures.isEmpty())
            throw new AssertionError("Shaft face phase: " + String.join("; ", failures));
    }

    static List<Direction> faces(KineticBlockEntity be) {
        if (be instanceof SplitShaftBlockEntity)
            return List.of(Iterate.directionsInAxis(KineticBlockEntityRenderer.getRotationAxisOf(be)));
        return java.util.Arrays.stream(Iterate.directions).filter(face -> ClientShaftAngles.hasShaft(be, face)).toList();
    }

    static void verifyMesh(KineticBlockEntity be) {
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        require(renderer != null, "Missing face renderer for " + be.getBlockState());
        var actual = new Vertices();
        renderer.render(be, AnimationTickHolder.getPartialTicks(be.getLevel()), new PoseStack(),
            ignored -> actual, 0xf000f0, 0);
        int offset = 0;
        for (var face : faces(be)) {
            var neighbour = ClientShaftAngles.neighbour(be, face);
            // Hidden/moving template copies do not constitute a displayed shaft contact.
            boolean connected = neighbour != null && !ClientShaftAngles.hasSeparateFaces(neighbour);
            var expected = new Vertices();
            float angle = connected ? ClientKineticAngles.shaftAngleOf(neighbour) : 0;
            CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), face)
                .rotateCentered(angle, Direction.get(Direction.AxisDirection.POSITIVE, face.getAxis()))
                .light(0xf000f0).renderInto(new PoseStack(), expected);
            require(actual.points.size() >= offset + expected.points.size(), "Missing face vertices at " + be.getBlockPos());
            if (connected) for (int i = 0; i < expected.points.size(); i++)
                require(expected.points.get(i).distanceToSqr(actual.points.get(offset + i)) <= 1e-10,
                    be.getBlockState() + " at " + be.getBlockPos().toShortString() + " face " + face
                        + " differs from adjoining shaft on vertex " + i);
            offset += expected.points.size();
        }
        require(actual.points.size() == offset, "Unexpected face vertices at " + be.getBlockPos());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
