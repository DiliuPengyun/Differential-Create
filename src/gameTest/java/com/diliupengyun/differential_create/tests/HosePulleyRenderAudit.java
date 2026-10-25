package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.HosePulleyAnimation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.pulley.HosePulleyVisual;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyRenderer;
import dev.engine_room.flywheel.api.instance.*;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.*;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exercise native block entities and both render paths; no live world blocks are edited. */
final class HosePulleyRenderAudit {
    private static final Field OFFSET = field(HosePulleyBlockEntity.class, "offset");

    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        int samples = 0;
        var getter = HosePulleyRenderer.class.getDeclaredMethod("getOffset", HosePulleyBlockEntity.class, float.class);
        getter.setAccessible(true);
        for (Level world : List.of(mc.level, new PonderLevel(BlockPos.ZERO, mc.level)))
            for (var facing : Direction.Plane.HORIZONTAL) {
                var pos = new BlockPos(0, 5, 0);
                var state = AllBlocks.HOSE_PULLEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
                var hose = (HosePulleyBlockEntity) AllBlocks.HOSE_PULLEY.get().newBlockEntity(pos, state);
                hose.setLevel(world);
                var offset = (LerpedFloat) OFFSET.get(hose);
                // Serialization itself consumes LerpedFloat's one-shot Force flag.
                // Clear it before taking a stable baseline for the render-only checks.
                offset.writeNBT();
                var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(hose);
                float previous = 0;
                for (float next : new float[]{.001f, .02f, .1f, .1875f, .249f, .251f, .749f, .751f,
                    .999f, 1, 1.001f, 2, 4, 2, 1, .75f, .25f, .1875f, .1f, .01f, 0, 0, .001f}) {
                    offset.startWithValue(previous);
                    offset.setValue(next);
                    var saved = offset.writeNBT().copy();
                    float start = hose.getInterpolatedOffset(0), end = hose.getInterpolatedOffset(1);
                    require(previous == next ? start == end : Math.signum(end - start) == Math.signum(next - previous),
                        "hose head must move immediately in either direction, including fully retracted restart");
                    for (float pt : new float[]{0, .25f, .5f, .75f, 1}) {
                        float expected = HosePulleyAnimation.offset(offset.getValue(pt));
                        near(expected, (float) getter.invoke(renderer, hose, pt), "ordinary renderer uses the shared pose");
                        var sink = new InstanceSink();
                        var visual = new HosePulleyVisual(sink, hose, pt);
                        near(pos.getY() - expected, sink.transformed.getFirst().pose.m31(), "Flywheel head follows the same pose");
                        visual.delete();
                        if (world instanceof PonderLevel) {
                            var vertices = new Vertices();
                            renderer.render(hose, pt, new PoseStack(), ignored -> vertices, 0xf000f0, 0);
                            require(vertices.count > 0, "missing ordinary hose mesh");
                            near(-expected, vertices.lowest, "native head geometry reaches the expected height");
                        }
                        near(expected, hose.getInterpolatedOffset(pt), "repeated/paused rendering is stable");
                        near(previous, offset.getValue(0), "rendering preserves the previous physical extension");
                        near(next, offset.getValue(1), "rendering preserves the current physical extension");
                        require(saved.equals(offset.writeNBT()), "rendering must not modify physical extension or saved interpolation");
                        samples++;
                    }
                    previous = next;
                }
            }
        replay();
        DifferentialCreate.LOGGER.info("DC_HOSE_PULLEY_RENDER_PASS: {} samples; real client/Ponder worlds, ordinary mesh/Flywheel, startup, retraction, reversal, pause and replay", samples);
    }

    private static void replay() {
        var scene = PonderIndex.getSceneAccess().compile(AllBlocks.HOSE_PULLEY.getId()).stream()
            .filter(candidate -> candidate.getId().getPath().equals("hose_pulley")).findFirst().orElseThrow();
        List<Float> reference = null;
        for (boolean skipping : new boolean[]{false, true, false}) {
            scene.begin();
            var positions = new ArrayList<Float>();
            for (int tick = 0; tick < 340; tick++) {
                if (skipping) scene.forEach(element -> element.whileSkipping(scene));
                scene.tick();
                var hose = (HosePulleyBlockEntity) scene.getWorld().getBlockEntity(new BlockPos(1, 5, 1));
                positions.add(hose.getInterpolatedOffset(1));
            }
            if (reference == null) reference = positions;
            else require(reference.equals(positions), "native hose startup/reversal differs after rewind or skip");
        }
    }

    private static Field field(Class<?> type, String name) {
        try { var result = type.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void near(double expected, double actual, String message) {
        require(Math.abs(expected - actual) < 2e-6, message + ": " + expected + " / " + actual);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class InstanceSink implements VisualizationContext, InstancerProvider {
        final List<TransformedInstance> transformed = new ArrayList<>();
        @Override public InstancerProvider instancerProvider() { return this; }
        @Override public Vec3i renderOrigin() { return BlockPos.ZERO; }
        @Override public VisualEmbedding createEmbedding(Vec3i origin) { throw new AssertionError("unexpected embedded visual"); }
        @Override public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
            return new Instancer<>() {
                @Override public I createInstance() {
                    I instance = type.create(new InstanceHandle() {
                        @Override public void setChanged() {}
                        @Override public void setDeleted() {}
                        @Override public void setVisible(boolean visible) {}
                        @Override public boolean isVisible() { return true; }
                    });
                    if (instance instanceof TransformedInstance transform) transformed.add(transform);
                    return instance;
                }
                @Override public void stealInstance(I instance) {}
            };
        }
    }

    private static final class Vertices implements VertexConsumer {
        int count;
        float lowest = Float.POSITIVE_INFINITY;
        @Override public VertexConsumer addVertex(float x, float y, float z) { lowest = Math.min(lowest, y); count++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
