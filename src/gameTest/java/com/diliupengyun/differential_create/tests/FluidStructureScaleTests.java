package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Actual remote structural changes and independently advancing networks. */
final class FluidStructureScaleTests {
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    static void distant(GameTestHelper h, int length, Fluid fluid) {
        String name = "structure_distant_" + length + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 10, 4); var sink = p(length, 0, 4); var pump = p(3, 3, 4);
        n.tank(source, 1, 16); n.tank(sink, 1, 4); n.route(source, p(0, 3, 4), sink.above(3));
        n.pump(pump, Direction.EAST); n.build(); int horizon = 7000 + 24 * length; n.timeout(horizon - 2);
        boolean[] acted = {false};
        n.after(20, () -> { n.fill(source, fluid, Math.min(120000, length * 100 + 16000)); n.speed(pump, 64); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0] || n.amount(sink, fluid) < 64) return;
            acted[0] = true; int tick = (int)h.getTick(), before = n.amount(sink, fluid); var middle = p(length / 2, 3, 4);
            n.expect(n.volume(middle) > 99, "the remote modified part is genuinely wet, after first native delivery");
            n.edge(middle, middle.east(), false);
            n.after(tick + 100, () -> {
                n.route(middle.west(4), middle.west(4).above(2), middle.west(4).above(2).south(6),
                    middle.east(4).above(2).south(6), middle.east(4).south(6), middle.east(4));
                n.installChangedPipes(); n.edge(middle, middle.east(), true);
                // Same-block entity reload crosses a remote changed junction.
                n.reload(middle.west(4));
            });
            n.after(tick + 2400, () -> {
                n.expect(n.amount(sink, fluid) >= before + 128, "long-line structural change resumes NEW remote native receipts");
                n.done("actual_distance=" + length + ", wet_remote_split=true, raised_3d_bypass=true, reload=true, new_receipts=" + (n.amount(sink, fluid) - before));
            });
        });
    }
    static void simultaneous(GameTestHelper h) {
        var networks = new ArrayList<NativeFluidTestNetwork>(); int[] before = new int[16]; boolean[] acted = {false};
        for (int i = 0; i < 16; i++) {
            var n = new NativeFluidTestNetwork(h, "structure_simultaneous_" + i, p(0, 0, 2048 + 32 * i));
            var source = p(0, 8, 4); var sink = p(32, 0, 4); var pump = p(3, 3, 4);
            n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, p(0, 3, 4), sink.above(3)); n.pump(pump, Direction.EAST);
            n.build(); networks.add(n);
        }
        h.runAtTickTime(20, () -> { for (var n : networks) { n.fill(p(0, 8, 4), Fluids.WATER, 18000); n.speed(p(3, 3, 4), 32); } });
        h.onEachTick(() -> {
            if (h.getTick() <= 20 || networks.getFirst().closed) return;
            try {
                for (var n : networks) { n.audit(); FluidStructureMutationTests.phases(n); }
                if (acted[0] || networks.stream().anyMatch(n -> n.amount(p(32, 0, 4), Fluids.WATER) < 64)) return;
                acted[0] = true; int tick = (int)h.getTick();
                for (int i = 0; i < networks.size(); i++) {
                    var n = networks.get(i); before[i] = n.amount(p(32, 0, 4), Fluids.WATER);
                    n.edge(p(16, 3, 4), p(17, 3, 4), false); n.speed(p(3, 3, 4), 16 + 16 * (i % 4));
                    n.route(p(12, 3, 4), p(12, 3, 10), p(20, 3, 10), p(20, 3, 4)); n.installChangedPipes();
                }
                h.runAtTickTime(tick + 120, () -> {
                    for (var n : networks) { n.reload(p(12, 3, 4)); n.edge(p(16, 3, 4), p(17, 3, 4), true); }
                });
                h.runAtTickTime(tick + 1900, () -> {
                    try {
                        int least = Integer.MAX_VALUE; double error = 0;
                        for (int i = 0; i < networks.size(); i++) {
                            var n = networks.get(i); n.audit(); int received = n.amount(p(32, 0, 4), Fluids.WATER) - before[i];
                            n.expect(received >= 128, "every independently changed component makes new actual receipts under the shared budget");
                            least = Math.min(least, received); error = Math.max(error, n.maximumError);
                        }
                        System.out.println("FLUID_STRUCTURE_MULTINET_PASS networks=16 least_new_receipts=" + least + " error=" + error);
                        for (var n : networks) n.cleanup(); h.succeed();
                    } catch (RuntimeException | Error failure) { for (var n : networks) n.cleanup(); throw failure; }
                });
            } catch (RuntimeException | Error failure) { for (var n : networks) n.cleanup(); throw failure; }
        });
        h.runAtTickTime(7998, () -> { if (!networks.getFirst().closed) { for (var n : networks) n.cleanup(); h.fail("all 16 dynamically changed native components must recover before the horizon"); } });
    }
}
