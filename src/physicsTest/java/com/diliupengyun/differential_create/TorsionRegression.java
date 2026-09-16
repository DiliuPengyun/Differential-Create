package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;

/** Expectations follow momentum/work balance, not the implementation's integration formula. */
public final class TorsionRegression {
    private static int checks;
    private static final double DT = 1.0 / 1200, H = DT / 64;
    public static void main(String[] args) { run(); }
    public static void run() {
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var links = List.of(link(0, 1, 1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        var s = TorsionSolver.advance(bodies, links, new double[]{16, 0}, new double[1], 60 * DT, H);
        near(8, s.speed()[0], .001, "equal flywheels synchronize");
        near(8, s.speed()[1], .001, "driven flywheel synchronizes");
        near(8, .5 * (s.speed()[0] + s.speed()[1]), 1e-8, "angular momentum conserved");
        check(energy(bodies, links, s.speed(), s.twist()) <= 64, "passive engagement cannot create energy");
        var undamped = List.of(new Link(0, 1, 1, 1e7, 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        s = TorsionSolver.advance(bodies, undamped, new double[]{16, 0}, new double[1], DT, H);
        near(64, energy(bodies, undamped, s.speed(), s.twist()), 1e-7, "undamped kinetic plus spring energy");

        var weak = List.of(link(0, 1, 1, 8192, Double.POSITIVE_INFINITY));
        s = TorsionSolver.advance(bodies, weak, new double[]{16, 0}, new double[1], DT, H);
        check(s.broken() == 0 && s.duration() < DT, "impact fractures inside a tick");
        check(s.speed()[0] < 16 && s.speed()[1] > 0 && s.speed()[0] > s.speed()[1], "fracture preserves partial impulse");
        near(8, .5 * (s.speed()[0] + s.speed()[1]), 1e-8, "fracture retains momentum");
        var finer = TorsionSolver.advance(bodies, weak, new double[]{16, 0}, new double[1], DT, H / 2);
        near(s.duration(), finer.duration(), H / 8, "fracture time converges with refinement");
        near(s.speed()[0], finer.speed()[0], .05, "fracture speed converges with refinement");

        for (double q : new double[]{2, -2, .5, -.5}) {
            var geared = List.of(link(0, 1, q, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
            s = TorsionSolver.advance(bodies, geared, new double[]{16, 0}, new double[1], 80 * DT, H);
            double expected = 16 / (1 + q * q);
            near(expected, s.speed()[0], .003, "geared driver momentum");
            near(q * expected, s.speed()[1], .003, "signed gear ratio");
        }
        var massless = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var shaft = List.of(link(0, 1, 1, 8192, Double.POSITIVE_INFINITY), link(1, 2, 1, 8192, Double.POSITIVE_INFINITY));
        s = TorsionSolver.advance(massless, shaft, new double[]{16, 0, 0}, new double[2], DT, H);
        check(s.broken() >= 0, "zero-inertia shaft transmits breaking torque");
        near(8, .5 * (s.speed()[0] + s.speed()[2]), 1e-7, "massless joint adds no momentum");

        var clutch = List.of(link(0, 1, 1, 8192, 32));
        s = TorsionSolver.advance(bodies, clutch, new double[]{16, -16}, new double[1], DT, H);
        check(s.broken() < 0 && s.slipping()[0], "clutch slips instead of exceeding its limit");
        check(Math.abs(s.torque()[0]) <= 32, "friction cap");
        near(16 - 32 * DT / .5, s.speed()[0], 1e-5, "friction transfers finite impulse");
        check(energy(bodies, clutch, s.speed(), s.twist()) <= 128, "friction cannot create energy");
        var loaded = List.of(new Body(.15, 64, 4, 0, 0), new Body(.01, 0, 0, 8, 0));
        s = TorsionSolver.advance(loaded, clutch, new double[]{0, 0}, new double[1], 600 * DT, H);
        near(14, s.speed()[0], .001, "loaded driver settles");
        near(14, s.speed()[1], .001, "partial clutch can synchronize under load");
        near(8, s.torque()[0], .001, "holding torque is retained at zero slip");
        var stalled = List.of(new Body(.15, 64, 4, 0, 0), new Body(.01, 0, 0, 40, 0));
        s = TorsionSolver.advance(stalled, clutch, new double[]{0, 0}, new double[1], 100 * DT, H);
        near(0, s.speed()[1], 1e-8, "static friction holds an overloaded clutch output");

        // A steady torque can break a link even with equal shaft speeds and no collision.
        var loadedWeak = List.of(link(0, 1, 1, 4, Double.POSITIVE_INFINITY));
        s = TorsionSolver.advance(loaded, loadedWeak, new double[]{14, 14}, new double[]{8 / 1e7}, DT, H);
        check(s.broken() == 0, "steady overload breaks a prestressed connection");

        // Parallel branches split load by stiffness; swapping edge traversal preserves the answer.
        var parallel = List.of(new Link(0, 1, 1, 1e7, 128, 8192, Double.POSITIVE_INFINITY),
            new Link(0, 1, 1, 2e7, 256, 8192, Double.POSITIVE_INFINITY));
        s = TorsionSolver.advance(loaded, parallel, new double[]{14, 14}, new double[2], 10 * DT, H);
        near(8.0 / 3, s.torque()[0], .002, "parallel soft branch load");
        near(16.0 / 3, s.torque()[1], .002, "parallel stiff branch load");
        var reordered = TorsionSolver.advance(loaded, List.of(parallel.get(1), parallel.get(0)),
            new double[]{14, 14}, new double[2], 10 * DT, H);
        near(s.speed()[0], reordered.speed()[0], 1e-9, "edge order independence");
        double q = -2;
        var forward = List.of(new Link(0, 1, q, 1e7, 128, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        var backward = List.of(new Link(1, 0, 1 / q, 1e7 * q * q, 128 * q * q, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        var f = TorsionSolver.advance(bodies, forward, new double[]{16, 0}, new double[1], DT, H);
        var r = TorsionSolver.advance(bodies, backward, new double[]{16, 0}, new double[1], DT, H);
        near(f.speed()[0], r.speed()[0], 1e-9, "port reference reversal preserves driver dynamics");
        near(f.speed()[1], r.speed()[1], 1e-9, "port reference reversal preserves driven dynamics");
        System.out.println("Torsion regression: " + checks + " checks passed");
    }
    private static Link link(int a, int b, double q, double strength, double friction) {
        return new Link(a, b, q, 1e7, 128, strength, friction);
    }
    private static double energy(List<Body> bodies, List<Link> links, double[] v, double[] twist) {
        double sum = 0;
        for (int i = 0; i < v.length; i++) sum += .5 * bodies.get(i).inertia() * v[i] * v[i];
        for (int i = 0; i < twist.length; i++) sum += .5 * links.get(i).stiffness() * twist[i] * twist[i];
        return sum;
    }
    private static void near(double expected, double actual, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": " + expected + " != " + actual);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
