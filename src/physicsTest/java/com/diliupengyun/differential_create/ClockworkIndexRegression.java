package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.*;

/** Physical indexing: input reaction, elastic storage, geometric dwell and time changes. */
public final class ClockworkIndexRegression {
    private static int checks;
    private static void require(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) { run(); }
    public static void run() {
        for (double inertia : new double[]{.001, .01, .1, 1, 8}) for (int direction : new int[]{-1, 1}) {
            Rig rig = new Rig(inertia, direction * 64, ShaftPotential.NONE);
            rig.target = direction / 60d;
            double initial = rig.energy(), peak = 0;
            for (int n = 0; n < 20000; n++) {
                rig.step(); peak = Math.max(peak, .5 * inertia * rig.speed[1] * rig.speed[1]);
                require(Math.abs(rig.energy() + rig.index.captureLoss - initial) < 2e-8,
                    "balanced index conserves rotor plus elastic energy including capture residue");
                if (!rig.index.moving && Math.abs(rig.position - rig.target) < 2e-6) break;
            }
            require(!rig.index.moving && Math.abs(rig.position - rig.target) < 2e-6, "index arrives under finite torque: J=" + inertia);
            require(rig.returned > peak * .9, "deceleration returns energy to the input rather than a casing brake");
            require(rig.index.captureLoss < 1e-14, "normal capture removes only event locator roundoff");
            double position = rig.position, input = rig.speed[0], energy = rig.energy();
            for (int n = 0; n < 100; n++) rig.step();
            require(rig.position == position && rig.speed[1] == 0 && rig.speed[0] == input && rig.energy() == energy,
                "geometric dwell neither creeps nor loads the still spinning input");
        }
        for (double inertia : new double[]{.001, .01, .1, 1}) for (double input : new double[]{-8, 8, -64, 64})
            for (double force : new double[]{-400, -20, 20, 400}) {
                var potential = new ShaftPotential(-force, List.of());
                Rig rig = new Rig(inertia, input, potential); rig.target = 1d / 60;
                double initial = rig.energy();
                for (int n = 0; n < 16000; n++) {
                    rig.step();
                    require(rig.energy() + rig.index.captureLoss <= initial + 2e-7, "gravity and gear changes cannot manufacture energy");
                    if (!rig.index.moving && Math.abs(rig.position - rig.target) < 2e-6) break;
                }
                require(!rig.index.moving && Math.abs(rig.position - rig.target) < 2e-6,
                    "loaded hand reaches its stop: J=" + inertia + " input=" + input + " gravity=" + force
                        + " position=" + rig.position + " speed=" + rig.speed[1]);
            }
        Rig powerless = new Rig(.01, 0, new ShaftPotential(-400, List.of())); powerless.target = .5;
        for (int i = 0; i < 100; i++) powerless.step();
        require(powerless.position == 0 && powerless.speed[1] == 0, "a new time cannot release an unpowered parked hand");
        Rig overload = new Rig(.1, 0, new ShaftPotential(-2 * ClockworkTransmission.LOCK_TORQUE, List.of()));
        overload.step(); require(overload.speed[1] > 0, "lock has finite strength under gravity");
        Rig insufficient = new Rig(.1, 1, new ShaftPotential(400, List.of())); insufficient.target = .25;
        for (int i = 0; i < 2000; i++) {
            insufficient.step();
            require(insufficient.position <= .5 / 400 + 1e-9, "insufficient input energy cannot lift a hand to the requested minute");
        }
        Rig curved = new Rig(.1, 64, new ShaftPotential(0, List.of(new ShaftPotential.Harmonic(10, 20, 2 * Math.PI))));
        curved.target = .25; double conserved = curved.energy();
        for (int i = 0; i < 2400; i++) {
            curved.step(); require(curved.energy() <= conserved + 2e-7, "nonlinear gravity and transmission share a passive work balance");
        }
        Rig retarget = new Rig(.01, 64, ShaftPotential.NONE); retarget.target = .25;
        retarget.step(); double first = retarget.index.destination; retarget.target = -.25;
        retarget.step(); require(retarget.index.destination == first && retarget.speed[1] > 0,
            "time or mode changes cannot replace the mechanical stroke in flight");
        var parked = TorsionSolver.advance(List.of(new TorsionSolver.Body(0, 0, 0, 0, 0),
                new TorsionSolver.Body(.01, 0, 0, 0, 0).withStop(2)),
            List.of(new TorsionSolver.Link(0, 1, 0, ClockworkTransmission.STIFFNESS, 0, Double.POSITIVE_INFINITY, 1024)),
            new double[]{32, 0}, new double[]{.0001}, RotationMath.TICK_MINUTES, ClockworkTransmission.INTERVAL);
        require(Double.isFinite(parked.speed()[0]) && parked.speed()[1] == 0 && parked.twist()[0] == .0001,
            "zero-ratio dwell preserves prestress and never divides by zero on a massless input");
        double[] velocity = {10, -3}; double twist = .0001;
        double initial = .5 * velocity[0] * velocity[0] + .25 * velocity[1] * velocity[1] + ClockworkTransmission.energy(twist);
        var random = new java.util.Random(20261101);
        for (int i = 0; i < 2000; i++) {
            double ratio = i % 7 == 0 ? 0 : random.nextDouble(-1, 1);
            var result = TorsionSolver.advance(List.of(new TorsionSolver.Body(1, 0, 0, 0, 0), new TorsionSolver.Body(.5, 0, 0, 0, 0)),
                List.of(new TorsionSolver.Link(0, 1, ratio, ClockworkTransmission.STIFFNESS, 0,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), velocity, new double[]{twist},
                ClockworkTransmission.INTERVAL, ClockworkTransmission.INTERVAL);
            velocity = result.speed(); twist = result.twist()[0];
            double energy = .5 * velocity[0] * velocity[0] + .25 * velocity[1] * velocity[1] + ClockworkTransmission.energy(twist);
            require(Math.abs(energy - initial) < 1e-8, "arbitrary ratio changes preserve energy when elastic state is retained");
        }
        System.out.println("Clock index regression: " + checks + " checks passed");
    }

    private static final class Rig {
        final double inertia;
        final ShaftPotential gravity;
        final ClockworkTransmission.State index = new ClockworkTransmission.State();
        double[] speed; double position, target, returned;
        Rig(double inertia, double input, ShaftPotential gravity) { this.inertia = inertia; this.gravity = gravity; speed = new double[]{input, 0}; }
        double energy() { return .5 * speed[0] * speed[0] + .5 * inertia * speed[1] * speed[1]
            + ClockworkTransmission.energy(index.twist) + gravity.change(0, position); }
        void step() {
            var potential = gravity.shifted(position);
            index.prepare(position, target - position, speed[1], speed[0], inertia, 0, potential.torque(0, 0), false);
            var bodies = List.of(new TorsionSolver.Body(1, 0, 0, 0, 0),
                new TorsionSolver.Body(inertia, 0, 0, 0, 0).withPotential(potential).withStop(index.pinned ? 2 : 0));
            var solver = TorsionSolver.prepare(bodies, List.of(index.link(0, 1)), speed, new double[]{index.twist});
            var boundary = index.boundary(1, speed[1]);
            var result = boundary == null ? solver.advance(ClockworkTransmission.INTERVAL, ClockworkTransmission.INTERVAL)
                : solver.advanceToEvent(ClockworkTransmission.INTERVAL, ClockworkTransmission.INTERVAL, List.of(boundary));
            require(result.duration() > 0, "capture makes forward progress");
            double bound = (ClockworkTransmission.TORQUE + Math.abs(potential.torque(0, result.angle()[1]))) * result.duration() / inertia;
            require(Math.abs(result.speed()[1] - speed[1]) <= bound + 1e-6, "finite torque bounds acceleration");
            returned += Math.max(0, .5 * (result.speed()[0] * result.speed()[0] - speed[0] * speed[0]));
            speed = result.speed(); position += result.angle()[1];
            speed[1] = index.accept(position, speed[1], inertia, gravity.torque(position, 0), result.twist()[0]);
        }
    }
}
