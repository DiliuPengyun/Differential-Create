package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Result;

/** Repeated boundary trials must never consume, alias, or refresh their captured initial state. */
final class SnapshotRegression {
    private static final double DT = 1.0 / 1200, H = DT / 64, INF = Double.POSITIVE_INFINITY;
    private static int checks;
    static void run() {
        var pair = List.of(new Body(.15, 64, 4, 0, 0), new Body(.1, 0, 0, 8, .001));
        trials(pair, List.of(new Link(0, 1, 1, 1e7, 0, INF, INF)), new double[]{128, 128}, new double[]{8e-7});
        trials(pair, List.of(new Link(0, 1, -2, 4e6, 0, INF, 32)), new double[]{128, -32}, new double[1]);
        trials(pair, List.of(new Link(0, 1, 1, 1e7, 0, 10, INF)), new double[]{128, 0}, new double[1]);
        trials(List.of(new Body(.01, -128, 0, 8, 0), new Body(.1, 64, 4, 0, 0)),
            List.of(), new double[]{1, 128}, new double[0]);
        trials(List.of(new Body(.15, 64, 4, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.1, 0, 0, 8, 0)),
            List.of(new Link(0, 1, 1, 1e7, 0, INF, INF), new Link(1, 2, -2, 4e6, 0, INF, INF)),
            new double[]{128, 128, -256}, new double[]{8e-7, 2e-6});
        trials(List.of(pair.get(0), pair.get(1), pair.get(0), pair.get(1), new Body(.1, 0, 0, 0, 0)),
            List.of(new Link(0, 1, 1, 1e7, 0, 10, INF), new Link(2, 3, 1, 1e7, 0, INF, INF),
                new Link(3, 4, 1, 1e7, 0, INF, 0)),
            new double[]{128, 0, 64, 64, 7}, new double[]{0, 8e-7, 1});
        trials(List.of(new Body(.15, 1024, 0, 0, 0, 8192, 256, 16), pair.get(1)),
            List.of(new Link(0, 1, 1, 1e7, 0, INF, INF)), new double[]{512, 128}, new double[1]);
        retainedResults(pair);
        disconnectedLargeSystems();
        System.out.println("Boundary snapshot regression: " + checks + " checks passed");
    }
    private static void disconnectedLargeSystems() {
        var bodies = new ArrayList<Body>(); var links = new ArrayList<Link>();
        // Eight independent systems are large enough to exercise worker batches
        // even after a boundary query has reused its complete-step prefixes.
        int n = 32, count = 8;
        double[] speeds = new double[n * count], twists = new double[(n - 1) * count];
        for (int c = 0; c < count; c++) for (int i = 0; i < n; i++) {
            bodies.add(i == 0 ? new Body(.15, 1024, 0, 0, 0, 131072, 512, 64)
                : i == n - 1 ? new Body(1.5, 0, 0, 64, .001) : new Body(0, 0, 0, 0, 0));
            speeds[c * n + i] = 128;
            if (i > 0) links.add(new Link(c * n + i - 1, c * n + i, 1, 1e7, 0, INF, INF));
        }
        var captured = TorsionSolver.prepare(bodies, links, speeds, twists);
        for (double step : new double[]{H, H / 2, H * 2, H})
            for (double duration : new double[]{DT / 8, DT, H / 256, 1e-12, DT * .73, 0, Math.nextUp(H)}) {
                // A single connected system has no worker path. Reassemble its
                // independent results as the expected global state.
                Result unit = TorsionSolver.advance(bodies.subList(0, n), links.subList(0, n - 1),
                    Arrays.copyOf(speeds, n), Arrays.copyOf(twists, n - 1), duration, step);
                Result result = captured.advance(duration, step);
                check(result.duration() == unit.duration() && result.broken() == -1, "independent clocks keep their original boundary");
                for (int c = 0; c < count; c++) {
                    check(Arrays.equals(unit.speed(), Arrays.copyOfRange(result.speed(), c * n, (c + 1) * n)), "worker speeds match the serial component");
                    check(Arrays.equals(unit.angle(), Arrays.copyOfRange(result.angle(), c * n, (c + 1) * n)), "worker angles match the serial component");
                    check(Arrays.equals(unit.travel(), Arrays.copyOfRange(result.travel(), c * n, (c + 1) * n)), "worker travel matches the serial component");
                    check(Arrays.equals(unit.twist(), Arrays.copyOfRange(result.twist(), c * (n - 1), (c + 1) * (n - 1))), "worker twists match the serial component");
                    check(Arrays.equals(unit.torque(), Arrays.copyOfRange(result.torque(), c * (n - 1), (c + 1) * (n - 1))), "worker torque matches the serial component");
                    check(Arrays.equals(unit.slipping(), Arrays.copyOfRange(result.slipping(), c * (n - 1), (c + 1) * (n - 1))), "worker contact branches match the serial component");
                }
            }
        boolean failed = false;
        try { captured.advance(H, Double.MIN_VALUE); }
        catch (RuntimeException expected) { failed = true; }
        check(failed, "an unrepresentable step retains the original convergence failure");
        same(TorsionSolver.advance(bodies, links, speeds, twists, DT / 8, H), captured.advance(DT / 8, H));
        var weakLinks = new ArrayList<>(links);
        Link first = links.get(n - 1);
        weakLinks.set(n - 1, new Link(first.a(), first.b(), first.ratio(), first.stiffness(), first.damping(), .01, first.friction()));
        Result fractured = TorsionSolver.prepare(bodies, weakLinks, speeds, twists).advance(DT / 8, H);
        var unitWeakLinks = new ArrayList<>(links.subList(0, n - 1));
        Link local = unitWeakLinks.getFirst();
        unitWeakLinks.set(0, new Link(local.a(), local.b(), local.ratio(), local.stiffness(), local.damping(), .01, local.friction()));
        Result weak = TorsionSolver.advance(bodies.subList(0, n), unitWeakLinks,
            Arrays.copyOf(speeds, n), Arrays.copyOf(twists, n - 1), DT / 8, H);
        check(weak.broken() == 0 && fractured.broken() == n - 1 && fractured.duration() == weak.duration(),
            "a worker fracture stops every independent component at the original boundary");
        Result intact = TorsionSolver.advance(bodies.subList(0, n), links.subList(0, n - 1),
            Arrays.copyOf(speeds, n), Arrays.copyOf(twists, n - 1), weak.duration(), H);
        for (int c = 0; c < count; c++) {
            Result expected = c == 1 ? weak : intact;
            check(Arrays.equals(expected.speed(), Arrays.copyOfRange(fractured.speed(), c * n, (c + 1) * n)), "fracture retains each component's original endpoint speed");
            check(Arrays.equals(expected.angle(), Arrays.copyOfRange(fractured.angle(), c * n, (c + 1) * n)), "fracture retains each component's original angle");
            check(Arrays.equals(expected.travel(), Arrays.copyOfRange(fractured.travel(), c * n, (c + 1) * n)), "fracture retains each component's original travel");
            check(Arrays.equals(expected.twist(), Arrays.copyOfRange(fractured.twist(), c * (n - 1), (c + 1) * (n - 1))), "fracture retains each component's original twist");
            check(Arrays.equals(expected.torque(), Arrays.copyOfRange(fractured.torque(), c * (n - 1), (c + 1) * (n - 1))), "fracture retains each component's original torque");
            check(Arrays.equals(expected.slipping(), Arrays.copyOfRange(fractured.slipping(), c * (n - 1), (c + 1) * (n - 1))), "fracture retains each component's original contact branch");
        }
        Link second = links.get(2 * (n - 1));
        weakLinks.set(2 * (n - 1), new Link(second.a(), second.b(), second.ratio(), second.stiffness(), second.damping(), .01, second.friction()));
        Result tied = TorsionSolver.prepare(bodies, weakLinks, speeds, twists).advance(DT / 8, H);
        check(tied.duration() == weak.duration() && tied.broken() == n - 1,
            "simultaneous worker fractures retain the original first edge regardless of completion order");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void retainedResults(List<Body> pair) {
        var links = List.of(new Link(0, 1, -2, 4e6, 0, INF, 32));
        double[] speeds = {128, -32}, twists = {0};
        var first = TorsionSolver.prepare(pair, links, speeds, twists);
        var other = TorsionSolver.prepare(pair, links, new double[]{-128, 32}, twists);
        Result expected = TorsionSolver.advance(pair, links, speeds, twists, DT / 8, H);
        Result retained = first.advance(DT / 8, H);
        var motion = first.motion(DT / 8, H);
        double[] angle = motion.angle().clone(), travel = motion.travel().clone();
        // Scratch is reused across queries, refinements and separate captures;
        // callers may retain complete or motion-only results throughout them.
        for (double step : new double[]{H, H / 2, H * 2, H / 4, H}) {
            for (double duration : new double[]{DT, H / 256, DT / 2, 1e-12, DT / 4096, 0}) {
                first.advance(duration, step);
                first.motion(duration, step);
                other.advance(duration, step);
                same(expected, retained);
                checks++;
                if (!Arrays.equals(angle, motion.angle()) || !Arrays.equals(travel, motion.travel()))
                    throw new AssertionError("retained motion must not alias reused solver scratch");
            }
        }
    }
    private static void trials(List<Body> bodies, List<Link> links, double[] speeds, double[] twists) {
        double edge = 13 * H;
        double[] durations = {DT, DT / 2, 0, H, Math.nextUp(H), Math.nextDown(H), DT / 128,
            edge, Math.nextUp(edge), Math.nextDown(edge), edge + 4 * Math.ulp(edge), edge + 8 * Math.ulp(edge),
            edge + 16 * Math.ulp(edge), DT * .73, DT / 4096, DT / 8, DT};
        var expected = new ArrayList<Result>();
        for (double duration : durations) expected.add(TorsionSolver.advance(bodies, links, speeds, twists, duration, H));
        var capturedBodies = new ArrayList<>(bodies); var capturedLinks = new ArrayList<>(links);
        var capturedSpeeds = speeds.clone(); var capturedTwists = twists.clone();
        var snapshot = TorsionSolver.prepare(capturedBodies, capturedLinks, capturedSpeeds, capturedTwists);
        // Later callbacks may change the source collections and physical rotor state.
        capturedBodies.clear(); capturedLinks.clear();
        Arrays.fill(capturedSpeeds, -999); Arrays.fill(capturedTwists, 999);
        for (int i = 0; i < durations.length; i++) {
            Result actual = snapshot.advance(durations[i], H);
            same(expected.get(i), actual);
            // Consumers own each result; changing one must not affect subsequent trials.
            Arrays.fill(actual.speed(), 999); Arrays.fill(actual.twist(), 999);
            Arrays.fill(actual.angle(), 999); Arrays.fill(actual.travel(), 999);
            Arrays.fill(actual.torque(), 999); Arrays.fill(actual.slipping(), true);
        }
        for (int i = durations.length - 1; i >= 0; i--) same(expected.get(i), snapshot.advance(durations[i], H));
        for (int i = 0; i < durations.length; i++) {
            var motion = snapshot.motion(durations[i], H);
            checks++;
            if (motion.duration() != expected.get(i).duration() || !Arrays.equals(motion.angle(), expected.get(i).angle())
                || !Arrays.equals(motion.travel(), expected.get(i).travel()))
                throw new AssertionError("motion-only probe must retain the full solver's clock and exact displacement");
            Arrays.fill(motion.angle(), 999); Arrays.fill(motion.travel(), 999);
            same(expected.get(i), snapshot.advance(durations[i], H));
        }
        // Changing numerical step size must discard incompatible prefixes; changing
        // it back must reproduce the same fixed-input trajectory, including fracture.
        for (double maxStep : new double[]{H / 2, H * 2, H, H / 4, H})
            for (double duration : new double[]{DT, DT / 8, Math.nextUp(7 * maxStep), DT / 4096})
                same(TorsionSolver.advance(bodies, links, speeds, twists, duration, maxStep),
                    snapshot.advance(duration, maxStep));
    }
    private static void same(Result expected, Result actual) {
        checks++;
        if (expected.duration() != actual.duration() || expected.broken() != actual.broken()
            || !Arrays.equals(expected.speed(), actual.speed()) || !Arrays.equals(expected.twist(), actual.twist())
            || !Arrays.equals(expected.angle(), actual.angle()) || !Arrays.equals(expected.travel(), actual.travel())
            || !Arrays.equals(expected.torque(), actual.torque()) || !Arrays.equals(expected.slipping(), actual.slipping()))
            throw new AssertionError("prepared boundary trials must match fresh integration exactly");
    }
}
