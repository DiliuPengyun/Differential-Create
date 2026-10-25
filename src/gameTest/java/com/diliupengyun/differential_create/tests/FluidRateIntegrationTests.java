package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluid;

/** Real source, receiver and shaft: no seeded flow or manual physics clock. */
final class FluidRateIntegrationTests {
    private FluidRateIntegrationTests() {}
    static void highFlow(GameTestHelper h, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "high_flow_transport_" + fluid);
        var source = new BlockPos(0, 3, 2);
        var sink = new BlockPos(6, 3, 2);
        var pump = new BlockPos(3, 3, 2);
        n.tank(source, 1, 4); n.tank(sink, 1, 4);
        n.route(source, sink); n.pump(pump, Direction.EAST); n.build(); n.timeout(1200);
        long[] start = {-1}; int[] received = {0};
        n.after(40, () -> { n.fill(source, fluid, 28000); n.speed(pump, 256); });
        n.watch(() -> {
            if (h.getTick() <= 40) return;
            n.audit();
            double flow = Math.abs(((IPumpDynamics)n.entity(pump)).differentialcreate$flow());
            if (start[0] < 0) {
                if (flow < 80 || n.amount(sink, fluid) < 1000) return;
                start[0] = h.getTick(); received[0] = n.amount(sink, fluid);
            }
            if (h.getTick() < start[0] + 60) return;
            int delivered = n.amount(sink, fluid) - received[0];
            n.expect(delivered > 60 * 60,
                "native receiver gains more than the old 50 mB/tick ceiling over a full 60-tick interval: " + delivered);
            n.done("high flow commits actual inventory, delivered=" + delivered + " mB in 60 ticks");
        });
    }
}
