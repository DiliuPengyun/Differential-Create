package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Player-built native drives; no assigned rotor speed, fluid head or pipe stock. */
final class PumpDirectionIntegrationTests {
    private static final BlockPos PUMP = new BlockPos(8, 8, 8);
    private static Direction positive(Direction facing) { return Direction.get(Direction.AxisDirection.POSITIVE, facing.getAxis()); }
    private static int polarity(Direction facing) { return facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1; }
    private static String kind(Fluid fluid) { return fluid == null ? "air" : fluid.isSame(Fluids.WATER) ? "water" : "lava"; }
    private record Reservoirs(BlockPos minus, BlockPos plus) {}
    private static Reservoirs reservoirs(NativeFluidTestNetwork n, Direction axis) {
        var minus = PUMP.relative(axis, -4); var plus = PUMP.relative(axis, 4);
        // Bottom connections stay immersed as the source drains. A pipe above
        // a tank's roof legitimately takes air and cannot test liquid suction.
        var left = axis.getAxis() == Direction.Axis.Y ? minus.west(2).above() : minus.above();
        var right = axis.getAxis() == Direction.Axis.Y ? plus.west(2).above() : plus.above();
        n.tank(left, 1, 1); n.tank(right, 1, 1);
        if (axis.getAxis() == Direction.Axis.Y) n.route(left, left.below(), minus, plus, right.below(), right);
        else n.route(left, minus, plus, right);
        return new Reservoirs(left, right);
    }
    private static void drive(NativeFluidTestNetwork n, int rpm) {
        // The native negative-facing motor and its one meshing cog give two
        // sign inversions. This is a real motor command, not an assigned speed.
        ((CreativeMotorBlockEntity)n.entity(n.motors.get(PUMP))).generatedSpeed.setValue(rpm);
    }
    static void direction(GameTestHelper h, Direction facing, int rpm, Fluid fluid) {
        String name = "pump_direction_" + facing.getName() + "_" + (rpm > 0 ? "positive" : "negative") + "_" + kind(fluid);
        var n = new NativeFluidTestNetwork(h, name); var axis = positive(facing);
        var minus = PUMP.relative(axis, -4); var plus = PUMP.relative(axis, 4);
        BlockPos source, sink;
        if (fluid == null) {
            n.route(minus, plus); n.port(minus, axis.getOpposite()); n.port(plus, axis);
            source = minus; sink = plus;
        } else {
            var tanks = reservoirs(n, axis); source = rpm * polarity(facing) > 0 ? tanks.minus : tanks.plus;
            sink = rpm * polarity(facing) > 0 ? tanks.plus : tanks.minus;
        }
        n.pump(PUMP, facing); n.build(); n.timeout(2398);
        boolean[] observed = {false};
        n.after(20, () -> { if (fluid != null) n.fill(source, fluid, 8000); drive(n, rpm); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit();
            var pump = (KineticBlockEntity)n.entity(PUMP); var state = (IPumpDynamics)pump;
            double speed = NetworkDynamics.preciseSpeed(pump), flow = state.differentialcreate$flow();
            if (Math.abs(speed) > 16 && Math.abs(flow) > .1 && (fluid == null || n.amount(sink, fluid) >= 128)) {
                n.expect(speed * rpm > 0, "real gearing turns the shaft in the requested world-axis direction");
                n.expect(flow * rpm * polarity(facing) > 0, "positive rotation pumps along the arrow and negative rotation against it: speed=" + speed + ", flow=" + flow);
                double outward = FluidKinetics.state(n.pipe(PUMP)).outward.getOrDefault(axis, 0d);
                n.expect(outward * rpm * polarity(facing) > 0, "a native physical face confirms arrow-relative delivery");
                n.expect(Double.isFinite(state.differentialcreate$head() + state.differentialcreate$torque()), "head and reaction remain finite");
                observed[0] = true;
                n.done("real_axis=" + axis + ", rpm=" + speed + ", signed_flow=" + flow + ", receipt=" + (fluid == null ? 0 : n.amount(sink, fluid)));
            }
        });
        n.after(2397, () -> n.expect(observed[0], "native direction and actual delivery were observed"));
    }
    static void dryTop(GameTestHelper h, Direction facing, Fluid fluid) {
        String name = "pump_direction_dry_top_" + facing.getName() + "_" + kind(fluid);
        var n = new NativeFluidTestNetwork(h, name); var source = PUMP.below(4); var sink = PUMP.above(4);
        n.tank(source, 1, 1); n.tank(sink, 1, 1); n.route(source, sink); n.pump(PUMP, facing); n.build(); n.timeout(498);
        n.after(20, () -> { n.fill(source, fluid, 8000); drive(n, 64 * polarity(facing)); });
        n.after(300, () -> {
            n.expect(((IPumpDynamics)n.entity(PUMP)).differentialcreate$flow() > .1, "the real shaft transports air from the dry top port");
            n.expect(n.amount(source, fluid) == 8000 && n.body() == 0 && n.amount(sink, fluid) == 0,
                "suction does not invent immersion at a tank's dry roof");
            n.done("dry_top_air_only");
        });
    }
    static void migration(GameTestHelper h, Direction facing, Fluid fluid) {
        String name = "pump_direction_migration_" + facing.getName() + "_" + kind(fluid);
        var n = new NativeFluidTestNetwork(h, name); var axis = positive(facing); var tanks = reservoirs(n, axis);
        int sign = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        var source = sign > 0 ? tanks.minus : tanks.plus; var sink = sign > 0 ? tanks.plus : tanks.minus;
        n.pump(PUMP, facing); n.build(); n.timeout(1198); boolean[] migrated = {false}; int[] receipt = {0};
        n.after(20, () -> { n.fill(source, fluid, 8000); drive(n, 64); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit();
            if (!migrated[0]) {
                if (n.amount(sink, fluid) < 128) return;
                var old = n.entity(PUMP); var saved = FluidKinetics.state(n.pipe(PUMP));
                double worldFlow = .5 * (saved.outward.getOrDefault(axis, 0d) - saved.outward.getOrDefault(axis.getOpposite(), 0d));
                var tag = old.saveWithFullMetadata(h.getLevel().registryAccess()); var hydraulic = tag.getCompound("DCIdealFluid");
                hydraulic.remove("PumpAxisVersion"); hydraulic.putDouble("PumpFlow", sign * worldFlow); hydraulic.putDouble("AxisSign", -sign);
                h.getLevel().removeBlockEntity(n.absolute(PUMP));
                var next = BlockEntity.loadStatic(n.absolute(PUMP), n.state(PUMP), tag, h.getLevel().registryAccess());
                n.expect(next != null, "a wet legacy snapshot loads through the real block-entity path"); h.getLevel().setBlockEntity(next);
                ((SmartBlockEntity)next).initialize(); FluidKinetics.invalidate(h.getLevel());
                n.expect(Math.abs(FluidKinetics.state(n.pipe(PUMP)).pumpFlow - worldFlow) < 1e-10,
                    "legacy front-oriented cached flow migrates to the actual world axis");
                n.audit(); n.reload(PUMP);
                n.expect(Math.abs(FluidKinetics.state(n.pipe(PUMP)).pumpFlow - worldFlow) < 1e-10,
                    "ordinary new-format reload does not apply the legacy sign conversion again");
                var fresh = n.entity(PUMP).saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("DCIdealFluid");
                n.expect(fresh.getInt("PumpAxisVersion") == 1 && !fresh.contains("AxisSign"), "the new snapshot records its axis version and retires the old rotation memory");
                n.audit(); receipt[0] = n.amount(sink, fluid); migrated[0] = true;
            } else if (n.amount(sink, fluid) >= receipt[0] + 128) {
                n.expect(((IPumpDynamics)n.entity(PUMP)).differentialcreate$flow() * sign > 0, "the wet native circuit makes new real receipts after migration");
                n.done("legacy_face_flow, ignored_opposite_history, once_only_conversion, wet_resumed_receipts");
            }
        });
    }
    private static NativeFluidTestNetwork freeFixture(GameTestHelper h, String name, Direction facing, int direction, int history, int offset) {
        var n = new NativeFluidTestNetwork(h, name, new BlockPos(0, 0, offset)); var axis = positive(facing);
        var minus = PUMP.relative(axis, -7); var plus = PUMP.relative(axis, 7);
        n.route(minus, plus); n.port(minus, axis.getOpposite()); n.port(plus, axis);
        var driver = PUMP.relative(axis, -3 * direction); n.pump(driver, axis); n.pump(PUMP, facing); n.build();
        // Exercise old saved signs through the real NBT load path. Only the
        // obsolete sign differs; actual stock, motion and topology are equal.
        var old = n.entity(PUMP); var tag = old.saveWithFullMetadata(h.getLevel().registryAccess());
        tag.getCompound("DCIdealFluid").putDouble("AxisSign", history);
        h.getLevel().removeBlockEntity(n.absolute(PUMP));
        var next = BlockEntity.loadStatic(n.absolute(PUMP), n.state(PUMP), tag, h.getLevel().registryAccess());
        n.expect(next != null, "the real saved pump loads"); h.getLevel().setBlockEntity(next);
        if (next instanceof SmartBlockEntity smart) smart.initialize(); FluidKinetics.invalidate(h.getLevel());
        n.after(20, () -> {
            n.expect(NetworkDynamics.preciseSpeed((KineticBlockEntity)n.entity(PUMP)) == 0, "the free shaft is genuinely at rest before fluid starts");
            n.speed(driver, 64 * direction);
        });
        return n;
    }
    static void standstill(GameTestHelper h, Direction facing, int direction) {
        String name = "pump_direction_standstill_" + facing.getName() + "_" + (direction > 0 ? "positive" : "negative");
        var a = freeFixture(h, name + "_old_positive", facing, direction, 1, 2048);
        var b = freeFixture(h, name + "_old_negative", facing, direction, -1, 2112);
        a.timeout(898); b.timeout(898);
        double[] startup = new double[2];
        a.watch(() -> {
            if (h.getTick() <= 20) return; a.audit(); b.audit();
            for (int i = 0; i < 2; i++) {
                var n = i == 0 ? a : b; var pump = (KineticBlockEntity)n.entity(PUMP); var state = (IPumpDynamics)pump;
                double speed = NetworkDynamics.preciseSpeed(pump);
                if (startup[i] == 0 && Math.abs(speed) > 1e-7) {
                    n.expect(state.differentialcreate$flow() * direction > 0, "the external driver actually sends air through the resting pump");
                    n.expect(speed * direction * polarity(facing) > 0, "the arrow sets startup polarity without an old saved sign: " + speed);
                    n.expect(-state.differentialcreate$torque() * direction * polarity(facing) > 0, "the initial fluid reaction uses the same arrow polarity");
                    startup[i] = speed;
                }
            }
            if (startup[0] != 0 && startup[1] != 0) {
                b.audit(); b.cleanup();
                a.done("two_real_resting_pumps, obsolete_signs=+1/-1, startup=" + startup[0] + "/" + startup[1]);
            }
        });
    }
    static void dynamic(GameTestHelper h, Direction facing, Fluid fluid) {
        String name = "pump_direction_dynamic_" + facing.getName() + "_" + kind(fluid);
        var n = new NativeFluidTestNetwork(h, name); var axis = positive(facing); var tanks = reservoirs(n, axis);
        int sign = polarity(facing);
        var source = sign > 0 ? tanks.minus : tanks.plus; var sink = sign > 0 ? tanks.plus : tanks.minus;
        n.pump(PUMP, facing); n.build(); n.timeout(5998);
        int[] stage = {0}, actionTick = {20}, before = {0}; BlockState[] savedMotor = {null}, savedPump = {null};
        var gate = PUMP.relative(axis, 2); var beyond = gate.relative(axis);
        n.after(20, () -> { n.fill(source, fluid, 8000); drive(n, 64); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit();
            var pump = (KineticBlockEntity)n.entity(PUMP); var state = (IPumpDynamics)pump;
            double speed = NetworkDynamics.preciseSpeed(pump); int tick = (int)h.getTick();
            switch (stage[0]) {
                case 0 -> {
                    if (n.amount(sink, fluid) < 1000 || speed < 16) return;
                    before[0] = n.amount(source, fluid); FluidStructureMutationTests.wrench(n, PUMP);
                    n.expect(n.state(PUMP).getValue(PumpBlock.FACING) == facing.getOpposite(), "native wrench changes the block's front");
                    n.expect(NetworkDynamics.preciseSpeed(pump) > 0, "a wrench flip does not instantly reverse the actual shaft");
                    actionTick[0] = tick; stage[0] = 1;
                }
                case 1 -> {
                    if (tick < actionTick[0] + 30 || n.amount(source, fluid) < before[0] + 128) return;
                    n.expect(speed > 0 && state.differentialcreate$flow() * sign < 0, "a real wrench flip reverses delivery while the shaft keeps its rotation sign");
                    for (var p : n.ports.keySet()) if (n.channel(p)) n.reload(p);
                    before[0] = n.amount(source, fluid); actionTick[0] = tick; stage[0] = 2;
                }
                case 2 -> {
                    if (tick < actionTick[0] + 30 || n.amount(source, fluid) < before[0] + 128) return;
                    n.expect(speed > 0 && state.differentialcreate$flow() * sign < 0, "native reload retains the flipped arrow's direction and actual inventory");
                    before[0] = n.amount(sink, fluid); drive(n, -64);
                    n.expect(NetworkDynamics.preciseSpeed(pump) > 0, "reversing the motor command does not teleport the real rotating shaft across zero");
                    actionTick[0] = tick; stage[0] = 3;
                }
                case 3 -> {
                    if (speed >= -16 || n.amount(sink, fluid) < before[0] + 128) return;
                    n.expect(state.differentialcreate$flow() * sign > 0, "negative rotation against the flipped arrow restores the original delivery direction");
                    var motor = n.motors.get(PUMP); savedMotor[0] = n.state(motor); n.put(motor, Blocks.AIR.defaultBlockState());
                    actionTick[0] = tick; stage[0] = 4;
                }
                case 4 -> {
                    if (tick < actionTick[0] + 40) return;
                    n.expect(Double.isFinite(speed + state.differentialcreate$flow() + state.differentialcreate$torque()), "coasting and fluid backdrive stay finite after the native motor is removed");
                    var motor = n.motors.get(PUMP); n.put(motor, savedMotor[0]); ((SmartBlockEntity)n.entity(motor)).initialize(); drive(n, 64);
                    before[0] = n.amount(source, fluid); actionTick[0] = tick; stage[0] = 5;
                }
                case 5 -> {
                    if (speed < 16 || n.amount(source, fluid) < before[0] + 128) return;
                    n.edge(gate, beyond, false); actionTick[0] = tick; stage[0] = 6;
                }
                case 6 -> {
                    if (tick < actionTick[0] + 60) return;
                    n.expect(Math.abs(state.differentialcreate$flow()) < 1e-8 && Math.abs(state.differentialcreate$torque()) < 1e-8,
                        "a genuinely sealed full branch has no continued flow or shaft power");
                    n.expect(state.differentialcreate$head() * sign < 0 && speed > 16, "the blocked pump retains pressure along its flipped arrow");
                    n.edge(gate, beyond, true); savedPump[0] = n.state(PUMP);
                    var cross = axis.getAxis() == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
                    n.put(PUMP.relative(cross), Blocks.BARRIER.defaultBlockState()); n.put(PUMP.relative(cross.getOpposite()), Blocks.BARRIER.defaultBlockState());
                    n.put(PUMP, n.state(PUMP).setValue(PumpBlock.FACING, cross)); actionTick[0] = tick; stage[0] = 7;
                }
                case 7 -> {
                    if (tick < actionTick[0] + 30) return;
                    n.expect(state.differentialcreate$flow() == 0, "changing the actual pump axis selects its new sealed physical faces");
                    n.put(PUMP, savedPump[0]); before[0] = n.amount(source, fluid); actionTick[0] = tick; stage[0] = 8;
                }
                case 8 -> {
                    if (speed < 16 || n.amount(source, fluid) < before[0] + 128) return;
                    n.done("wrench, wet_reload, real_motor_reversal, coast, motor_replacement, deadhead, axis_change, restore");
                }
                default -> throw new IllegalStateException("unexpected pump direction stage");
            }
        });
    }
}
