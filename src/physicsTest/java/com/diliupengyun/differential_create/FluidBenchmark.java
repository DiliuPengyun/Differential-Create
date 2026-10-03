package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.Arrays;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.kinetics.PipeFilling;
import com.diliupengyun.differential_create.kinetics.ReducedFluidGraph;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Repeatable kernel comparison, not a Minecraft TPS benchmark. Includes actual
 * filling bookkeeping and both projections used by the production adapter. */
public final class FluidBenchmark {
    private static volatile double sink;
    public static void main(String[] args) {
        var water = new IdealFluidSolver.Material(1000, false);
        for (int n : new int[]{64, 256, 1024}) {
            var edges = new ArrayList<IdealFluidSolver.Edge>(); var tubes = new ArrayList<PipeFilling.Tube>();
            for (int e = 0; e < n; e++) { edges.add(new IdealFluidSolver.Edge(e, e + 1, water.inertia(1))); tubes.add(new PipeFilling.Tube(e, e + 1, 1, 0, 0)); }
            boolean[] boundary = new boolean[n + 1], active = new boolean[n]; Arrays.fill(active, true); boundary[0] = boundary[n] = true;
            double[] flow = new double[n], force = new double[n], datum = new double[n + 1], supplied = new double[n + 1], mass = new double[n];
            Arrays.fill(flow, 9); Arrays.fill(mass, water.inertia(1)); force[n / 2] = 10; supplied[0] = 1;
            var contents = new PipeContents[n]; Arrays.fill(contents, PipeContents.FULL);
            double dt = RotationMath.TICK_MINUTES / 8;
            var reduced = new ReducedFluidGraph(n + 1, edges, boundary, active);
            Runnable legacy = () -> sink = new IdealFluidSolver.Graph(n + 1, edges, boundary, active).sample(flow, force, datum, dt).flow()[0];
            Runnable current = () -> {
                var origin = reduced.sample(flow, mass, new double[n], datum, dt).flow();
                var predicted = PipeFilling.sample(tubes, n + 1, contents, origin, flow, boundary, supplied, water, dt);
                var trial = reduced.sample(origin, predicted.inertia(), force, datum, dt);
                sink = PipeFilling.sample(tubes, n + 1, contents, origin, trial.midpoint(), boundary, supplied, water, dt).contents()[0].fraction() + trial.flow()[0];
            };
            for (int i = 0; i < 40; i++) { legacy.run(); current.run(); }
            int count = n == 1024 ? 40 : 160;
            double before = Double.POSITIVE_INFINITY, after = Double.POSITIVE_INFINITY;
            for (int pass = 0; pass < 3; pass++) {
                before = Math.min(before, time(legacy, count)); after = Math.min(after, time(current, count));
            }
            System.out.printf("Fluid kernel, %d full straight sections: legacy=%.4f ms/step, contents+reduction=%.4f ms/step, ratio=%.3f, channels=%d%n",
                n, before, after, after / before, reduced.channels());
        }
    }
    private static double time(Runnable sample, int count) {
        long start = System.nanoTime(); for (int i = 0; i < count; i++) sample.run();
        return (System.nanoTime() - start) / 1e6 / count;
    }
}
