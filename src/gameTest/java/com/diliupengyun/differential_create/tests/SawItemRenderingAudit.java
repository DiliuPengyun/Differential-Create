package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawRenderer;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Read the actual item mesh; recipe counters alone cannot prove a continuous feed. */
final class SawItemRenderingAudit {
    private static final java.lang.reflect.Method RENDER_ITEMS;
    static {
        try {
            RENDER_ITEMS = SawRenderer.class.getDeclaredMethod("renderItems", SawBlockEntity.class, float.class,
                PoseStack.class, MultiBufferSource.class, int.class, int.class);
            RENDER_ITEMS.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    record Pose(Vec3 start, Vec3 end, String stock, boolean applied, float duration) {}

    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var screen = mc.screen;
        int clock = PonderUI.ponderTicks;
        float partial = PonderUI.ponderPartialTicksPaused;
        var paused = PonderUI.class.getDeclaredField("identifyMode"); paused.setAccessible(true);
        int samples = 0;
        try {
            var scene = PonderIndex.getSceneAccess().compile(AllBlocks.MECHANICAL_SAW.getId()).stream()
                .filter(s -> s.getId().getPath().equals("mechanical_saw_processing")).findFirst().orElseThrow();
            var ui = new PonderUI(new ArrayList<>(List.of(scene))) {};
            mc.screen = ui; paused.setBoolean(ui, true); scene.begin();
            Pose previous = null;
            for (int tick = 0; tick < 1800 && !scene.isFinished(); tick++) {
                scene.tick();
                var saw = (SawBlockEntity) scene.getWorld().getBlockEntity(new BlockPos(2, 1, 2));
                if (saw == null || saw.inventory.isEmpty()) { previous = null; continue; }
                var current = pose(saw);
                if (previous != null && previous.stock().equals(current.stock())
                    && previous.applied() == current.applied() && previous.duration() == current.duration())
                    near(previous.end(), current.start(), "Native saw item jumps between ticks at " + scene.getCurrentTime());
                for (float p : new float[]{0, .25f, .5f, .75f, 1}) {
                    var actual = center(saw, p);
                    near(current.start().lerp(current.end(), p), actual, "Saw item interpolation is not linear");
                    PonderUI.ponderTicks += 137;
                    near(actual, center(saw, p), "Saw item depends on the UI clock");
                    PonderUI.ponderTicks -= 137;
                    samples++;
                }
                previous = current;
            }
            for (boolean firstAxis : new boolean[]{false, true}) for (int direction : new int[]{-1, 1}) {
                var world = new PonderLevel(BlockPos.ZERO, mc.level);
                var pos = new BlockPos(0, 1, 0);
                world.setBlock(pos, AllBlocks.MECHANICAL_SAW.getDefaultState()
                    .setValue(SawBlock.FACING, net.minecraft.core.Direction.UP)
                    .setValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE, firstAxis), 3);
                var saw = (SawBlockEntity) world.getBlockEntity(pos); saw.initialize();
                saw.inventory.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
                saw.inventory.recipeDuration = 1000; saw.inventory.remainingTime = 800; saw.inventory.appliedRecipe = false;
                var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pos, direction * 8);
                Pose before = null;
                for (int tick = 0; tick < 220; tick++) {
                    if (tick == 12) physics.boundary(world, pos, 0);
                    world.tick(); PonderSimulation.of(world).finish(world);
                    var current = pose(saw);
                    if (before != null) near(before.end(), current.start(), "Low-speed/stopping saw item jumps");
                    before = current; samples += 2;
                }
                require(Math.abs(saw.getSpeed()) < 1e-5, "The stopped fixture never stopped");
                near(center(saw, 0), center(saw, 1), "A held saw item still travels inside a tick");
                var saved = saw.saveWithoutMetadata(world.registryAccess());
                var otherPos = pos.above(3);
                world.setBlock(otherPos, saw.getBlockState(), 3);
                var restored = (SawBlockEntity) world.getBlockEntity(otherPos);
                restored.loadWithComponents(saved, world.registryAccess());
                restored.setSpeed(0);
                near(center(saw, 1), center(restored, .5f), "Stopped saw item changes its feed side after reload");
            }
        } finally {
            mc.screen = screen; PonderUI.ponderTicks = clock; PonderUI.ponderPartialTicksPaused = partial;
        }
        DifferentialCreate.LOGGER.info("DC_SAW_ITEM_RENDER_PASS: {} actual mesh samples; native feed, subframes, low speed, stop and saved direction", samples);
    }

    static Pose pose(SawBlockEntity saw) throws Exception {
        var stock = new ArrayList<String>();
        for (int slot = 0; slot < saw.inventory.getSlots(); slot++) stock.add(saw.inventory.getStackInSlot(slot).toString());
        return new Pose(center(saw, 0), center(saw, 1), stock.toString(), saw.inventory.appliedRecipe, saw.inventory.recipeDuration);
    }
    static Vec3 center(SawBlockEntity saw, float partial) throws Exception {
        var vertices = new Centroid();
        RENDER_ITEMS.invoke(Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(saw), saw, partial,
            new PoseStack(), (MultiBufferSource) type -> vertices, 0xf000f0, 0);
        require(vertices.count > 0, "Saw has material but no item mesh");
        return new Vec3(vertices.x / vertices.count, vertices.y / vertices.count, vertices.z / vertices.count);
    }
    private static final class Centroid implements VertexConsumer {
        int count; double x, y, z;
        @Override public VertexConsumer addVertex(float a, float b, float c) { count++; x += a; y += b; z += c; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
    private static void near(Vec3 expected, Vec3 actual, String message) {
        require(expected.distanceToSqr(actual) < 1e-10, message + ": " + expected + " -> " + actual);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
