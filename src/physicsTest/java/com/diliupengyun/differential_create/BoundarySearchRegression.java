package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.ShaftClock;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Result;

/** Prefix reuse and displacement-only probes must preserve the original bisection path. */
final class BoundarySearchRegression {
    private static final double DT = 1.0 / 1200, H = DT / 64, INF = Double.POSITIVE_INFINITY;
    private static int checks;
    static void run() {
        var pair = List.of(new Body(.15, 64, 4, 0, 0), new Body(.1, 0, 0, 8, .001));
        compare(pair, List.of(new Link(0, 1, 1, 1e7, 0, INF, INF)), new double[]{128, 128}, new double[]{8e-7});
        compare(pair, List.of(new Link(0, 1, -2, 4e6, 0, INF, 32)), new double[]{128, -32}, new double[1]);
        compare(pair, List.of(new Link(0, 1, 1, 1e7, 0, 10, INF)), new double[]{128, 0}, new double[1]);
        compare(List.of(new Body(.01, -128, 0, 8, 0), new Body(.1, 64, 4, 0, 0)),
            List.of(), new double[]{1, 128}, new double[0]);
        for (int n : new int[]{8, 16, 32}) {
            var bodies = new ArrayList<Body>();
            var links = new ArrayList<Link>();
            double[] speeds = new double[n]; Arrays.fill(speeds, 128);
            for (int i = 0; i < n; i++) {
                bodies.add(i == 0 ? new Body(.15, 1024, 0, 0, 0, 131072, 512, 64)
                    : i == n - 1 ? new Body(1.5, 0, 0, 64, .001) : new Body(0, 0, 0, 0, 0));
                if (i > 0) links.add(new Link(i - 1, i, 1, 1e7, 0, INF, INF));
            }
            compare(bodies, links, speeds, new double[n - 1]);
        }
        slidingBranchedTransmission();
        System.out.println("Boundary search regression: " + checks + " checks passed");
    }
    private static void slidingBranchedTransmission() {
        var bodies = new ArrayList<Body>();
        for (int i = 0; i < 12; i++) bodies.add(i == 0 ? new Body(.0001, 1024, .1, 0, 0, 131072, 512, 64)
            : i == 5 || i == 9 ? new Body(.1, 0, 0, 0, 0)
            : i == 11 ? new Body(1.5, 0, 0, 64, 0) : new Body(0, 0, 0, 0, 0));
        // The full-horizon motion has no reversal, but sliding contacts and the
        // short final implicit step make travel versus trial duration non-monotone.
        var links = List.of(new Link(0, 1, -2, 1e5, 0, INF, INF), new Link(1, 2, -.5, 1e9, .01, INF, 32),
            new Link(2, 3, -2, 1e9, .01, INF, INF), new Link(2, 4, 1, 1e7, 0, INF, 32),
            new Link(1, 5, 1, 1e9, 0, INF, INF), new Link(0, 6, 1, 1e9, 0, INF, 32),
            new Link(6, 7, -1, 1e9, 0, INF, INF), new Link(3, 8, -2, 1e9, 0, INF, 32),
            new Link(4, 9, -2, 1e9, 0, INF, INF), new Link(0, 10, .5, 1e9, .01, INF, 32),
            new Link(5, 11, -1, 1e9, 0, INF, INF));
        compare(bodies, links,
            new double[]{128, -244.44892172821793, 125.83293452609978, -247.01596834841894,
                127.61917189253825, -230.388492083727, 115.25820057401182, -124.5666668549444,
                503.2375537208956, -264.81072734813256, 78.05603397723071, 227.96346042002813},
            new double[]{2.568798562207102e-5, 2.910248591240663e-5, -4.53652424177371e-5,
                -9.808590222191554e-5, 2.3292462391982396e-5, 7.363654652502654e-5,
                3.89678532571365e-5, -2.571839223439023e-5, 5.164240290451845e-5,
                -5.4148254879328395e-5, -8.884733729388384e-6});
    }
    private static void compare(List<Body> bodies, List<Link> links, double[] speeds, double[] twists) {
        for (double horizon : new double[]{DT / 8, DT / 4096}) {
            Result end = TorsionSolver.advance(bodies, links, speeds, twists, horizon, H);
            int shaft = bodies.size() - 1;
            for (double fraction : new double[]{.001, .1, .3, .73, .99, 1}) {
                double budget = end.travel()[shaft] / DT * fraction;
                if (!(budget > 0)) continue;
                var expectedProbes = new ArrayList<Double>();
                double expected = ShaftClock.firstBoundary(end.duration(), t -> {
                    expectedProbes.add(t);
                    return TorsionSolver.advance(bodies, links, speeds, twists, t, H).travel()[shaft] / DT >= budget;
                });
                var captured = TorsionSolver.prepare(bodies, links, speeds, twists);
                captured.advance(horizon, H);
                var actualProbes = new ArrayList<Double>();
                double actual = ShaftClock.firstBoundary(end.duration(), t -> {
                    actualProbes.add(t);
                    return captured.motion(t, H).travel()[shaft] / DT >= budget;
                });
                check(actual == expected, "displacement-only search preserves the exact original boundary");
                check(actualProbes.equals(expectedProbes), "all boundary queries retain their original order and durations");
                same(TorsionSolver.advance(bodies, links, speeds, twists, expected, H), captured.advance(actual, H));
            }
        }
    }
    private static void same(Result expected, Result actual) {
        check(expected.duration() == actual.duration() && expected.broken() == actual.broken()
            && Arrays.equals(expected.speed(), actual.speed()) && Arrays.equals(expected.twist(), actual.twist())
            && Arrays.equals(expected.angle(), actual.angle()) && Arrays.equals(expected.travel(), actual.travel())
            && Arrays.equals(expected.torque(), actual.torque()) && Arrays.equals(expected.slipping(), actual.slipping()),
            "the selected boundary commits exactly the original full physical state");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
