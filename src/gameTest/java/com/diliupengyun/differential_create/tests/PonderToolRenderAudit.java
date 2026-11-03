package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Test actual native blade/wheel meshes, including the paused clock and tick boundaries. */
final class PonderToolRenderAudit {
    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var originalScreen = mc.screen;
        int originalClock = PonderUI.ponderTicks;
        float originalPartial = PonderUI.ponderPartialTicksPaused;
        var paused = PonderUI.class.getDeclaredField("identifyMode");
        paused.setAccessible(true);
        var issues = new ArrayList<String>();
        int samples = 0;
        try {
            for (var component : List.of(AllBlocks.MECHANICAL_HARVESTER, AllBlocks.MECHANICAL_ROLLER, AllBlocks.CONTRAPTION_CONTROLS)) {
                String id = component == AllBlocks.MECHANICAL_HARVESTER ? "mechanical_harvester"
                    : component == AllBlocks.MECHANICAL_ROLLER ? "mechanical_roller_pave" : "contraption_controls";
                var scene = PonderIndex.getSceneAccess().compile(component.getId()).stream()
                    .filter(candidate -> candidate.getId().getPath().equals(id)).findFirst().orElseThrow();
                var ui = new PonderUI(new ArrayList<>(List.of(scene))) {};
                paused.setBoolean(ui, true); mc.screen = ui; scene.begin();
                var previous = new IdentityHashMap<BlockEntity, List<Vec3>>();
                int moving = 0, stopped = 0;
                boolean clockFailure = false, continuityFailure = false;
                for (int tick = 0; tick < 3000 && pending(scene); tick++) {
                    PonderUI.ponderTicks = tick;
                    scene.tick();
                    var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<BlockEntity, Boolean>());
                    for (var be : scene.getWorld().getBlockEntities()) {
                        if (!(be instanceof HarvesterBlockEntity) && !(be instanceof RollerBlockEntity)) continue;
                        if (!PonderSimulation.of(scene.getWorld()).mountedBlock(be.getBlockPos())) continue;
                        seen.add(be);
                        float speed = be instanceof HarvesterBlockEntity harvester ? harvester.getAnimatedSpeed()
                            : ((RollerBlockEntity) be).getAnimatedSpeed();
                        if (speed != 0) moving++; else stopped++;
                        PonderUI.ponderPartialTicksPaused = 0;
                        var start = render(be);
                        var old = previous.get(be);
                        if (old != null && !same(old, start) && !continuityFailure) {
                            continuityFailure = true;
                            issues.add(id + " mesh jumps between ticks at " + scene.getCurrentTime());
                        }
                        for (float partial : new float[]{0, .5f, 1}) {
                            PonderUI.ponderPartialTicksPaused = partial;
                            var vertices = render(be);
                            PonderUI.ponderTicks += 137;
                            if (!same(vertices, render(be)) && !clockFailure) {
                                clockFailure = true;
                                issues.add(id + " mesh depends on UI clock at " + scene.getCurrentTime());
                            }
                            PonderUI.ponderTicks -= 137;
                            samples++;
                        }
                        previous.put(be, render(be));
                    }
                    // A later reassembly of a hidden template starts a new tool phase.
                    previous.keySet().retainAll(seen);
                    if (clockFailure && continuityFailure) break;
                }
                if (moving < 5 || stopped == 0) issues.add(id + " lacks moving/stopped mesh coverage: " + moving + "/" + stopped);
                if (id.equals("contraption_controls") && stopped < 50) issues.add("Disabled harvester mesh coverage is missing");
            }
            samples += sawMeshes(paused);
            samples += drillFacings(paused);
        } finally {
            mc.screen = originalScreen;
            PonderUI.ponderTicks = originalClock;
            PonderUI.ponderPartialTicksPaused = originalPartial;
        }
        if (!issues.isEmpty()) throw new AssertionError("Moving tool renderer: " + String.join("; ", issues));
        DifferentialCreate.LOGGER.info("DC_PONDER_TOOL_RENDER_PASS: {} actual blade/wheel mesh samples; clock independence, tick continuity and saw return state", samples);
    }

    private static int drillFacings(java.lang.reflect.Field paused) throws Exception {
        var scene = PonderIndex.getSceneAccess().compile(AllBlocks.MECHANICAL_DRILL.getId()).stream()
            .filter(candidate -> candidate.getId().getPath().equals("mechanical_drill_contraption")).findFirst().orElseThrow();
        var ui = new PonderUI(new ArrayList<>(List.of(scene))) {};
        paused.setBoolean(ui, true); Minecraft.getInstance().screen = ui;
        PonderUI.ponderPartialTicksPaused = 1; scene.begin();
        var simulation = PonderSimulation.of(scene.getWorld());
        com.simibubi.create.content.kinetics.drill.DrillBlockEntity drill = null;
        double localAngle = 0;
        for (int tick = 0; tick < 160 && drill == null; tick++) {
            scene.tick();
            for (var be : scene.getWorld().getBlockEntities()) {
                var phase = simulation.mountedToolAngle(be.getBlockPos(), 1);
                if (be instanceof com.simibubi.create.content.kinetics.drill.DrillBlockEntity candidate
                    && phase != null && Math.abs(Math.sin(Math.toRadians(phase))) > .1) { drill = candidate; localAngle = phase; break; }
            }
        }
        if (drill == null) throw new AssertionError("No nontrivial moving drill pose");
        for (var facing : net.minecraft.core.Direction.values()) {
            var state = drill.getBlockState().setValue(com.simibubi.create.content.kinetics.drill.DrillBlock.FACING, facing);
            drill.setBlockState(state);
            var expected = new Vertices();
            net.createmod.catnip.render.CachedBuffers.partial(com.simibubi.create.AllPartialModels.DRILL_HEAD, state)
                .center().rotateYDegrees(net.createmod.catnip.math.AngleHelper.horizontalAngle(facing))
                .rotateXDegrees(net.createmod.catnip.math.AngleHelper.verticalAngle(facing)).rotateZDegrees((float) localAngle)
                .uncenter().light(0xf000f0).renderInto(new PoseStack(), expected);
            if (!same(expected.points, render(drill))) throw new AssertionError("Moving drill mesh has wrong facing/shaft alignment: " + facing);
            var frame = com.diliupengyun.differential_create.client.ClientKineticAngles.rotationOf(drill);
            float offset = com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getRotationOffsetForPosition(drill, drill.getBlockPos(), facing.getAxis());
            var instanced = new Vertices();
            net.createmod.catnip.render.CachedBuffers.partialFacing(com.simibubi.create.AllPartialModels.DRILL_HEAD, state)
                .rotateCentered(net.createmod.catnip.math.AngleHelper.rad(frame.shaderOffset(offset)),
                    net.minecraft.core.Direction.get(net.minecraft.core.Direction.AxisDirection.POSITIVE, facing.getAxis()))
                .light(0xf000f0).renderInto(new PoseStack(), instanced);
            if (!same(expected.points, instanced.points)) throw new AssertionError("Moving drill shader phase has wrong facing: " + facing);
        }
        return 12;
    }

    private static int sawMeshes(java.lang.reflect.Field paused) throws Exception {
        var scene = PonderIndex.getSceneAccess().compile(AllBlocks.MECHANICAL_SAW.getId()).stream()
            .filter(candidate -> candidate.getId().getPath().equals("mechanical_saw_contraption")).findFirst().orElseThrow();
        var ui = new PonderUI(new ArrayList<>(List.of(scene))) {};
        paused.setBoolean(ui, true); Minecraft.getInstance().screen = ui; scene.begin();
        record Pose(net.createmod.ponder.api.element.WorldSectionElement owner, Vec3 point) {}
        var previous = new java.util.HashMap<net.minecraft.core.BlockPos, Pose>();
        var blade = com.simibubi.create.content.kinetics.saw.SawRenderer.class.getDeclaredMethod("renderBlade",
            com.simibubi.create.content.kinetics.saw.SawBlockEntity.class, PoseStack.class,
            net.minecraft.client.renderer.MultiBufferSource.class, int.class);
        blade.setAccessible(true);
        int active = 0, inactive = 0;
        for (int tick = 0; tick < 2000 && pending(scene); tick++) {
            PonderUI.ponderTicks = tick; PonderUI.ponderPartialTicksPaused = 1; scene.tick();
            for (var be : scene.getWorld().getBlockEntities()) {
                if (!(be instanceof com.simibubi.create.content.kinetics.saw.SawBlockEntity saw)
                    || !PonderSimulation.of(scene.getWorld()).mountedTool(be.getBlockPos())) continue;
                net.createmod.ponder.api.element.WorldSectionElement[] owner = {null};
                scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
                    if (((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section).differentialcreate$selection().test(be.getBlockPos())) owner[0] = section;
                });
                if (owner[0] == null) continue;
                var pose = new PoseStack();
                ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) owner[0]).transformMS(pose, 1);
                var matrix = pose.last().pose();
                var point = new Vec3(matrix.transformPosition(Vec3.atCenterOf(be.getBlockPos()).toVector3f()));
                var old = previous.put(be.getBlockPos(), new Pose(owner[0], point));
                if (old == null || old.owner() != owner[0]) continue;
                var motion = new Vec3(new org.joml.Matrix4f(matrix).invert().transformDirection(point.subtract(old.point()).toVector3f()));
                var state = saw.getBlockState();
                var facing = state.getValue(com.simibubi.create.content.kinetics.saw.SawBlock.FACING);
                boolean spinning = motion.length() >= 1 / 512d && motion.dot(Vec3.atLowerCornerOf(facing.getNormal())) > -.99 * motion.length();
                var model = com.simibubi.create.content.kinetics.saw.SawBlock.isHorizontal(state)
                    ? spinning ? com.simibubi.create.AllPartialModels.SAW_BLADE_HORIZONTAL_ACTIVE : com.simibubi.create.AllPartialModels.SAW_BLADE_HORIZONTAL_INACTIVE
                    : spinning ? com.simibubi.create.AllPartialModels.SAW_BLADE_VERTICAL_ACTIVE : com.simibubi.create.AllPartialModels.SAW_BLADE_VERTICAL_INACTIVE;
                var expected = new Vertices();
                var mesh = net.createmod.catnip.render.CachedBuffers.partialFacing(model, state);
                if (!com.simibubi.create.content.kinetics.saw.SawBlock.isHorizontal(state)
                    && state.getValue(com.simibubi.create.content.kinetics.saw.SawBlock.AXIS_ALONG_FIRST_COORDINATE))
                    mesh.rotateCentered(net.createmod.catnip.math.AngleHelper.rad(90), net.minecraft.core.Direction.UP);
                mesh.color(0xffffff).light(0xf000f0).renderInto(new PoseStack(), expected);
                var actual = new Vertices();
                blade.invoke(Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(saw), saw,
                    new PoseStack(), (net.minecraft.client.renderer.MultiBufferSource) ignored -> actual, 0xf000f0);
                if (!expected.uv.equals(actual.uv)) throw new AssertionError("Saw blade animation ignores actual feed direction at tick " + scene.getCurrentTime());
                if (spinning) active++; else inactive++;
            }
        }
        if (active < 100 || inactive < 100) throw new AssertionError("Missing active/returning saw blade mesh coverage");
        return active + inactive;
    }

    private static boolean pending(PonderScene scene) throws Exception {
        var schedule = PonderScene.class.getDeclaredField("activeSchedule"); schedule.setAccessible(true);
        return !((java.util.Collection<?>) schedule.get(scene)).isEmpty();
    }

    private static List<Vec3> render(BlockEntity be) {
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null) throw new AssertionError("Missing tool renderer: " + be.getBlockPos());
        var vertices = new Vertices();
        renderer.render(be, PonderUI.ponderPartialTicksPaused, new PoseStack(), ignored -> vertices, 0xf000f0, 0);
        return vertices.points;
    }

    private static boolean same(List<Vec3> a, List<Vec3> b) {
        if (a.size() <= 24 || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i).distanceToSqr(b.get(i)) > 1e-9) return false;
        return true;
    }

    private static final class Vertices implements VertexConsumer {
        final List<Vec3> points = new ArrayList<>();
        final List<Float> uv = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { points.add(new Vec3(x, y, z)); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { uv.add(u); uv.add(v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
