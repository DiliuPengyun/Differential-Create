package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Checks the real fan mesh, airflow and workpiece, beyond the solver's internal angle. */
final class PonderFanAudit {
    private int rawFrames, workFrames, batches, products;
    private double remaining = Double.NaN;
    private TransportedItemStack workpiece;
    private boolean completedWorkpiece;

    void sample(PonderScene scene) {
        EncasedFanBlockEntity processing = null;
        var world = scene.getWorld();
        for (var be : world.getBlockEntities()) if (be instanceof EncasedFanBlockEntity fan) {
            if (PonderKinetics.of(world).angle(fan, 1) != null) verifyMesh(fan);
            if (PonderKinetics.of(world).processesFan(fan.getBlockPos())) processing = fan;
        }
        if (processing == null) return;
        var fan = processing;
        for (var be : world.getBlockEntities()) if (be instanceof DepotBlockEntity depot) {
            var handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
            handler.handleProcessingOnAllItems(stack -> {
                if (stack.stack.is(Items.GOLD_ORE)) {
                    if (stack != workpiece) {
                        workpiece = stack;
                        remaining = Double.NaN;
                        completedWorkpiece = false;
                        batches++;
                    }
                    rawFrames++;
                    double current = stack.processingTime;
                    if (stack.processedBy != null && current >= 0) {
                        require(fan.airCurrent.pushing && stack.processedBy == AllFanProcessingTypes.BLASTING,
                            "Fan lesson processes ore without outward blasting airflow");
                        if (Double.isFinite(remaining)) {
                            double spent = remaining - current;
                            require(spent == 1,
                                "Fan lesson must use Create's fixed processing tick: " + spent + " at " + fan.getSpeed());
                            if (spent > 0) workFrames++;
                        }
                        remaining = current;
                    }
                } else if (stack.stack.is(Items.GOLD_INGOT) && !completedWorkpiece) {
                    require(remaining == 0,
                        "Fan lesson replaced ore with an ingot before finishing actual work");
                    completedWorkpiece = true;
                    products++;
                }
                return TransportedResult.doNothing();
            });
        }
    }

    void verify(PonderScene scene) {
        if (!scene.getId().getPath().equals("fan_work")) return;
        require(rawFrames >= 30 && workFrames >= 20 && batches == 2 && products == 2,
            "Fan lesson must visibly process both ores before producing their ingots: "
                + rawFrames + "/" + workFrames + "/" + batches + "/" + products);
    }

    static void verifyRenderer() throws Exception {
        var mc = Minecraft.getInstance();
        var screen = mc.screen;
        int clock = PonderUI.ponderTicks;
        float partial = PonderUI.ponderPartialTicksPaused;
        var ui = new PonderUI(new ArrayList<>(PonderIndex.getSceneAccess().compile(AllBlocks.ENCASED_FAN.getId()))) {};
        var paused = PonderUI.class.getDeclaredField("identifyMode");
        paused.setAccessible(true);
        paused.setBoolean(ui, true);
        mc.screen = ui;
        int cases = 0;
        try {
            for (var facing : Direction.values()) {
                var world = new PonderLevel(BlockPos.ZERO, mc.level);
                var pos = new BlockPos(2, 2, 2);
                world.setBlock(pos, AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, facing), 3);
                var fan = (EncasedFanBlockEntity) world.getBlockEntity(pos);
                var physics = PonderKinetics.of(world);
                physics.useExplicitSources();
                List<Vec3> before = null;
                for (float target : new float[]{64, 256, 0, -64, -1, 0}) {
                    physics.boundary(world, pos, target);
                    for (int tick = 0; tick < 35; tick++) {
                        world.tick();
                        fan.tick();
                        for (float pt : new float[]{0, .5f, 1}) {
                            PonderUI.ponderPartialTicksPaused = pt;
                            verifyMesh(fan);
                            var vertices = render(fan);
                            if (pt == 0 && before != null) sameVertices(before, vertices, "Fan jumps at tick boundary");
                            if (pt == 1) before = vertices;
                            // Pause/slow playback and a different UI clock cannot alter a stored pose.
                            PonderUI.ponderTicks += 137;
                            sameVertices(vertices, render(fan), "Fan pose depends on UI time instead of shaft travel");
                            cases++;
                        }
                        if (fan.getSpeed() != 0) require(fan.airCurrent.pushing
                                == (fan.getAirFlowDirection() == fan.getAirflowOriginSide()),
                            "Fan airflow lags behind a reversal");
                    }
                }
                physics.reset();
                physics.useExplicitSources();
                physics.boundary(world, pos, 0);
                world.tick();
                verifyMesh(fan);
            }
        } finally {
            mc.screen = screen;
            PonderUI.ponderTicks = clock;
            PonderUI.ponderPartialTicksPaused = partial;
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_FAN_RENDER_PASS: {} real mesh samples, six facings, speed changes, reversal, coast, pause and rewind", cases);
    }

    private static void verifyMesh(EncasedFanBlockEntity fan) {
        var world = (PonderLevel) fan.getLevel();
        float partial = AnimationTickHolder.getPartialTicks(world);
        Double theta = PonderKinetics.of(world).angle(fan, partial);
        if (theta == null) return;
        var direction = fan.getBlockState().getValue(BlockStateProperties.FACING);
        double offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(fan, fan.getBlockPos(), direction.getAxis());
        float angle = (float) Math.toRadians((theta + offset) % 360);
        var expected = new Vertices();
        for (var model : List.of(AllPartialModels.SHAFT_HALF, AllPartialModels.ENCASED_FAN_INNER))
            CachedBuffers.partialFacing(model, fan.getBlockState(), direction.getOpposite())
                .rotateCentered(angle, Direction.get(Direction.AxisDirection.POSITIVE, direction.getAxis()))
                .light(0xf000f0).renderInto(new PoseStack(), expected);
        sameVertices(expected.points, render(fan), "Fan blade/shaft mesh disagrees with integrated shaft phase at " + fan.getBlockPos());
    }

    private static List<Vec3> render(EncasedFanBlockEntity fan) {
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(fan);
        require(renderer != null, "Missing fan renderer");
        var vertices = new Vertices();
        renderer.render(fan, AnimationTickHolder.getPartialTicks(fan.getLevel()), new PoseStack(), ignored -> vertices, 0xf000f0, 0);
        return vertices.points;
    }

    private static void sameVertices(List<Vec3> expected, List<Vec3> actual, String message) {
        require(expected.size() > 24 && expected.size() == actual.size(), message + ": vertex count");
        for (int i = 0; i < expected.size(); i++)
            require(expected.get(i).distanceToSqr(actual.get(i)) < 1e-10, message + ": vertex " + i);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Vertices implements VertexConsumer {
        final List<Vec3> points = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { points.add(new Vec3(x, y, z)); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
