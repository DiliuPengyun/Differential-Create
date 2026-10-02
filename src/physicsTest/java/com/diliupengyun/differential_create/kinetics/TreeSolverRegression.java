package com.diliupengyun.differential_create.kinetics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Result;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.EventLimit;

/** Independent decimal equations and conservative branch/search regression tests. */
public final class TreeSolverRegression {
    private static final MathContext MC = new MathContext(80, RoundingMode.HALF_EVEN);
    private static final double DT = 1.0 / 1200, H = DT / 64, INF = Double.POSITIVE_INFINITY;
    private static int checks;
    private record Graph(List<Body> bodies, List<Link> links, double[] speed, double[] twist) {
        TorsionSolver.Prepared capture(boolean reference) {
            return reference ? TorsionSolver.prepareReference(bodies, links, speed, twist)
                : TorsionSolver.prepare(bodies, links, speed, twist);
        }
    }

    public static void run() {
        decimalReference();
        guardedCaptures();
        searchRestart();
        disconnectedSearchRestart();
        System.out.println("Tree solver regression: " + checks + " checks passed");
    }

    private static void decimalReference() {
        var random = new Random(0x80261002L);
        for (int n : new int[]{4, 8, 16, 32}) for (int scenario = 0; scenario < 24; scenario++) {
            var bodies = new ArrayList<Body>();
            int[] a = new int[n - 1], b = new int[n - 1];
            double[] ratio = new double[n - 1], weights = new double[n - 1], base = new double[n], expected = new double[n];
            boolean[] fixed = new boolean[n];
            double h = H / Math.pow(2, scenario % 8), stiffness = Math.pow(10, 5 + scenario % 5);
            for (int i = 0; i < n; i++) {
                double inertia = i == 0 || i == n - 1 || scenario % 4 == 0 ? .05 + random.nextDouble() : 0;
                bodies.add(new Body(inertia, 0, 0, 0, 0));
                base[i] = 2 * inertia / h + (scenario % 6 == 0 ? .03 : 0);
                expected[i] = random.nextDouble() * 8 - 4;
            }
            for (int e = 0; e < n - 1; e++) {
                a[e] = scenario % 3 == 0 ? e : e / 2; b[e] = e + 1;
                ratio[e] = scenario % 3 == 1 ? (e % 2 == 0 ? -1 : 1)
                    : scenario % 3 == 2 ? (e % 2 == 0 ? 2 : .5) : 1;
                weights[e] = h * stiffness / 2;
            }
            var factor = TreeFactorization.create(bodies, a, b, ratio);
            check(factor != null, "connected tree is eligible");
            // Change each part of the reuse key. A stale factor must not survive a changed
            // inertia/source diagonal, contact coefficient, or pinned-node set.
            for (int mutation = 0; mutation < 4; mutation++) {
                if (mutation == 1) base[0] *= 1.5;
                if (mutation == 2) weights[0] *= .5;
                if (mutation == 3) fixed[n / 2] = true;
                var matrix = exactMatrix(base, a, b, ratio, weights, fixed);
                double[] rhs = new double[n], diagonal = new double[n];
                for (int i = 0; i < n; i++) {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (int j = 0; j < n; j++) sum = sum.add(matrix[i][j].multiply(value(expected[j]), MC), MC);
                    rhs[i] = sum.doubleValue(); diagonal[i] = matrix[i][i].doubleValue();
                }
                check(factor.factor(base, diagonal, weights, fixed), "positive definite tree factorizes");
                check(factor.factor(base.clone(), diagonal.clone(), weights.clone(), fixed.clone()), "equal matrix remains reusable");
                BigDecimal[] reference = gaussian(matrix, rhs);
                double[] solution = new double[n];
                factor.solve(rhs, solution);
                for (int i = 0; i < n; i++) {
                    double error = value(solution[i]).subtract(reference[i], MC).abs().doubleValue();
                    check(error <= 1e-11 * (1 + reference[i].abs().doubleValue()), "80-digit forward solution");
                    BigDecimal sum = BigDecimal.ZERO, magnitude = value(rhs[i]).abs();
                    for (int j = 0; j < n; j++) {
                        BigDecimal term = matrix[i][j].multiply(value(solution[j]), MC);
                        sum = sum.add(term, MC); magnitude = magnitude.add(term.abs(), MC);
                    }
                    check(sum.subtract(value(rhs[i]), MC).abs().doubleValue() <= 1e-12 * (1 + magnitude.doubleValue()),
                        "80-digit row residual");
                }
            }
        }
        var passive = List.of(new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0),
            new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        var free = TreeFactorization.create(passive, new int[]{0, 1, 2}, new int[]{1, 2, 3}, new double[]{1, -1, 2});
        check(!free.factor(new double[4], new double[]{1, 2, 5, 1}, new double[]{1, 1, 1}, new boolean[4]),
            "free massless mode does not invent an anchor");
        check(TreeFactorization.create(passive, new int[]{0, 1, 2, 3}, new int[]{1, 2, 3, 0}, new double[]{1, 1, 1, 1}) == null,
            "cyclic graph keeps CG");
        check(TreeFactorization.create(passive, new int[]{0, 0, 1}, new int[]{1, 2, 2}, new double[]{1, 1, 1}) == null,
            "disconnected graph with a cycle keeps CG");
    }

    private static BigDecimal value(double number) { return new BigDecimal(number, MC); }

    private static BigDecimal[][] exactMatrix(double[] base, int[] a, int[] b, double[] ratio, double[] weights, boolean[] fixed) {
        int n = base.length;
        var matrix = new BigDecimal[n][n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(matrix[i], BigDecimal.ZERO); matrix[i][i] = fixed[i] ? BigDecimal.ONE : value(base[i]);
        }
        for (int e = 0; e < a.length; e++) {
            int x = a[e], y = b[e];
            BigDecimal w = value(weights[e]), q = value(ratio[e]);
            if (!fixed[x]) matrix[x][x] = matrix[x][x].add(w.multiply(q, MC).multiply(q, MC), MC);
            if (!fixed[y]) matrix[y][y] = matrix[y][y].add(w, MC);
            if (!fixed[x] && !fixed[y]) matrix[x][y] = matrix[y][x] = w.multiply(q, MC).negate();
        }
        return matrix;
    }

    /** Dense partial-pivot Gaussian elimination shares no tree factorization code. */
    private static BigDecimal[] gaussian(BigDecimal[][] matrix, double[] rhs) {
        int n = rhs.length;
        var a = new BigDecimal[n][n]; var b = new BigDecimal[n];
        for (int i = 0; i < n; i++) { a[i] = matrix[i].clone(); b[i] = value(rhs[i]); }
        for (int k = 0; k < n; k++) {
            int pivot = k;
            for (int i = k + 1; i < n; i++) if (a[i][k].abs().compareTo(a[pivot][k].abs()) > 0) pivot = i;
            var row = a[k]; a[k] = a[pivot]; a[pivot] = row;
            var number = b[k]; b[k] = b[pivot]; b[pivot] = number;
            check(a[k][k].signum() != 0, "independent reference is nonsingular");
            for (int i = k + 1; i < n; i++) {
                BigDecimal factor = a[i][k].divide(a[k][k], MC);
                for (int j = k + 1; j < n; j++) a[i][j] = a[i][j].subtract(factor.multiply(a[k][j], MC), MC);
                b[i] = b[i].subtract(factor.multiply(b[k], MC), MC); a[i][k] = BigDecimal.ZERO;
            }
        }
        var result = new BigDecimal[n];
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal sum = b[i];
            for (int j = i + 1; j < n; j++) sum = sum.subtract(a[i][j].multiply(result[j], MC), MC);
            result[i] = sum.divide(a[i][i], MC);
        }
        return result;
    }

    private static Graph chain(int n) {
        var bodies = new ArrayList<Body>(); var links = new ArrayList<Link>();
        double[] speed = new double[n]; Arrays.fill(speed, 128);
        for (int i = 0; i < n; i++) {
            bodies.add(i == 0 ? new Body(.15, 1024, 0, 0, 0, 131072, 512, 64)
                : i == n - 1 ? new Body(1.5, 0, 0, 64, .001) : new Body(0, 0, 0, 0, 0));
            if (i > 0) links.add(new Link(i - 1, i, 1, 1e7, 0, INF, INF));
        }
        return new Graph(bodies, links, speed, new double[n - 1]);
    }

    private static void guardedCaptures() {
        var graph = chain(8);
        var capture = graph.capture(false);
        Result full = capture.advance(DT / 8, H);
        check(capture.fallbackRevision() == 0, "ordinary tree uses direct solve");
        compare(graph.capture(true).advance(DT / 8, H), full, false);
        compare(graph.capture(true).advance(1e-12, H), capture.advance(1e-12, H), true);
        check(capture.fallbackRevision() > 0, "very short interval returns to original solve");
        // A failed/guarded probe must not change a later ordinary query or its prefix cache.
        compare(full, capture.advance(DT / 8, H), true);
        for (int scenario = 0; scenario < 4; scenario++) {
            var bodies = new ArrayList<>(graph.bodies); var links = new ArrayList<>(graph.links);
            double[] speed = graph.speed.clone(), twist = graph.twist.clone();
            if (scenario == 0) speed[7] = 0; // Static body friction.
            if (scenario == 1) { // Finite contact already slipping.
                Link edge = links.getLast();
                links.set(6, new Link(edge.a(), edge.b(), edge.ratio(), edge.stiffness(), 0, INF, 32)); twist[6] = .001;
            }
            if (scenario == 2) { // Strength crossing and the original fracture search.
                Link edge = links.getLast();
                links.set(6, new Link(edge.a(), edge.b(), edge.ratio(), edge.stiffness(), 0, 10, INF)); speed[7] = 120;
            }
            if (scenario == 3) for (int i = 0; i < 8; i++)
                bodies.set(i, new Body(0, i == 0 ? 1 : i == 7 ? -1 : 0, 0, 0, 0));
            var guarded = new Graph(bodies, links, speed, twist);
            var candidate = guarded.capture(false);
            compare(guarded.capture(true).advance(DT / 8, H), candidate.advance(DT / 8, H), true);
            check(candidate.fallbackRevision() > 0, "sensitive capture is guarded: " + scenario);
        }
        // The small systems that motivated the fallback overhead control stay on CG.
        var small = chain(2); var smallCapture = small.capture(false);
        compare(small.capture(true).advance(DT / 8, H), smallCapture.advance(DT / 8, H), true);
        check(smallCapture.fallbackRevision() == 0, "tiny systems avoid speculative fallback");
    }

    private static void searchRestart() {
        var graph = chain(8);
        double budget = graph.capture(true).advance(1e-12, H).travel()[7];
        var candidate = graph.capture(false);
        var limit = List.of(new EventLimit(7, budget, INF, INF));
        compare(graph.capture(true).advanceToEvent(DT / 8, H, limit),
            candidate.advanceToEvent(DT / 8, H, limit), true);
        check(candidate.fallbackRevision() > 0, "short event probe restarts the entire search");
        for (double fraction : new double[]{.001, .1, .73, .99, 1.1}) {
            double travel = graph.capture(true).advance(DT / 8, H).travel()[7] * fraction;
            var event = List.of(new EventLimit(7, travel, INF, INF));
            compare(graph.capture(true).advanceToEvent(DT / 8, H, event),
                graph.capture(false).advanceToEvent(DT / 8, H, event), false);
        }
    }

    private static void disconnectedSearchRestart() {
        var unit = chain(32);
        var bodies = new ArrayList<Body>(); var links = new ArrayList<Link>();
        double[] speed = new double[96], twist = new double[93];
        for (int component = 0; component < 3; component++) {
            bodies.addAll(unit.bodies);
            System.arraycopy(unit.speed, 0, speed, component * 32, 32);
            for (Link edge : unit.links) links.add(new Link(edge.a() + component * 32, edge.b() + component * 32,
                edge.ratio(), edge.stiffness(), edge.damping(), edge.strength(), edge.friction()));
        }
        // Two ordinary components and one with static friction: the initial full trial
        // mixes algorithms, so all components must be recomputed before even a no-event commit.
        speed[95] = 0;
        var graph = new Graph(bodies, links, speed, twist);
        var candidate = graph.capture(false);
        var noEvent = List.of(new EventLimit(31, INF, INF, INF));
        compare(graph.capture(true).advanceToEvent(DT / 8, H, noEvent),
            candidate.advanceToEvent(DT / 8, H, noEvent), true);
        check(candidate.fallbackRevision() > 0, "one guarded component returns all components to the original event clock");
        double budget = unit.capture(true).advance(DT / 8, H).travel()[31] * .73;
        var event = List.of(new EventLimit(31, budget, INF, INF));
        compare(graph.capture(true).advanceToEvent(DT / 8, H, event),
            graph.capture(false).advanceToEvent(DT / 8, H, event), true);
    }

    private static void compare(Result expected, Result actual, boolean exact) {
        check(expected.broken() == actual.broken() && Arrays.equals(expected.slipping(), actual.slipping()), "discrete branches agree");
        check(exact ? expected.duration() == actual.duration() : Math.abs(expected.duration() - actual.duration()) <= 1e-15,
            "event duration agrees");
        double[][] a = {expected.speed(), expected.twist(), expected.angle(), expected.travel(), expected.torque()};
        double[][] b = {actual.speed(), actual.twist(), actual.angle(), actual.travel(), actual.torque()};
        for (int field = 0; field < a.length; field++) for (int i = 0; i < a[field].length; i++)
            check(exact ? a[field][i] == b[field][i] : Math.abs(a[field][i] - b[field][i]) <= 1e-9 * (1 + Math.abs(a[field][i])),
                "captured result agrees, field=" + field + ", element=" + i);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
