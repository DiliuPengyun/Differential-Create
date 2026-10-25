package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxVisual;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftVisual;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Vector3f;

/** Runs the real renderer and Flywheel visual constructors/updates, with an in-memory instance sink. */
final class ShaftRenderingAudit {
    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var screen = mc.screen;
        int clock = PonderUI.ponderTicks;
        float partial = PonderUI.ponderPartialTicksPaused;
        var ui = new PonderUI(new ArrayList<>(PonderIndex.getSceneAccess().compile(AllBlocks.CLUTCH.getId()))) {};
        var paused = PonderUI.class.getDeclaredField("identifyMode");
        paused.setAccessible(true);
        paused.setBoolean(ui, true);
        mc.screen = ui;
        int cases = 0;
        try {
            for (var block : List.of(AllBlocks.CLUTCH.get(), ModContent.BRASS_CLUTCH.get(), AllBlocks.GEARSHIFT.get(),
                AllBlocks.SEQUENCED_GEARSHIFT.get(), AllBlocks.GEARBOX.get()))
                for (var axis : Direction.Axis.values()) for (int parity = 0; parity < 2; parity++) {
                    var world = new PonderLevel(BlockPos.ZERO, mc.level);
                    var pos = new BlockPos(4, 4, 4).relative(axis == Direction.Axis.X ? Direction.UP : Direction.EAST, parity);
                    var state = block.defaultBlockState();
                    state = state.hasProperty(BlockStateProperties.AXIS) ? state.setValue(BlockStateProperties.AXIS, axis)
                        : state.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis == Direction.Axis.Y ? Direction.Axis.X : axis)
                            .setValue(SequencedGearshiftBlock.VERTICAL, axis == Direction.Axis.Y);
                    world.setBlock(pos, state, 3);
                    var be = (KineticBlockEntity) world.getBlockEntity(pos);
                    var faces = PonderShaftAudit.faces(be);
                    for (var face : faces) world.setBlock(pos.relative(face),
                        (face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                            ? AllBlocks.LARGE_COGWHEEL.getDefaultState() : AllBlocks.SHAFT.getDefaultState())
                            .setValue(BlockStateProperties.AXIS, face.getAxis()), 3);
                    for (var inlet : faces) {
                        var physics = PonderKinetics.of(world);
                        physics.reset();
                        physics.useExplicitSources();
                        be.source = pos.relative(inlet);
                        var sink = new InstanceSink();
                        var visual = visual(sink, be);
                        for (float target : new float[]{64, -32, 0}) {
                            if (state.hasProperty(BlockStateProperties.POWERED)) {
                                world.setBlock(pos, state.setValue(BlockStateProperties.POWERED, target != 64), 3);
                                be.setBlockState(world.getBlockState(pos));
                            }
                            physics.boundary(world, pos.relative(inlet), target);
                            for (int tick = 0; tick < 4; tick++) {
                                world.tick();
                                for (float pt : new float[]{0, .5f, 1}) {
                                    PonderUI.ponderPartialTicksPaused = pt;
                                    visual.update(pt);
                                    PonderShaftAudit.verifyMesh(be);
                                    verifyInstances(be, sink);
                                    PonderUI.ponderTicks += 137;
                                    visual.update(pt);
                                    PonderShaftAudit.verifyMesh(be);
                                    verifyInstances(be, sink);
                                    cases++;
                                }
                            }
                            var rebuilt = new InstanceSink();
                            var replacement = visual(rebuilt, be);
                            verifyInstances(be, rebuilt);
                            visual.delete();
                            visual = replacement;
                            sink = rebuilt;
                        }
                        visual.delete();
                    }
                }
            verifyFreeClutch();
            verifyGantry();
        } finally {
            mc.screen = screen;
            PonderUI.ponderTicks = clock;
            PonderUI.ponderPartialTicksPaused = partial;
        }
        DifferentialCreate.LOGGER.info("DC_SHAFT_RENDER_PASS: {} mesh/instance samples; five blocks, three axes, both model offsets, all input faces, reversal, coast, pause and rebuild", cases);
    }

    private static void verifyGantry() throws Exception {
        var cogAngle = com.simibubi.create.content.contraptions.gantry.GantryCarriageVisual.class.getDeclaredMethod("getCogAngle");
        var multiplier = com.simibubi.create.content.contraptions.gantry.GantryCarriageVisual.class.getDeclaredField("rotationMult");
        cogAngle.setAccessible(true); multiplier.setAccessible(true);
        for (var facing : Direction.values()) for (boolean along : new boolean[]{false, true}) {
            var world = new PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
            var pos = new BlockPos(3, 3, 3);
            world.setBlock(pos, AllBlocks.GANTRY_CARRIAGE.getDefaultState()
                .setValue(BlockStateProperties.FACING, facing)
                .setValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.AXIS_ALONG_FIRST_COORDINATE, along), 3);
            var be = (com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity) world.getBlockEntity(pos);
            var axis = com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getRotationAxisOf(be);
            var visualPos = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? pos : pos.relative(facing.getOpposite());
            var physics = PonderKinetics.of(world);
            physics.useExplicitSources();
            var visual = new com.simibubi.create.content.contraptions.gantry.GantryCarriageVisual(new InstanceSink(), be, 0);
            for (float target : new float[]{64, -32, 0, 16}) {
                physics.boundary(world, pos, target);
                for (int tick = 0; tick < 8; tick++) {
                    world.tick();
                    for (float pt : new float[]{0, .5f, 1}) {
                        PonderUI.ponderPartialTicksPaused = pt;
                        double expected = physics.angle(be, pt)
                            + com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getRotationOffsetForPosition(be, visualPos, axis);
                        double angle = com.simibubi.create.content.contraptions.gantry.GantryCarriageRenderer.getAngleForBE(be, visualPos, axis);
                        require(Math.abs(Math.IEEEremainder(angle - expected, 360)) < .002, "Gantry cog lost its integrated phase");
                        double instanced = (float) cogAngle.invoke(visual) / multiplier.getFloat(visual);
                        require(Math.abs(Math.IEEEremainder(instanced - expected, 360)) < .002, "Gantry Flywheel phase differs from fallback renderer");
                        PonderUI.ponderTicks += 137;
                        double after = com.simibubi.create.content.contraptions.gantry.GantryCarriageRenderer.getAngleForBE(be, visualPos, axis);
                        require(Math.abs(after - angle) < .002, "Gantry phase depends on the global animation clock");
                    }
                }
            }
            visual.delete();
        }
        DifferentialCreate.LOGGER.info("DC_GANTRY_RENDER_PASS: all 12 orientations, changing speed, reversal, coast and interpolation; fallback and Flywheel share integrated phase");
    }

    private static KineticBlockEntityVisual<?> visual(InstanceSink sink, KineticBlockEntity be) {
        return be instanceof GearboxBlockEntity gearbox ? new GearboxVisual(sink, gearbox, 0)
            : new SplitShaftVisual(sink, (SplitShaftBlockEntity) be, 0);
    }

    private static void verifyInstances(KineticBlockEntity be, InstanceSink sink) {
        var faces = PonderShaftAudit.faces(be);
        require(sink.instances.size() == faces.size(), "Incorrect Flywheel face count");
        for (int i = 0; i < faces.size(); i++) {
            var face = faces.get(i);
            var instance = sink.instances.get(i);
            var neighbour = (KineticBlockEntity) be.getLevel().getBlockEntity(be.getBlockPos().relative(face));
            double expected = Math.toDegrees(ClientKineticAngles.shaftAngleOf(neighbour));
            double actual = instance.rotationOffset + ClientKineticAngles.renderTick() / 20f * instance.rotationalSpeed;
            require(Math.abs(Math.IEEEremainder(actual - expected, 360)) < .002,
                "Flywheel face differs from adjoining shaft: " + be.getBlockState() + " " + face + " " + actual + " vs " + expected);
            var facing = instance.rotation.transform(new Vector3f(0, 0, 1));
            require(facing.distanceSquared(new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ())) < 1e-10,
                "Flywheel half-shaft points at the wrong face");
            require(instance.rotationAxisX == (face.getAxis() == Direction.Axis.X ? 127 : 0)
                && instance.rotationAxisY == (face.getAxis() == Direction.Axis.Y ? 127 : 0)
                && instance.rotationAxisZ == (face.getAxis() == Direction.Axis.Z ? 127 : 0), "Wrong Flywheel rotation axis");
        }
    }

    /** An open, exposed output must keep its last angle and continue coasting independently. */
    private static void verifyFreeClutch() {
        var world = new PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        var pos = new BlockPos(2, 2, 2);
        world.setBlock(pos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X), 3);
        for (var face : List.of(Direction.EAST, Direction.WEST))
            world.setBlock(pos.relative(face), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X), 3);
        var clutch = (ClutchBlockEntity) world.getBlockEntity(pos);
        clutch.source = pos.west();
        var physics = PonderKinetics.of(world);
        physics.useExplicitSources();
        physics.boundary(world, pos.west(), 64);
        for (int i = 0; i < 40; i++) world.tick();
        world.setBlock(pos, world.getBlockState(pos).setValue(BlockStateProperties.POWERED, true), 3);
        world.tick();
        double before = physics.clutchOutputAngle(clutch, 1);
        world.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), 3);
        world.tick();
        require(Math.abs(before - physics.clutchOutputAngle(clutch, 0)) < 1e-6, "Removing an output shaft resets the clutch phase");
        require(Math.abs(physics.clutchOutputAngle(clutch, 1) - before) > .01, "Exposed open clutch output loses its coasting motion");
        var sink = new InstanceSink();
        var visual = visual(sink, clutch);
        int output = PonderShaftAudit.faces(clutch).indexOf(Direction.EAST);
        double angle = physics.clutchOutputAngle(clutch, PonderUI.ponderPartialTicksPaused)
            + ClientKineticAngles.shaftOffsetOf(clutch, Direction.Axis.X);
        require(Math.abs(Math.IEEEremainder(sink.instances.get(output).rotationOffset - angle, 360)) < .002,
            "Rebuilding an exposed clutch loses its output phase");
        visual.delete();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class InstanceSink implements VisualizationContext, InstancerProvider {
        final List<RotatingInstance> instances = new ArrayList<>();
        @Override public InstancerProvider instancerProvider() { return this; }
        @Override public Vec3i renderOrigin() { return BlockPos.ZERO; }
        @Override public VisualEmbedding createEmbedding(Vec3i origin) { throw new AssertionError("Unexpected embedded visual"); }
        @Override public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
            return new Instancer<>() {
                @Override public I createInstance() {
                    I instance = type.create(new InstanceHandle() {
                        @Override public void setChanged() {}
                        @Override public void setDeleted() {}
                        @Override public void setVisible(boolean visible) {}
                        @Override public boolean isVisible() { return true; }
                    });
                    if (instance instanceof RotatingInstance rotating) instances.add(rotating);
                    return instance;
                }
                @Override public void stealInstance(I instance) { throw new AssertionError("Unexpected instance transfer"); }
            };
        }
    }
}
