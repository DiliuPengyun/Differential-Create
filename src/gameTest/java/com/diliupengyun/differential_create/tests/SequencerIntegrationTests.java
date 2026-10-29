package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual server ticks, native program UI state, physical rotors and NBT replacement. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class SequencerIntegrationTests {
    private SequencerIntegrationTests() {}
    private static void add(Collection<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            tests.add(new TestFunction("motion_" + name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 1400, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (var axis : Direction.Axis.values()) for (int sign : new int[]{-1, 1}) for (int ratio : new int[]{-2, -1, 1, 2})
            for (String program : new String[]{"stop", "blend", "slower", "faster", "reverse", "short", "delay", "await", "distance", "mixed", "zero", "max_angle", "max_distance", "max_delay", "motion_sync"}) {
                String name = "servo_" + axis.name().toLowerCase() + "_" + (sign > 0 ? "positive" : "negative") + "_r" + (ratio < 0 ? "minus" + -ratio : ratio) + "_" + program;
                add(tests, name, h -> runCase(h, axis, sign, ratio, program, "normal", false));
            }
        for (var axis : Direction.Axis.values()) for (int sign : new int[]{-1, 1})
            for (String lifecycle : new String[]{"reload", "power", "source_reverse", "load", "remove_output", "restart", "redstone", "probe", "held_await", "unpowered_start", "mirror_unpowered"}) {
                String name = "lifecycle_" + axis.name().toLowerCase() + "_" + (sign > 0 ? "positive" : "negative") + "_" + lifecycle;
                add(tests, name, h -> runCase(h, axis, sign, 1, lifecycle.equals("held_await") ? "await" : "blend", lifecycle, false));
            }
        for (var axis : Direction.Axis.values()) for (int sign : new int[]{-1, 1}) for (int ratio : new int[]{-2, -1, 1, 2})
            for (String pattern : new String[]{"stop", "blend"}) {
                String name = "mirror_" + axis.name().toLowerCase() + "_" + (sign > 0 ? "positive" : "negative") + "_r" + (ratio < 0 ? "minus" + -ratio : ratio) + "_" + pattern;
                add(tests, name, h -> runCase(h, axis, sign, ratio, pattern, "mirror", false));
            }
        for (var axis : Direction.Axis.values()) for (int sign : new int[]{-1, 1})
            for (String pattern : new String[]{"stop", "blend", "reverse", "short"}) {
                String name = "bearing_" + axis.name().toLowerCase() + "_" + (sign > 0 ? "positive" : "negative") + "_" + pattern;
                add(tests, name, h -> runCase(h, axis, sign, 1, pattern, "bearing", false));
            }
        for (var axis : Direction.Axis.values()) for (int sign : new int[]{-1, 0, 1}) for (float inertia : new float[]{0, .01f, .25f, .5f, 1, 8}) {
            String name = "inertia_" + axis.name().toLowerCase() + "_sign" + (sign < 0 ? "minus1" : sign) + "_j" + Float.toString(inertia).replace('.', '_');
            add(tests, name, h -> inertia(h, axis, sign, inertia));
        }
        for (var axis : Direction.Axis.values()) for (var side : Direction.AxisDirection.values())
            for (String lifecycle : new String[]{"align", "extend", "reload", "dual"})
                add(tests, "phase_" + axis.name().toLowerCase() + "_" + side.name().toLowerCase() + "_" + lifecycle,
                    h -> outputPhase(h, axis, side, lifecycle));
        add(tests, "virtual_demo", SequencerIntegrationTests::virtualDemo);
        return tests;
    }
    private static void outputPhase(GameTestHelper h, Direction.Axis axis, Direction.AxisDirection side, String lifecycle) {
        BlockPos pos = new BlockPos(8, 4, 8);
        Direction output = Direction.fromAxisAndDirection(axis, side);
        h.setBlock(pos, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState().setValue(SequencedGearshiftBlock.VERTICAL, axis == Direction.Axis.Y)
            .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X));
        h.setBlock(pos.relative(output.getOpposite()), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, output));
        h.setBlock(pos.relative(output), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
        h.setBlock(pos.relative(output, 2), lifecycle.equals("dual")
            ? AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState().setValue(SequencedGearshiftBlock.VERTICAL, axis == Direction.Axis.Y)
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X)
            : AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
        if (lifecycle.equals("dual")) {
            h.setBlock(pos.relative(output, 3), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, output.getOpposite()));
            CreativeMotorBlockEntity otherMotor = h.getBlockEntity(pos.relative(output, 3));
            otherMotor.generatedSpeed.setValue(32);
        }
        CreativeMotorBlockEntity motor = h.getBlockEntity(pos.relative(output.getOpposite()));
        motor.generatedSpeed.setValue(32);
        h.runAfterDelay(5, () -> {
            SequencedGearshiftBlockEntity seq = h.getBlockEntity(pos);
            NetworkTopology.mark(seq); NetworkTopology.flush();
            var m = ((ISequencerDynamics) seq).differentialcreate$motion();
            m.angle = m.target = 1.00842464243087;
            m.phaseOffset = .125;
            m.speed = 0;
            seq.getInstructions().clear();
            seq.getInstructions().add(new Instruction(SequencerInstructions.TURN_ANGLE, 90));
            seq.getInstructions().add(new Instruction(SequencerInstructions.END, 1));
            seq.run(0); NetworkTopology.flush();
            KineticBlockEntity shaft = h.getBlockEntity(pos.relative(output));
            KineticBlockEntity flywheel = h.getBlockEntity(pos.relative(output, 2));
            SequencerMotion other = null;
            if (flywheel instanceof SequencedGearshiftBlockEntity second) {
                other = ((ISequencerDynamics) second).differentialcreate$motion();
                other.angle = other.target = -2.25; other.phaseOffset = .37; other.speed = 0;
                second.getInstructions().clear();
                second.getInstructions().add(new Instruction(SequencerInstructions.TURN_ANGLE, 45));
                second.getInstructions().add(new Instruction(SequencerInstructions.END, 1));
                second.run(0); NetworkTopology.flush();
            }
            // Independent saved assembly zeros reproduce the stopped seam in the report.
            ElasticKinetics.state(shaft).angle = 1176.4585832324276;
            if (other == null) ElasticKinetics.state(flywheel).angle = 1176.4585832324276;
            var network = seq.getOrCreateNetwork();
            var edges = ((INetworkDynamics) network).differentialcreate$data().connections;
            for (var edge : edges) if (SequencerKinetics.outputOwner(edge) == seq) {
                edge.twist = side == Direction.AxisDirection.POSITIVE ? .00005 : -.00005;
                edge.save();
            }
            double angle = m.angle, start = m.start, target = m.target, progress = m.progress(), speed = m.speed;
            double inputSpeed = NetworkDynamics.preciseSpeed(seq), inputAngle = ElasticKinetics.state(seq).angle;
            double energy = ElasticKinetics.elasticEnergy(network).total();
            double otherAngle = other == null ? 0 : other.angle, otherStart = other == null ? 0 : other.start, otherTarget = other == null ? 0 : other.target;
            ElasticKinetics.alignAngles(network);
            assertOutputPhase(h, seq);
            double phaseOffset = m.phaseOffset;
            if (lifecycle.equals("extend")) {
                h.setBlock(pos.relative(output, 3), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
                NetworkTopology.mark(seq); NetworkTopology.flush();
            } else if (lifecycle.equals("reload")) {
                var tag = seq.saveWithFullMetadata(h.getLevel().registryAccess());
                NetworkTopology.mark(seq);
                seq = (SequencedGearshiftBlockEntity) BlockEntity.loadStatic(seq.getBlockPos(), seq.getBlockState(), tag, h.getLevel().registryAccess());
                h.getLevel().setBlockEntity(seq); NetworkTopology.mark(seq); NetworkTopology.flush();
            }
            m = ((ISequencerDynamics) seq).differentialcreate$motion();
            assertOutputPhase(h, seq);
            h.assertTrue(m.angle == angle && m.start == start && m.target == target && m.progress() == progress && m.speed == speed,
                "assembly phase repair preserves the active servo coordinate, target, progress and velocity");
            h.assertTrue(m.phaseOffset == phaseOffset, "reload and extension retain the output's assembly zero");
            if (other != null) {
                assertOutputPhase(h, (SequencedGearshiftBlockEntity) flywheel);
                h.assertTrue(other.angle == otherAngle && other.start == otherStart && other.target == otherTarget && other.speed == 0,
                    "joining two output spindles fits their assembly zeros without rebasing either motion target");
            }
            h.assertTrue(NetworkDynamics.preciseSpeed(seq) == inputSpeed && ElasticKinetics.state(seq).angle == inputAngle,
                "independent input spindle keeps its phase and velocity");
            h.assertTrue(Math.abs(ElasticKinetics.elasticEnergy(seq.getOrCreateNetwork()).total() - energy) < 1e-9,
                "assembly phase repair cannot equilibrate or discard spring preload");
            double aligned = ElasticKinetics.state(shaft).angle;
            ElasticKinetics.alignAngles(seq.getOrCreateNetwork());
            h.assertTrue(ElasticKinetics.state(shaft).angle == aligned, "repeated assembly alignment is idempotent");
            for (int distance = -1; distance <= 3; distance++) {
                var at = pos.relative(output, distance);
                if (h.getLevel().getBlockEntity(h.absolutePos(at)) instanceof KineticBlockEntity be) NetworkTopology.mark(be);
                h.setBlock(at, Blocks.AIR);
            }
            NetworkTopology.flush(); h.succeed();
        });
    }
    private static void assertOutputPhase(GameTestHelper h, SequencedGearshiftBlockEntity seq) {
        var edges = ((INetworkDynamics) seq.getOrCreateNetwork()).differentialcreate$data().connections;
        for (var edge : edges) if (SequencerKinetics.outputOwner(edge) == seq) {
            double a = SequencerKinetics.outputFace(edge.a, edge.b)
                ? ((ISequencerDynamics) edge.a).differentialcreate$motion().shaftAngle() : ElasticKinetics.state(edge.a).angle;
            double b = SequencerKinetics.outputFace(edge.b, edge.a)
                ? ((ISequencerDynamics) edge.b).differentialcreate$motion().shaftAngle() : ElasticKinetics.state(edge.b).angle;
            h.assertTrue(Math.abs(edge.ratio * a - b - edge.twist) < 1e-10,
                "output spindle and adjacent shaft share an assembly phase including the actual spring twist: " + (edge.ratio * a - b - edge.twist));
        }
    }
    private static void virtualDemo(GameTestHelper h) {
        BlockPos pos = new BlockPos(8, 4, 8);
        h.setBlock(pos, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState());
        h.runAfterDelay(5, () -> {
            SequencedGearshiftBlockEntity seq = h.getBlockEntity(pos);
            seq.markVirtual();
            seq.getInstructions().clear();
            seq.getInstructions().add(new Instruction(SequencerInstructions.DELAY, 3));
            seq.getInstructions().add(new Instruction(SequencerInstructions.AWAIT, 1));
            seq.getInstructions().add(new Instruction(SequencerInstructions.END, 1));
            seq.run(0);
            for (int i = 0; i < 3; i++) seq.tick();
            h.assertTrue(seq.getModifier() == 0 && seq.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("InstructionIndex") == 0,
                "virtual demonstrations keep native delay timing");
            seq.tick();
            h.assertTrue(seq.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("InstructionIndex") == 1,
                "virtual demonstration advances to native redstone wait without a mechanical servo");
            seq.onRedstoneUpdate(true, true);
            h.assertTrue(seq.isIdle(), "virtual demonstration still responds to a native rising edge");
            var tag = seq.saveWithoutMetadata(h.getLevel().registryAccess());
            h.assertTrue(!tag.contains("DCServo") && !((ISequencerDynamics) seq).differentialcreate$motion().initialized,
                "virtual demonstration cannot acquire real rotor targets or serialized servo state");
            NetworkTopology.mark(seq); h.setBlock(pos, Blocks.AIR); NetworkTopology.flush();
            h.succeed();
        });
    }
    private static void inertia(GameTestHelper h, Direction.Axis axis, int sign, float nextInertia) {
        BlockPos pos = new BlockPos(8, 4, 8);
        h.setBlock(pos, AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
        h.runAfterDelay(5, () -> {
            KineticBlockEntity be = h.getBlockEntity(pos);
            NetworkTopology.mark(be); NetworkTopology.flush();
            float original = InertiaRegistry.getBaseInertia(be);
            try {
                var state = ElasticKinetics.state(be); state.physicalSpeed = sign * 32; state.lastInertia = original; state.angle = .123;
                double initialEnergy = ElasticKinetics.kineticEnergy(be.getOrCreateNetwork());
                InertiaRegistry.register(AllBlocks.FLYWHEEL.get(), nextInertia); ElasticKinetics.refresh(be.getOrCreateNetwork());
                double expected = nextInertia > original ? sign * 32d * original / nextInertia : sign * 32;
                h.assertTrue(Math.abs(state.physicalSpeed - expected) < 1e-10, "new resting mass joins with angular momentum conservation; removed mass carries its share away");
                h.assertTrue(state.angle == .123, "mass changes cannot teleport rotor position");
                h.assertTrue(ElasticKinetics.kineticEnergy(be.getOrCreateNetwork()) <= initialEnergy + 1e-10, "mass attachment/removal cannot create kinetic energy");
                ElasticKinetics.refresh(be.getOrCreateNetwork()); h.assertTrue(state.physicalSpeed == expected, "repeated refresh cannot reapply mass attachment");
            } finally {
                NetworkTopology.mark(be); h.setBlock(pos, Blocks.AIR); NetworkTopology.flush();
                InertiaRegistry.register(AllBlocks.FLYWHEEL.get(), original);
            }
            h.succeed();
        });
    }
    static void runCase(GameTestHelper h, Direction.Axis axis, int sign, int ratio, String pattern, String lifecycle, boolean manual) {
        new Scene(h, axis, sign, ratio, pattern, lifecycle).start(manual);
    }
    private static final class Scene {
        final GameTestHelper h; final Direction.Axis axis; final Direction forward; final int sign, ratio;
        final String pattern, lifecycle; final BlockPos at = new BlockPos(8, 4, 8);
        final List<BlockPos> owned = new ArrayList<>();
        SequencedGearshiftBlockEntity seq; CreativeMotorBlockEntity motor;
        KineticBlockEntity load; List<Instruction> program;
        double goal, previousAngle, previousHeat; int ticks, previousIndex, parkedTicks;
        boolean changed, secondChanged, awaitPulsed, started;
        int heldTicks, releasedAt, snapshotChecks;
        boolean middleSnapshot;
        Scene(GameTestHelper h, Direction.Axis axis, int sign, int ratio, String pattern, String lifecycle) {
            this.h = h; this.axis = axis; this.sign = sign; this.ratio = ratio; this.pattern = pattern; this.lifecycle = lifecycle;
            forward = Direction.fromAxisAndDirection(axis, lifecycle.startsWith("mirror") ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
        }
        SequencerMotion m() { return ((ISequencerDynamics) seq).differentialcreate$motion(); }
        void put(BlockPos p, net.minecraft.world.level.block.state.BlockState state) { owned.add(p); h.setBlock(p, state); }
        void start(boolean manual) {
            put(at.relative(forward.getOpposite()), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, forward));
            motor = h.getBlockEntity(at.relative(forward.getOpposite())); motor.generatedSpeed.setValue(unpowered() ? 0 : sign * (pattern.equals("max_distance") ? 128 : 32));
            put(at, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState().setValue(SequencedGearshiftBlock.VERTICAL, axis == Direction.Axis.Y)
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X));
            put(at.relative(forward), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
            put(at.relative(forward, 2), lifecycle.equals("bearing")
                ? AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, forward)
                : AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
            if (lifecycle.equals("bearing")) {
                put(at.relative(forward, 3), Blocks.SLIME_BLOCK.defaultBlockState());
                put(at.relative(forward, 3).relative(axis == Direction.Axis.Z ? Direction.UP : Direction.SOUTH), Blocks.OAK_PLANKS.defaultBlockState());
            }
            seq = h.getBlockEntity(at); load = h.getBlockEntity(at.relative(forward, 2));
            h.runAfterDelay(6, () -> {
                NetworkTopology.mark(seq); NetworkTopology.flush();
                if (load instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing) {
                    h.useBlock(at.relative(forward, 2)); bearing.tick();
                    h.assertTrue(bearing.isRunning() && bearing.getMovedContraption().getContraption().getBlocks().size() == 2, "real moving structure is assembled");
                }
                configure();
                if (List.of("redstone", "held_await", "unpowered_start", "mirror_unpowered").contains(lifecycle)) put(at.relative(axis == Direction.Axis.Z ? Direction.UP : Direction.SOUTH), Blocks.REDSTONE_BLOCK.defaultBlockState());
                else seq.run(0);
                previousIndex = m().index; previousAngle = m().angle; started = true;
                if (manual) {
                    for (int i = 0; i < 1200; i++) {
                        seq.tick(); MachiningClock.advance(h.getLevel());
                        if (check()) return;
                    }
                    h.fail("manual servo never parked: " + diagnostic());
                }
            });
            if (!manual) h.onEachTick(() -> { if (started) check(); });
        }
        void configure() {
            program = new ArrayList<>();
            int first = pattern.equals("slower") ? Integer.signum(ratio) * 2 : pattern.equals("faster") ? Integer.signum(ratio) : ratio;
            program.add(new Instruction(List.of("distance", "max_distance").contains(pattern) ? SequencerInstructions.TURN_DISTANCE : SequencerInstructions.TURN_ANGLE,
                InstructionSpeedModifiers.getByModifier(first), pattern.equals("max_distance") ? 128 : pattern.equals("max_angle") ? 360 : pattern.equals("motion_sync") ? 90 : pattern.equals("distance") ? 1 : pattern.equals("zero") ? 0 : 30));
            switch (pattern) {
                case "blend" -> program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(ratio), 30));
                case "max_angle" -> program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(ratio), 360));
                case "slower" -> program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(Integer.signum(ratio)), 30));
                case "faster" -> program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(Integer.signum(ratio) * 2), 30));
                case "short" -> program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(ratio), 5));
                case "mixed" -> program.add(new Instruction(SequencerInstructions.TURN_DISTANCE, InstructionSpeedModifiers.getByModifier(ratio), 1));
                case "reverse" -> program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(-ratio), 30));
                case "delay", "await", "max_delay" -> {
                    program.add(new Instruction(pattern.equals("await") ? SequencerInstructions.AWAIT : SequencerInstructions.DELAY, pattern.equals("max_delay") ? 600 : 5));
                    program.add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(-ratio), 30));
                }
                default -> { }
            }
            program.add(new Instruction(SequencerInstructions.END));
            seq.getInstructions().clear(); seq.getInstructions().addAll(program);
            // Native motors express their setting relative to the facing side.
            double sourceDirection = sign * forward.getAxisDirection().getStep();
            goal = ((ISequencerDynamics) seq).differentialcreate$program().stream().filter(SequencerMotion.Segment::moving)
                .mapToDouble(s -> sourceDirection * Math.signum(s.ratio()) * s.turns()).sum();
        }
        String diagnostic() { return "pattern=" + pattern + " lifecycle=" + lifecycle + " index=" + m().index + " angle=" + m().angle + " speed=" + m().speed + " load=" + NetworkDynamics.preciseSpeed(load) + " target=" + m().target + " command=" + m().command; }
        boolean unpowered() { return lifecycle.equals("unpowered_start") || lifecycle.equals("mirror_unpowered"); }
        boolean check() {
            ticks++;
            h.assertTrue(!seq.isRemoved() && !load.isRemoved(), "ordinary servo operation must not fracture: " + diagnostic());
            h.assertTrue(Double.isFinite(m().angle) && Double.isFinite(m().speed), "finite actual output state");
            assertOutputPhase(h, seq);
            if (pattern.equals("motion_sync")) snapshot();
            h.assertTrue(m().brakeHeat >= previousHeat, "brake heat is nondecreasing"); previousHeat = m().brakeHeat;
            h.assertTrue(Math.abs(m().angle - previousAngle) < Math.max(.3, Math.abs(NetworkDynamics.preciseSpeed(motor)) * RotationMath.TICK_MINUTES * 4), "bounded actual frame displacement"); previousAngle = m().angle;
            if (m().index != previousIndex) {
                int display = seq.getBlockState().getValue(SequencedGearshiftBlock.STATE);
                h.assertTrue(m().index >= 0 ? display == m().index + 1 : h.getLevel().hasNeighborSignal(seq.getBlockPos()) ? display > 0 : display == 0, "native comparator instruction state and powered end latch");
                if (previousIndex == 0) {
                    if (List.of("reverse", "stop", "delay", "await", "distance", "zero", "max_distance", "max_delay", "motion_sync").contains(pattern)) h.assertTrue(Math.abs(m().boundarySpeed) <= SequencerMotion.SPEED_EPSILON, "stop barrier has zero exit velocity");
                    else h.assertTrue(Math.abs(m().boundarySpeed) > SequencerMotion.SPEED_EPSILON, "compatible boundary keeps output rotating: " + diagnostic());
                }
                previousIndex = m().index;
            }
            if (pattern.equals("await") && !lifecycle.equals("held_await") && m().index == 1 && !awaitPulsed && m().settled()) {
                awaitPulsed = true; seq.risingFlank();
            }
            if (lifecycle.equals("held_await") && m().index == 1 && m().settled()) {
                var signal = at.relative(axis == Direction.Axis.Z ? Direction.UP : Direction.SOUTH);
                if (++heldTicks == 6) {
                    seq.risingFlank(); h.assertTrue(m().index == 1, "held redstone cannot be reused as a new AWAIT pulse");
                    h.setBlock(signal, Blocks.AIR); releasedAt = ticks;
                } else if (releasedAt > 0 && ticks >= releasedAt + 3 && !awaitPulsed) {
                    awaitPulsed = true; h.setBlock(signal, Blocks.REDSTONE_BLOCK);
                }
            }
            if (unpowered() && ticks >= 3 && ticks <= 20) {
                h.assertTrue(m().index == 0 && m().progress() == 0 && m().angle == 0, "redstone command stays pending before any input rotation");
            }
            if (unpowered() && ticks == 21) { motor.generatedSpeed.setValue(sign * 32); NetworkTopology.mark(motor); }
            if (!lifecycle.equals("normal") && !changed && ticks >= 10 && m().index >= 0) mutate();
            if (lifecycle.equals("power") && changed && !secondChanged && ticks >= 40) {
                motor.generatedSpeed.setValue(sign * 32); NetworkTopology.mark(motor); secondChanged = true;
            }
            if (m().index < 0 && ticks > 12 && (!lifecycle.equals("power") || secondChanged)) {
                if (h.getLevel().hasNeighborSignal(seq.getBlockPos())) {
                    seq.onRedstoneUpdate(true, seq.getBlockState().getValue(SequencedGearshiftBlock.STATE) != 0);
                    h.assertTrue(m().index < 0, "completed program cannot restart on a held signal or unrelated neighbour update");
                }
                if (++parkedTicks < 12) return false;
                h.assertTrue(Math.abs(m().angle - goal) <= SequencerMotion.POSITION_EPSILON * 2, "program ends at commanded net angle: " + diagnostic() + " expected=" + goal);
                h.assertTrue(Math.abs(m().speed) <= SequencerMotion.SPEED_EPSILON && Math.abs(NetworkDynamics.preciseSpeed(load)) <= .2, "output and load stay parked");
                h.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(motor)) > 1, "input source keeps rotating independently of parked output");
                if (pattern.equals("motion_sync")) h.assertTrue(snapshotChecks > 5 && middleSnapshot,
                    "snapshot codec and phase checks must cover actual moving mid-stroke poses, not only the parked ends");
                if (load instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing) {
                    double angle = ((IBearingMotion) bearing).differentialcreate$syncedAngle();
                    h.assertTrue(Math.abs(angle - goal * 360) <= .06, "actual bearing geometry reaches its command without a native position clamp: " + angle + " expected=" + goal * 360);
                    h.assertTrue(SequencerKinetics.controlled(bearing) && bearing.sequenceContext == null, "servo output follows integrated shaft displacement");
                }
                cleanup(); h.succeed(); return true;
            }
            if (ticks > 1200) h.fail("servo failed to finish: " + diagnostic());
            return false;
        }
        void snapshot() {
            KineticBlockEntity shaft = h.getBlockEntity(at.relative(forward));
            var payload = new RotorStatePayload(h.getLevel().dimension().location(), seq.network, 0, 0, LoadFactor.Reading.UNKNOWN,
                List.of(ClientSpeedSync.captureRotor(seq, 0), ClientSpeedSync.captureRotor(shaft, 0), ClientSpeedSync.captureRotor(load, 0)));
            var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                RotorStatePayload.STREAM_CODEC.encode(buf, payload);
                var decoded = RotorStatePayload.STREAM_CODEC.decode(buf);
                h.assertTrue(payload.equals(decoded) && !buf.isReadable(), "member, independent output and neighbouring shaft poses share an exact network snapshot");
                var output = decoded.rotors().getFirst().output();
                h.assertTrue(output != null && output.angle() == m().shaftAngle() * 360 && decoded.rotors().get(1).output() == null,
                    "output rendering receives the actual spindle phase, independently of its input or block-NBT arrival time");
                snapshotChecks++;
                double progress = m().progress() * 360;
                if (progress >= 30 && progress <= 65 && Math.abs(m().speed) > 1) middleSnapshot = true;
            } finally { buf.release(); }
        }
        void mutate() {
            changed = true;
            switch (lifecycle) {
                case "reload" -> {
                    var before = m(); double a = before.angle, w = before.speed, target = before.target, progress = before.progress();
                    var tag = seq.saveWithFullMetadata(h.getLevel().registryAccess());
                    NetworkTopology.mark(seq);
                    seq = (SequencedGearshiftBlockEntity) BlockEntity.loadStatic(seq.getBlockPos(), seq.getBlockState(), tag, h.getLevel().registryAccess());
                    h.getLevel().setBlockEntity(seq); NetworkTopology.mark(seq); NetworkTopology.flush();
                    h.assertTrue(m().angle == a && m().speed == w && m().target == target && m().progress() == progress, "NBT replacement preserves rotor and program coordinates exactly");
                }
                case "power" -> { motor.generatedSpeed.setValue(0); NetworkTopology.mark(motor); }
                case "source_reverse" -> { motor.generatedSpeed.setValue(-sign * 32); NetworkTopology.mark(motor); }
                case "load" -> {
                    put(at.relative(forward, 3), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
                    put(at.relative(forward, 4), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
                    NetworkTopology.mark(seq);
                }
                case "remove_output" -> {
                    h.setBlock(at.relative(forward), Blocks.AIR);
                    NetworkTopology.mark(seq); NetworkTopology.flush();
                    put(at.relative(forward), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, axis)); NetworkTopology.mark(seq);
                }
                case "restart" -> {
                    double now = m().angle; seq.run(0); goal += now;
                }
                case "probe" -> {
                    double angle = m().angle, speed = m().speed, target = m().target, progress = m().progress(); int index = m().index;
                    var snapshot = ElasticKinetics.snapshot(MachiningClock.networks(h.getLevel()));
                    var first = snapshot.sample(RotationMath.TICK_MINUTES / 4);
                    snapshot.sample(RotationMath.TICK_MINUTES);
                    var repeated = snapshot.sample(RotationMath.TICK_MINUTES / 4);
                    h.assertTrue(java.util.Arrays.equals(first.result.speed(), repeated.result.speed()) && java.util.Arrays.equals(first.result.angle(), repeated.result.angle()), "discarded servo trials remain capture-order independent");
                    h.assertTrue(m().angle == angle && m().speed == speed && m().target == target && m().progress() == progress && m().index == index, "solver probes cannot move the virtual output or consume instructions");
                    h.getLevel().tickRateManager().setFrozen(true);
                    h.getLevel().tickRateManager().tick();
                    try { seq.tick(); MachiningClock.advance(h.getLevel()); }
                    finally { h.getLevel().tickRateManager().setFrozen(false); h.getLevel().tickRateManager().tick(); }
                    h.assertTrue(m().angle == angle && m().speed == speed && m().progress() == progress && m().index == index, "tick freeze suspends both mechanical output and instruction execution");
                }
                default -> { }
            }
        }
        void cleanup() {
            if (load instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing && bearing.isRunning()) bearing.disassemble();
            for (var p : owned) {
                if (h.getLevel().getBlockEntity(h.absolutePos(p)) instanceof KineticBlockEntity be) NetworkTopology.mark(be);
                h.setBlock(p, Blocks.AIR);
            }
            NetworkTopology.flush(); started = false;
        }
    }
}
