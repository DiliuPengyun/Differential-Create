package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.SteamHoseGeometry;
import com.diliupengyun.differential_create.client.SteamHoseRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.diliupengyun.differential_create.trains.SteamHosePort;
import com.diliupengyun.differential_create.trains.SteamBogey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Frame-level hose checks using native client carriage transforms and the actual emitted tube vertices. */
public final class TrainClientHoseRenderTests {
    private TrainClientHoseRenderTests() {}

    public static void verify(Minecraft mc) throws ReflectiveOperationException {
        Method transform = SteamHoseRenderer.class.getDeclaredMethod("transformCarriage", PoseStack.class,
            CarriageContraptionEntity.class, Vec3.class, float.class);
        Method tube = SteamHoseRenderer.class.getDeclaredMethod("tube", PoseStack.class, VertexConsumer.class,
            List.class, double.class, TextureAtlasSprite.class, float.class, float.class, float.class, float.class,
            int.class, UnaryOperator.class);
        transform.setAccessible(true); tube.setAccessible(true);
        var sprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ResourceLocation.fromNamespaceAndPath("create", "block/hose"));
        int cases = 0;
        double oldJump = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var carriage = CarriageContraptionEntity.create(mc.level, new CarriageContraption(facing));
            var wheel = AllBlocks.SMALL_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, facing.getAxis());
            var hose = SteamHoseGeometry.connect(wheel, new Vec3(.5, .5, .5), new AABB(1, 0, 3, 3, 3, 5), new Vec3(1.5, 1.5, 3.5),
                new com.diliupengyun.differential_create.trains.SteamHosePort(Direction.NORTH, new Vec3(.5, .5, 0)));
            for (double distance : new double[]{0, 10_000.25, 29_000_000.25}) for (boolean slope : new boolean[]{false, true}) {
                carriage.prevYaw = carriage.yaw = carriage.getInitialYaw() + (slope ? 37 : 0);
                carriage.prevPitch = carriage.pitch = slope ? 20 : 0;
                Vec3 origin = new Vec3(distance, -58.25, -distance), camera = origin.add(3.125, 2.75, -5.25);
                for (double speed : new double[]{0, .125, -.125, 2, -2}) {
                    Vec3 motion = new Vec3(0, slope ? speed / 4 : 0, speed);
                    carriage.setPos(origin.add(motion));
                    carriage.xOld = carriage.xo = origin.x;
                    carriage.yOld = carriage.yo = origin.y;
                    carriage.zOld = carriage.zo = origin.z;
                    var end = mesh(transform, tube, carriage, camera, 1, hose, sprite);
                    for (float partial : new float[]{0, .1f, .25f, .5f, .75f, .9f, 1}) {
                        var frame = mesh(transform, tube, carriage, camera, partial, hose, sprite);
                        if (frame.size() != end.size() || frame.isEmpty()) throw new AssertionError("Moving hose changed vertex count");
                        Vec3 shift = motion.scale(partial - 1);
                        for (int i = 0; i < frame.size(); i++) {
                            var actual = frame.get(i); var previous = end.get(i);
                            if (actual.point.distanceTo(previous.point.add(shift)) > 2e-5
                                || actual.u != previous.u || actual.v != previous.v || !actual.normal.equals(previous.normal))
                                throw new AssertionError("Hose must translate smoothly with its carriage, retaining shape/UVs: facing="
                                    + facing + " distance=" + distance + " slope=" + slope + " speed=" + speed + " partial=" + partial);
                        }
                        cases++;
                    }
                    // Reproduce the former tick-only anchor independently of the new rendering path.
                    if (distance == 0 && !slope && speed == 2) {
                        Vec3 point = hose.engine().point();
                        var pose = new PoseStack(); transform.invoke(null, pose, carriage, camera, .5f);
                        var tip = pose.last().pose().transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
                        oldJump = Math.max(oldJump, carriage.toGlobalVector(point, .5f)
                            .distanceTo(new Vec3(tip.x, tip.y, tip.z).add(camera)));
                    }
                }
            }
            // A moving frame must match the same carriage frozen at that frame's native interpolated pose.
            for (double distance : new double[]{0, 10_000.25, 29_000_000.25}) {
                Vec3 origin = new Vec3(distance, -58.25, -distance), current = origin.add(1, .25, -2);
                Vec3 camera = origin.add(3.125, 2.75, -5.25);
                for (float partial : new float[]{0, .1f, .25f, .5f, .75f, .9f, 1}) {
                    carriage.setPos(current);
                    carriage.xOld = carriage.xo = origin.x;
                    carriage.yOld = carriage.yo = origin.y;
                    carriage.zOld = carriage.zo = origin.z;
                    carriage.prevYaw = carriage.getInitialYaw() + 179; carriage.yaw = carriage.getInitialYaw() - 179;
                    carriage.prevPitch = -15; carriage.pitch = 20;
                    var moving = mesh(transform, tube, carriage, camera, partial, hose, sprite);
                    Vec3 at = carriage.getPosition(partial);
                    float yaw = -carriage.getViewYRot(partial), pitch = carriage.getViewXRot(partial);
                    carriage.setPos(at); carriage.prevYaw = carriage.yaw = yaw; carriage.prevPitch = carriage.pitch = pitch;
                    var frozen = mesh(transform, tube, carriage, camera, 1, hose, sprite);
                    if (moving.size() != frozen.size()) throw new AssertionError("Turning hose changed vertex count");
                    for (int i = 0; i < moving.size(); i++) {
                        var actual = moving.get(i); var expected = frozen.get(i);
                        if (actual.point.distanceTo(expected.point) > 2e-5 || actual.u != expected.u || actual.v != expected.v
                            || actual.normal.distanceTo(expected.normal) > 2e-5)
                            throw new AssertionError("Hose differs from the native frozen frame while turning/changing slope: "
                                + facing + " distance=" + distance + " partial=" + partial);
                    }
                    cases++;
                }
            }
        }
        if (oldJump < .99) throw new AssertionError("Motion fixture did not expose the former tick-only hose jump");
        crossCarriages(mc, transform, tube, sprite);
        DifferentialCreate.LOGGER.info("DC_TRAIN_HOSE_MOTION_PASS: {} actual mesh frames; stationary/forward/reverse, four orientations, turns/slopes and near/far coordinates; former midpoint jump={} blocks",
            cases, oldJump);
    }

    private static void crossCarriages(Minecraft mc, Method transform, Method tube, TextureAtlasSprite sprite)
        throws ReflectiveOperationException {
        var boiler = new CarriageContraption(Direction.SOUTH); boiler.anchor = new BlockPos(278, -58, 181);
        for (int x = -1; x <= 1; x++) for (int y = 1; y <= 2; y++) for (int z = 2; z <= 4; z++) {
            var at = new BlockPos(x, y, z); var data = new CompoundTag();
            if (x == -1 && y == 1 && z == 2) { data.putInt("Size", 3); data.putInt("Height", 2); }
            boiler.getBlocks().put(at, new StructureBlockInfo(at, AllBlocks.FLUID_TANK.getDefaultState(), data));
        }
        var source = CarriageContraptionEntity.create(mc.level, boiler); source.trainId = UUID.randomUUID();
        var carriages = new ArrayList<CarriageContraptionEntity>(); carriages.add(source);
        int[] offsets = {9, 18, 36, 52};
        for (int i = 0; i < 4; i++) {
            var cc = new CarriageContraption(Direction.SOUTH); cc.anchor = boiler.anchor.offset(0, i == 3 ? 1 : 0, offsets[i]);
            var data = new CompoundTag(); var bogey = new CompoundTag();
            bogey.putBoolean(SteamBogey.ENGINE, true);
            bogey.putLong(SteamHoseLinks.ENGINE_KEY, boiler.anchor.offset(0, 1, 4).subtract(cc.anchor).asLong());
            SteamHosePort.write(bogey, new SteamHosePort(Direction.SOUTH, new Vec3(.5, .5, 1)));
            data.put("BogeyData", bogey);
            cc.getBlocks().put(BlockPos.ZERO, new StructureBlockInfo(BlockPos.ZERO,
                AllBlocks.SMALL_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z), data));
            var engine = CarriageContraptionEntity.create(mc.level, cc); engine.trainId = source.trainId; carriages.add(engine);
        }
        int cases = 0;
        for (double distance : new double[] {0, 29_000_000.25}) for (boolean turning : new boolean[] {false, true}) {
            Vec3 origin = new Vec3(distance, -58, -distance), camera = origin.add(3, 4, -5);
            for (int i = 0; i < carriages.size(); i++) {
                var entity = carriages.get(i); var previous = origin.add(0, i == 4 ? 1 : 0, i == 0 ? 0 : offsets[i - 1]);
                entity.setPos(previous.add(i * .13, turning ? i * .04 : 0, .25 + i * .1));
                entity.xOld = entity.xo = previous.x; entity.yOld = entity.yo = previous.y; entity.zOld = entity.zo = previous.z;
                entity.prevYaw = entity.getInitialYaw(); entity.yaw = entity.getInitialYaw() + (turning ? i * 17 : 0);
                entity.prevPitch = 0; entity.pitch = turning ? i * 4 : 0;
            }
            for (float partial : new float[] {0, .25f, .5f, .75f, 1}) for (int i = 1; i < carriages.size(); i++) {
                var engine = carriages.get(i); var block = engine.getContraption().getBlocks().get(BlockPos.ZERO);
                var hose = SteamHoseRenderer.mountedConnection(engine, block, carriages, partial);
                if (hose == null) throw new AssertionError("A saved cross-carriage hose must render: " + i);
                var from = renderedPoint(transform, engine, camera, partial, new Vec3(.5, 1.015, .5));
                var to = renderedPoint(transform, source, camera, partial, new Vec3(.5, 1.5, 5.015));
                if (hose.engine().point().distanceTo(from) > 2e-5 || hose.tank().point().distanceTo(to) > 2e-5)
                    throw new AssertionError("Cross-carriage endpoints do not follow their own native frame: " + i + " partial=" + partial);
                var pose = new PoseStack(); var originPoint = hose.engine().point();
                pose.translate(originPoint.x - camera.x, originPoint.y - camera.y, originPoint.z - camera.z);
                var vertices = new Vertices();
                tube.invoke(null, pose, vertices, hose.points().stream().map(p -> p.subtract(originPoint)).toList(),
                    SteamHoseGeometry.RADIUS, sprite, 12f, 16f, 0f, 16f, 0xFFFFFF, (UnaryOperator<Vec3>) p -> p.add(originPoint));
                vertices.flush();
                if (vertices.vertices.isEmpty() || vertices.vertices.stream().anyMatch(v -> !Double.isFinite(v.point.lengthSqr())))
                    throw new AssertionError("Cross-carriage hose must emit a finite, nonempty mesh");
                cases++;
            }
        }
        var engine = carriages.get(1); var block = engine.getContraption().getBlocks().get(BlockPos.ZERO);
        UUID correctTrain = source.trainId; source.trainId = UUID.randomUUID();
        if (SteamHoseRenderer.mountedConnection(engine, block, carriages, .5f) != null)
            throw new AssertionError("A hose must never render to another Train at the same assembly coordinates");
        source.trainId = correctTrain;
        if (SteamHoseRenderer.mountedConnection(engine, block, List.of(engine), .5f) != null)
            throw new AssertionError("A missing destination carriage must not leave a floating hose");
        var last = carriages.getLast(); var lastBlock = last.getContraption().getBlocks().get(BlockPos.ZERO);
        lastBlock.nbt().getCompound("BogeyData").putLong(SteamHoseLinks.ENGINE_KEY, new BlockPos(0, 0, -49).asLong());
        if (SteamHoseRenderer.mountedConnection(last, lastBlock, carriages, .5f) != null)
            throw new AssertionError("A saved 49-block hose must be rejected even when its target tank exists");
        DifferentialCreate.LOGGER.info("DC_TRAIN_CROSS_HOSE_RENDER_PASS: {} meshes for four separate carriages up to 48 blocks, independent turns/slopes/interpolation and far coordinates; 49 blocks/different Train/missing destination rejected", cases);
    }

    private static Vec3 renderedPoint(Method transform, CarriageContraptionEntity carriage, Vec3 camera, float partial, Vec3 local)
        throws ReflectiveOperationException {
        var pose = new PoseStack(); transform.invoke(null, pose, carriage, camera, partial);
        var point = pose.last().pose().transformPosition(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        return new Vec3(point.x, point.y, point.z).add(camera);
    }

    private static List<Vertex> mesh(Method transform, Method tube, CarriageContraptionEntity carriage, Vec3 camera,
        float partial, SteamHoseGeometry.Connection hose, TextureAtlasSprite sprite) throws ReflectiveOperationException {
        var pose = new PoseStack();
        transform.invoke(null, pose, carriage, camera, partial);
        var vertices = new Vertices();
        UnaryOperator<Vec3> lightPoint = point -> carriage.toGlobalVector(point, partial);
        tube.invoke(null, pose, vertices, hose.points(), SteamHoseGeometry.RADIUS, sprite, 12f, 16f, 0f, 16f, 0xFFFFFF, lightPoint);
        vertices.flush(); return vertices.vertices;
    }

    private record Vertex(Vec3 point, float u, float v, Vec3 normal) {}
    private static final class Vertices implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private Vec3 point, normal;
        private float u, v;
        private void flush() {
            if (point != null) vertices.add(new Vertex(point, u, v, normal));
            point = null;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            flush(); point = new Vec3(x, y, z); normal = Vec3.ZERO; u = v = 0; return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { normal = new Vec3(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
    }
}
