package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluid;

/** Real inventory endpoints: no assigned pipe contents, flow, pressure or rotor speed. */
final class ReservoirBoundaryTests {
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }

    static void horizontalRest(GameTestHelper h, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "reservoir_horizontal_rest");
        var source = p(2, 4, 8); var sink = p(10, 4, 8);
        n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, sink); n.build(); n.timeout(498);
        n.after(20, () -> { n.fill(source, fluid, 28000); n.fill(sink, fluid, 1000); });
        n.watch(() -> { if (h.getTick() > 20) n.audit(); });
        n.after(200, () -> {
            n.expect(n.amount(source, fluid) == 28000 && n.amount(sink, fluid) == 1000 && n.body() == 0,
                "different displayed levels add no pressure to a stationary horizontal connection");
            n.fill(source, fluid, 1000);
        });
        n.after(450, () -> {
            n.expect(n.amount(source, fluid) == 29000 && n.amount(sink, fluid) == 1000 && n.body() == 0,
                "a later level change cannot wake an unforced horizontal pipe");
            n.done("equal_reference_pressure, no_level_head");
        });
    }

    static void extract(GameTestHelper h, Direction side, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "reservoir_extract_" + side.getName());
        var source = p(8, 10, 8);
        var mouth = side == Direction.DOWN ? source : source.above(2);
        var sink = mouth.relative(side, 8); var pump = mouth.relative(side, 4);
        n.tank(source, 1, 3); n.tank(sink, 1, 1); n.route(mouth, sink); n.pump(pump, side); n.build(); n.timeout(1598);
        n.after(20, () -> { n.fill(source, fluid, 1234); n.speed(pump, 128); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit();
            if (n.amount(source, fluid) == 0 && n.amount(sink, fluid) >= 1200) {
                n.expect(n.amount(sink, fluid) <= 1234, "the receiver never exceeds the real source inventory");
                n.done("side=" + side + ", source_empty, actual_receipt=" + n.amount(sink, fluid));
            }
        });
    }

    static void dryColumn(GameTestHelper h, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "reservoir_dry_column");
        var source = p(4, 12, 8); var sink = source.below(8); var pump = source.below(4);
        n.tank(source, 1, 2); n.tank(sink, 1, 1); n.route(source, sink); n.pump(pump, Direction.DOWN);
        n.build(); n.timeout(1598);
        n.after(20, () -> n.fill(source, fluid, 2000));
        n.watch(() -> { if (h.getTick() > 20) n.audit(); });
        n.after(200, () -> {
            n.expect(n.body() == 0 && n.amount(source, fluid) == 2000 && n.amount(sink, fluid) == 0,
                "a stationary all-air column receives no artificial reservoir pressure");
            n.speed(pump, 64);
        });
        n.watch(() -> {
            if (h.getTick() > 200 && n.amount(sink, fluid) >= 1900)
                n.done("no_container_head, actual_pump_establishes_transfer");
        });
    }

    static void gravityLoop(GameTestHelper h, Fluid fluid, int height) {
        var n = new NativeFluidTestNetwork(h, "reservoir_gravity_loop_" + height);
        var tank = p(4, 4, 8); var top = tank.above(height); var pump = top.east(2); var corner = top.east(4);
        n.tank(tank, 1, height + 1); n.route(top, corner, corner.below(height), tank); n.pump(pump, Direction.EAST);
        n.build(); n.timeout(2398);
        int[] released = {-1}; double[] early = {Double.NaN};
        double expected = Math.sqrt(IdealFluidSolver.GRAVITY * height) / .2;
        n.after(20, () -> { n.fill(tank, fluid, 6000); n.speed(pump, 64); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit();
            if (released[0] < 0) {
                boolean full = n.ports.keySet().stream().filter(n::channel)
                    .allMatch(pos -> Math.abs(n.volume(pos) - FluidKinetics.state(n.pipe(pos)).queueCapacity) < 1e-5);
                if (!full) return;
                // Actual pumping primes the loop; replacing it retains native flow history.
                n.speed(pump, 0); n.replacePumpWithPipe(pump); released[0] = (int)h.getTick();
                return;
            }
            double q = FluidKinetics.state(n.pipe(corner.below())).outward.getOrDefault(Direction.DOWN, 0d);
            n.expect(Double.isFinite(q) && Math.abs(q) < 256, "gravity circulation stays finite after removing its only pump");
            int elapsed = (int)h.getTick() - released[0];
            if (elapsed == 300) early[0] = q;
            if (elapsed >= 700) {
                n.expect(Math.abs(q - expected) < .01 * expected,
                    "boundary dissipation balances the actual height drop: " + q + " / " + expected);
                n.expect(Math.abs(q - early[0]) < .01 * expected, "circulation does not keep accelerating with elapsed time");
                n.done("no_pump, drop=" + height + ", stable_flow=" + q + ", expected=" + expected);
            }
        });
    }
}
