package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MixerVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import dev.engine_room.flywheel.api.instance.*;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.*;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/** Exercise the rendered head, including a stopped stroke and native gear changes. */
final class MixerRenderingAudit {
    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var original = mc.screen;
        int clock = PonderUI.ponderTicks;
        float partial = PonderUI.ponderPartialTicksPaused;
        var paused = PonderUI.class.getDeclaredField("identifyMode");
        paused.setAccessible(true);
        var scene = PonderIndex.getSceneAccess().compile(AllBlocks.MECHANICAL_MIXER.getId()).stream()
            .filter(s -> s.getId().getPath().equals("mechanical_mixer")).findFirst().orElseThrow();
        var ui = new PonderUI(new ArrayList<>(List.of(scene))) {};
        var issues = new ArrayList<String>();
        int samples = 0;
        try {
            mc.screen = ui;
            paused.setBoolean(ui, true);
            for (int progress : new int[]{8, 18, 28, 38}) {
                var world = new PonderLevel(BlockPos.ZERO, mc.level);
                var pos = new BlockPos(0, 3, 0);
                world.setBlock(pos, AllBlocks.MECHANICAL_MIXER.getDefaultState(), 3);
                var mixer = (MechanicalMixerBlockEntity) world.getBlockEntity(pos);
                mixer.initialize();
                mixer.running = true; mixer.runningTicks = progress;
                var physics = PonderKinetics.of(world);
                physics.useExplicitSources(); physics.boundary(world, pos, 0);
                world.tick(); PonderSimulation.of(world).finish(world);
                float start = mixer.getRenderedHeadOffset(0), end = mixer.getRenderedHeadOffset(1);
                if (Math.abs(start - end) > 1e-6)
                    issues.add("stopped mixer head moves within a tick at progress " + progress + ": " + start + " -> " + end);
            }
            scene.begin();
            var previous = new java.util.IdentityHashMap<MechanicalMixerBlockEntity, List<Vec3>>();
            var visuals = new java.util.IdentityHashMap<MechanicalMixerBlockEntity, Visual>();
            boolean clockFailure = false, boundaryFailure = false;
            var stages = new java.util.HashSet<Integer>();
            for (int tick = 0; tick < 1800 && !scene.isFinished(); tick++) {
                PonderUI.ponderTicks = tick;
                scene.tick();
                for (var be : scene.getWorld().getBlockEntities()) {
                    if (!(be instanceof MechanicalMixerBlockEntity mixer)
                        || !PonderSimulation.of(scene.getWorld()).physical(scene.getWorld(), be.getBlockPos())) continue;
                    stages.add(!mixer.running ? 0 : mixer.runningTicks < 15 ? 1 : mixer.runningTicks <= 20 ? 2 : 3);
                    PonderUI.ponderPartialTicksPaused = 0;
                    var start = render(mixer);
                    var old = previous.get(mixer);
                    if (old != null && !same(old, start) && !boundaryFailure) {
                        issues.add("mixer head mesh jumps at tick boundary " + scene.getCurrentTime());
                        boundaryFailure = true;
                    }
                    for (float pt : new float[]{0, .5f, 1}) {
                        PonderUI.ponderPartialTicksPaused = pt;
                        var points = render(mixer);
                        var visual = visuals.get(mixer);
                        if (visual == null) { visual = new Visual(mixer); visuals.put(mixer, visual); }
                        visual.verify(mixer, pt, points);
                        if (tick % 40 == 0) {
                            var replacement = new Visual(mixer);
                            replacement.verify(mixer, pt, points);
                            replacement.visual.delete();
                        }
                        PonderUI.ponderTicks += 137;
                        if (!same(points, render(mixer)) && !clockFailure) {
                            issues.add("mixer head mesh depends on absolute UI clock at tick " + scene.getCurrentTime());
                            clockFailure = true;
                        }
                        PonderUI.ponderTicks -= 137;
                        samples++;
                    }
                    previous.put(mixer, render(mixer));
                }
            }
            if (stages.size() != 4) issues.add("missing native mixer stage coverage: " + stages);
            visuals.values().forEach(value -> value.visual.delete());
        } finally {
            mc.screen = original;
            PonderUI.ponderTicks = clock;
            PonderUI.ponderPartialTicksPaused = partial;
        }
        if (!issues.isEmpty()) throw new AssertionError("Mixer rendering: " + String.join("; ", issues));
        DifferentialCreate.LOGGER.info("DC_MIXER_RENDER_PASS: {} real head mesh and Flywheel instance samples, four stopped stroke poses, native gearing stages and visual rebuilds", samples);
    }

    private static final class Visual {
        private static final java.lang.reflect.Method ANIMATE;
        static {
            try { ANIMATE = MixerVisual.class.getDeclaredMethod("animate", float.class); ANIMATE.setAccessible(true); }
            catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
        final Sink sink = new Sink();
        final MixerVisual visual;
        Visual(MechanicalMixerBlockEntity mixer) { visual = new MixerVisual(sink, mixer, 0); }
        void verify(MechanicalMixerBlockEntity mixer, float partial, List<Vec3> fallback) throws Exception {
            ANIMATE.invoke(visual, partial);
            var head = sink.rotating.getLast();
            float angle = head.rotationOffset + com.diliupengyun.differential_create.client.ClientKineticAngles.renderTick() / 20 * head.rotationalSpeed;
            var expected = new Vertices();
            net.createmod.catnip.render.CachedBuffers.partial(com.simibubi.create.AllPartialModels.MECHANICAL_MIXER_HEAD, mixer.getBlockState())
                .rotateCentered((float) Math.toRadians(angle), net.minecraft.core.Direction.UP)
                .translate(head.x - mixer.getBlockPos().getX(), head.y - mixer.getBlockPos().getY(), head.z - mixer.getBlockPos().getZ())
                .light(0xf000f0).renderInto(new PoseStack(), expected);
            if (!same(expected.points, fallback)) throw new AssertionError("Flywheel mixer head differs from actual fallback mesh");
        }
    }

    private static final class Sink implements VisualizationContext, InstancerProvider {
        final List<RotatingInstance> rotating = new ArrayList<>();
        @Override public InstancerProvider instancerProvider() { return this; }
        @Override public Vec3i renderOrigin() { return BlockPos.ZERO; }
        @Override public VisualEmbedding createEmbedding(Vec3i origin) { throw new AssertionError("Unexpected embedding"); }
        @Override public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
            return new Instancer<>() {
                @Override public I createInstance() {
                    I instance = type.create(new InstanceHandle() {
                        @Override public void setChanged() {}
                        @Override public void setDeleted() {}
                        @Override public void setVisible(boolean visible) {}
                        @Override public boolean isVisible() { return true; }
                    });
                    if (instance instanceof RotatingInstance value) rotating.add(value);
                    return instance;
                }
                @Override public void stealInstance(I instance) { throw new AssertionError("Unexpected instance transfer"); }
            };
        }
    }

    private static List<Vec3> render(MechanicalMixerBlockEntity mixer) {
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(mixer);
        var head = new Vertices();
        var ignored = new Vertices();
        renderer.render(mixer, PonderUI.ponderPartialTicksPaused, new PoseStack(),
            type -> type == RenderType.cutoutMipped() ? head : ignored, 0xf000f0, 0);
        if (head.points.size() < 24) throw new AssertionError("Missing mixer head mesh");
        return head.points;
    }
    private static boolean same(List<Vec3> a, List<Vec3> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i).distanceToSqr(b.get(i)) > 1e-9) return false;
        return true;
    }
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
