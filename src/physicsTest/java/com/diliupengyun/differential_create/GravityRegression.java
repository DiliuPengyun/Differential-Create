package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.ShaftPotential;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;

/** Physical work, acceleration, support reactions and pure event captures. */
public final class GravityRegression {
    private static final double DT = 1.0 / 1200, H = DT / 64;
    private static int checks;
    public static void main(String[] args) { run(); }
    public static void run() {
        double mass = .0006, lead = 1200.0 / 512, radius = lead / (2 * Math.PI);
        var lifting = new ShaftPotential(mass * ShaftPotential.WEIGHT * lead, List.of());
        var winch = new Body(mass * radius * radius, 0, 0, 0, 0).withPotential(lifting);
        var falling = TorsionSolver.advance(List.of(winch), List.of(), new double[1], new double[0], 20 * DT, H);
        near(-ShaftPotential.GRAVITY, falling.speed()[0] * lead / 60, 1e-8, "one second unconstrained fall velocity");
        near(-ShaftPotential.GRAVITY / 2, falling.angle()[0] * lead, 1e-8, "one second free fall displacement");
        near(0, .5 * winch.inertia() * Math.pow(falling.speed()[0], 2) + lifting.change(0, falling.angle()[0]), 1e-8, "fall work balance");

        var pendulum = new ShaftPotential(0, List.of(new ShaftPotential.Harmonic(0, mass * ShaftPotential.WEIGHT, 2 * Math.PI)));
        near(pendulum.change(.17, -.13), pendulum.shifted(.17).change(0, -.13), 1e-12,
            "accepted event recapture preserves the potential phase");
        var bearing = new Body(.01 + mass, 0, 0, 0, 0).withPotential(pendulum);
        var swing = TorsionSolver.advance(List.of(bearing), List.of(), new double[1], new double[0], 400 * DT, H);
        near(0, .5 * bearing.inertia() * swing.speed()[0] * swing.speed()[0] + pendulum.change(0, swing.angle()[0]), 1e-8, "pendulum exchanges kinetic and gravitational energy");
        var initial = TorsionSolver.advance(List.of(bearing), List.of(), new double[1], new double[0], DT, H);
        check(initial.speed()[0] < 0, "eccentric bearing starts from rest without a source");
        var boundedSource = new Body(bearing.inertia(), 0, 0, 0, 0, 0, 0, 1).withPotential(pendulum);
        var independent = TorsionSolver.advance(List.of(boundedSource), List.of(), new double[1], new double[0], DT, H);
        near(initial.speed()[0], independent.speed()[0], 1e-10, "source power limits cannot suppress gravity");

        var supported = TorsionSolver.advance(List.of(winch.withStop(-1)), List.of(), new double[1], new double[0], DT, H);
        near(0, supported.speed()[0], 0, "lower stop supports weight at rest");
        var release = TorsionSolver.advance(List.of(winch.withStop(1)), List.of(), new double[1], new double[0], DT, H);
        check(release.speed()[0] < 0, "upper stop releases for inward gravity");
        var held = new Body(winch.inertia(), 0, 0, lifting.linear() * 2, 0).withPotential(lifting);
        near(0, TorsionSolver.advance(List.of(held), List.of(), new double[1], new double[0], DT, H).speed()[0], 0, "finite brake holds below its capacity");
        var slipping = new Body(winch.inertia(), 0, 0, lifting.linear() / 2, 0).withPotential(lifting);
        check(TorsionSolver.advance(List.of(slipping), List.of(), new double[1], new double[0], DT, H).speed()[0] < 0, "overloaded brake slips");

        var limited = winch.withSpeedLimit(30);
        var terminal = TorsionSolver.advance(List.of(limited), List.of(), new double[1], new double[0], 20 * DT, H);
        near(-30, terminal.speed()[0], 1e-10, "native travel cap is a passive speed brake");
        check(.5 * limited.inertia() * terminal.speed()[0] * terminal.speed()[0]
            + lifting.change(0, terminal.angle()[0]) < 0, "speed brake dissipates the excess potential energy");
        var steady = TorsionSolver.advance(List.of(limited), List.of(), new double[] {-30}, new double[0], DT, H);
        near(-30 * DT, steady.angle()[0], 1e-10, "terminal fall stays within the native travel cap");
        var reversing = new Body(winch.inertia(), 2 * lifting.linear(), 0, 0, 0).withPotential(lifting).withSpeedLimit(30);
        var released = TorsionSolver.advance(List.of(reversing), List.of(), new double[] {-30}, new double[0], DT, H);
        check(released.speed()[0] > -30, "speed brake releases when the applied force reverses");

        var capture = TorsionSolver.prepare(List.of(bearing, winch), List.of(), new double[2], new double[0]);
        var limit = List.of(new TorsionSolver.EventLimit(0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, .001, 0));
        var event = capture.advanceToEvent(30 * DT, H, limit);
        near(-.001, event.angle()[0], 1e-9, "gravity locates signed travel event");
        capture.advance(50 * DT, H);
        var repeat = capture.advanceToEvent(30 * DT, H, limit);
        near(event.speed()[0], repeat.speed()[0], 1e-10, "position-dependent event trials are independent of probe order");
        near(event.angle()[1], repeat.angle()[1], 1e-10, "disconnected gravity component keeps the common event time");
        var link = new Link(0, 1, 1, 1e7, 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        var driven = TorsionSolver.advance(List.of(bearing, new Body(.02, 0, 0, 0, 0)), List.of(link), new double[2], new double[1], 10 * DT, H);
        check(driven.speed()[1] < 0, "gravity backdrives a connected rotor");
        near(0, .5 * bearing.inertia() * Math.pow(driven.speed()[0], 2) + .01 * Math.pow(driven.speed()[1], 2)
            + .5e7 * Math.pow(driven.twist()[0], 2) + pendulum.change(0, driven.angle()[0]), 1e-8, "elastic shaft plus gravity conserve energy");
        System.out.println("Gravity regression: " + checks + " checks passed");
    }
    private static void near(double expected, double actual, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
