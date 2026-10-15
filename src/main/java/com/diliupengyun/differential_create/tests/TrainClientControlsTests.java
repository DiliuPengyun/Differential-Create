package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.diliupengyun.differential_create.trains.TrainStatePayload;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsMovementBehaviour;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsRenderer;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import io.netty.buffer.Unpooled;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.FriendlyByteBuf;

/** Exercise the transformed native actor, smoothing and actual emitted controller geometry. */
public final class TrainClientControlsTests {
    private static final ControlsMovementBehaviour BEHAVIOR = new ControlsMovementBehaviour();
    private TrainClientControlsTests() {}

    public static void verify(Minecraft mc, CarriageContraptionEntity entity, BlockPos cab) {
        var original = ClientTrainKinetics.state(entity.trainId);
        var originalKeys = ControlsHandler.currentlyPressed;
        var blocks = entity.getContraption().getBlocks(); var originalBlock = blocks.get(cab);
        boolean backwards = entity.movingBackwards;
        int cases = 0;
        try {
            for (int gear = 0; gear <= TrainMechanics.REVERSE_GEAR; gear++)
                for (boolean inverted : new boolean[]{false, true}) for (double speed : new double[]{0, -.8, .8})
                    for (double admission : new double[]{0, .25, 1}) for (boolean brake : new boolean[]{false, true}) {
                        var facing = entity.getInitialOrientation().getCounterClockWise();
                        var block = new StructureBlockInfo(cab, originalBlock.state().setValue(ControlsBlock.FACING,
                            inverted ? facing.getOpposite() : facing), originalBlock.nbt());
                        blocks.put(cab, block); entity.movingBackwards = speed < 0;
                        int steering = cases % 3 - 1;
                        var keys = new ArrayList<Integer>();
                        if (admission > 0) keys.add(0);
                        if (brake) keys.add(1);
                        if (steering != 0) keys.add(steering > 0 ? 3 : 2);
                        ControlsHandler.currentlyPressed = keys;
                        sample(original, entity, gear, speed, admission, brake);
                        var context = new MovementContext(mc.level, block, entity.getContraption());
                        context.motion = new Vec3(0, 0, speed);
                        BEHAVIOR.tick(context); render(context);
                        float expected = brake ? (float) (-2d / 7) : (float) (admission * .5);
                        var handle = angle(context, "speed");
                        if (Math.abs(handle.getChaseTarget() - expected) > 1e-6
                            || angle(context, "steering").getChaseTarget() != steering)
                            throw new AssertionError("Native controller must show admission/braking and preserve junction steering: gear="
                                + gear + " inverted=" + inverted + " speed=" + speed + " admission=" + admission + " brake=" + brake);
                        for (int tick = 0; tick < 80; tick++) BEHAVIOR.tick(context);
                        var actual = render(context); var reference = new Vertices();
                        ControlsRenderer.render(context, entity.getContraption().getOrCreateClientContraptionLazy().getRenderLevel(),
                            new ContraptionMatrices(), type -> reference, 1, expected, steering);
                        if (actual.isEmpty() || !same(actual, reference.vertices))
                            throw new AssertionError("Controller lever did not reach the native regulator/brake geometry");
                        cases++;
                    }
            // Releasing W must smoothly return a moving train's handle, rather than reporting its speed.
            blocks.put(cab, originalBlock); ControlsHandler.currentlyPressed = List.of();
            sample(original, entity, 1, .8, 1, false);
            var context = new MovementContext(mc.level, originalBlock, entity.getContraption());
            context.motion = new Vec3(0, 0, .8);
            BEHAVIOR.tick(context); render(context);
            for (int tick = 0; tick < 80; tick++) BEHAVIOR.tick(context);
            sample(original, entity, 1, .8, 0, false); render(context);
            BEHAVIOR.tick(context);
            var handle = angle(context, "speed");
            if (handle.getChaseTarget() != 0 || !(handle.getValue(0) > handle.getValue(.5f)
                && handle.getValue(.5f) > handle.getValue(1) && handle.getValue(1) > 0))
                throw new AssertionError("Releasing admission must interpolate back to rest while the train coasts");
            cases++;

            // Losing updates must not leave a permanently open regulator.
            sample(original, entity, 1, 0, 1, false); stale(entity); render(context);
            if (handle.getChaseTarget() != 0) throw new AssertionError("Stale controller snapshot kept admission open");
            cases++;

            // An unused cab stays folded and returns its previous drive angle while retracting.
            var unused = new StructureBlockInfo(cab.above(), originalBlock.state(), null);
            var idle = new MovementContext(mc.level, unused, entity.getContraption());
            BEHAVIOR.tick(idle); angle(idle, "speed").startWithValue(.5);
            render(idle); BEHAVIOR.tick(idle);
            if (angle(idle, "equipAnimation").getChaseTarget() != 0 || angle(idle, "speed").getValue(1) >= .5)
                throw new AssertionError("Unused cab unfolded or retained its old drive angle");
            cases++;

            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.001, 1.001}) {
                boolean rejected = false;
                try { sample(original, entity, 1, 0, invalid, false); }
                catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("Invalid controller admission accepted by the native packet codec");
                cases++;
            }
            DifferentialCreate.LOGGER.info("DC_TRAIN_CONTROLS_ANIMATION_PASS: {} native actor, geometry, all-gear/cab, stationary/coast/reverse, intake/brake, steering, smoothing, stale and codec checks", cases);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            blocks.put(cab, originalBlock); entity.movingBackwards = backwards;
            ControlsHandler.currentlyPressed = originalKeys; ClientTrainKinetics.apply(original);
        }
    }

    private static LerpedFloat angle(MovementContext context, String name) throws ReflectiveOperationException {
        var field = context.temporaryData.getClass().getDeclaredField(name); field.setAccessible(true);
        return (LerpedFloat) field.get(context.temporaryData);
    }
    private static void sample(TrainStatePayload original, CarriageContraptionEntity entity, int gear, double speed,
        double admission, boolean brake) {
        var packet = new TrainStatePayload(original.dimension(), entity.getId(), entity.trainId, gear, true, speed,
            1, 0, "", original.rotors(), original.boilers(), admission, brake);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TrainStatePayload.STREAM_CODEC.encode(buffer, packet);
            var decoded = TrainStatePayload.STREAM_CODEC.decode(buffer);
            if (!packet.equals(decoded) || buffer.isReadable()) throw new AssertionError("Cab state did not round-trip exactly");
            ClientTrainKinetics.apply(decoded);
        } finally { buffer.release(); }
    }
    @SuppressWarnings("unchecked")
    private static void stale(CarriageContraptionEntity entity) throws ReflectiveOperationException {
        var field = ClientTrainKinetics.class.getDeclaredField("CARRIAGES"); field.setAccessible(true);
        var cache = (Map<CarriageContraptionEntity, ClientTrainKinetics.CarriageSample>) field.get(null);
        cache.put(entity, new ClientTrainKinetics.CarriageSample(cache.get(entity).rotors(), ClientKineticAngles.renderTick() - 11));
    }
    private static List<Vertex> render(MovementContext context) {
        var vertices = new Vertices();
        BEHAVIOR.renderInContraption(context, context.contraption.getOrCreateClientContraptionLazy().getRenderLevel(),
            new ContraptionMatrices(), type -> vertices);
        return vertices.vertices;
    }
    private record Vertex(float x, float y, float z) {}
    private static boolean same(List<Vertex> a, List<Vertex> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i); var y = b.get(i);
            if (Math.abs(x.x - y.x) > 1e-5 || Math.abs(x.y - y.y) > 1e-5 || Math.abs(x.z - y.z) > 1e-5) return false;
        }
        return true;
    }
    private static final class Vertices implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { vertices.add(new Vertex(x, y, z)); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
