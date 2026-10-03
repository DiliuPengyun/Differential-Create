package com.diliupengyun.differential_create;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.FluidBudget;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.kinetics.PipeFilling;
import com.diliupengyun.differential_create.kinetics.ReducedFluidGraph;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Physical ownership, moving mass and gravity expectations independent of Minecraft. */
public final class PipeFillingRegression {
    private static final IdealFluidSolver.Material WATER = new IdealFluidSolver.Material(1000, false);
    private static int checks;
    public static void run() {
        intervals(); massAndEnergy(); branches(); residualAtMouth(); sharedInventory(); pumpTangent(); reduction();
        System.out.println("Pipe filling regression: " + checks + " checks passed");
    }
    private static void intervals() {
        var water = PipeContents.of(.1, .4, .6, .9);
        near(.6, water.fraction(), 1e-15, "separate slugs own only their liquid");
        near(water.fraction(), water.reverse().fraction(), 1e-15, "reversing a pipe preserves inventory");
        var joined = PipeContents.join(water.slice(0, .5), water.slice(.5, 1), .5);
        near(water.fraction(), joined.fraction(), 1e-15, "splitting between the two existing owners preserves inventory");
        near(water.moment(), joined.moment(), 1e-15, "ownership split preserves gravitational position");
        near(.7, water.move(.1, 1).fraction(), 1e-15, "inflow extends a moving front");
        near(.5, PipeContents.FULL.move(.5, 0).fraction(), 1e-15, "empty source admits no free liquid");
        near(.3, PipeContents.of(0, .4).move(-.1, 0).fraction(), 1e-15, "a water column can retreat");
        near(.4, PipeContents.of(0, .4).move(0, 0).fraction(), 0, "stopped water remains in its pipe");
        near(.4, PipeContents.of(0, .6).interpolate(PipeContents.of(0, .2), .5).fraction(), 1e-15, "front animation interpolates actual owned water");
    }
    private static void massAndEnergy() {
        Random random = new Random(0xF1112026);
        for (int sample = 0; sample < 2000; sample++) {
            double length = .5 + random.nextDouble() * 30, fromY = random.nextDouble() * 100 - 50;
            double toY = fromY + (random.nextDouble() * 2 - 1) * length;
            var tube = new PipeFilling.Tube(0, 1, length, fromY, toY);
            var old = sample % 4 == 0 ? PipeContents.EMPTY : sample % 4 == 1 ? PipeContents.FULL
                : sample % 4 == 2 ? PipeContents.of(0, .6) : PipeContents.of(.2, .8);
            double q = (random.nextDouble() * 2 - 1) * 30, q0 = q * .7;
            if (old == PipeContents.EMPTY) { q = Math.abs(q); q0 = 0; }
            double dt = RotationMath.TICK_MINUTES / 64;
            var move = PipeFilling.sample(List.of(tube), 2, new PipeContents[]{old}, new double[]{q0}, new double[]{q},
                new boolean[]{true, true}, new double[]{1, 0}, WATER, dt);
            double capacity = length * PipeFilling.MB_PER_METRE;
            double volume = (move.contents()[0].fraction() - old.fraction()) * capacity;
            near(volume, dt * (move.waterOutward()[0] + move.waterOutward()[1]) / RotationMath.TICK_MINUTES,
                1e-10, "pipe inventory equals actual liquid exchanged, including reverse flow");
            if (move.inertia()[0] == 0) continue;
            double q1 = 2 * q - q0;
            double force = move.inertia()[0] * (q1 - q0) / dt - move.correction()[0];
            var graph = new ReducedFluidGraph(2, List.of(new IdealFluidSolver.Edge(0, 1, WATER.inertia(length))),
                new boolean[]{true, true}, new boolean[]{true});
            var trial = graph.sample(new double[]{q0}, move.inertia(), new double[]{force + move.correction()[0]}, new double[2], dt);
            near(q1, trial.flow()[0], 1e-10, "moving-mass discrete impulse");
            double energy = .5 * WATER.inertia(length) * (move.contents()[0].fraction() * q1 * q1 - old.fraction() * q0 * q0);
            energy += capacity * RotationMath.TICK_MINUTES * (WATER.gravityForce(fromY) * (move.contents()[0].fraction() - old.fraction())
                + WATER.gravityForce(toY - fromY) * (move.contents()[0].moment() - old.moment()));
            double work = dt * (force * q + WATER.gravityForce(fromY) * move.waterOutward()[0] + WATER.gravityForce(toY) * move.waterOutward()[1]);
            near(work, energy, 1e-10, "admitted mass, stored kinetic/gravity energy and pressure work balance");
        }
        var material = new IdealFluidSolver.Material(1000, true);
        var tube = new PipeFilling.Tube(0, 1, 38, 0, 38);
        var water = PipeContents.of(0, .5);
        var gas = PipeFilling.sample(List.of(tube), 2, new PipeContents[]{water}, new double[]{4}, new double[]{4},
            new boolean[]{true, true}, new double[]{1, 0}, material, RotationMath.TICK_MINUTES / 64);
        var level = PipeFilling.sample(List.of(new PipeFilling.Tube(0, 1, 38, 0, 0)), 2, new PipeContents[]{water}, new double[]{4}, new double[]{4},
            new boolean[]{true, true}, new double[]{1, 0}, WATER, RotationMath.TICK_MINUTES / 64);
        near(level.correction()[0], gas.correction()[0], 0, "gas switch removes gravity, retaining real mass and filling");
    }
    private static void branches() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 1), new PipeFilling.Tube(1, 3, 1, 0, -1));
        double dt = RotationMath.TICK_MINUTES / 8;
        var trial = PipeFilling.sample(tubes, 4, new PipeContents[]{PipeContents.FULL, PipeContents.EMPTY, PipeContents.EMPTY},
            new double[]{30, 10, 20}, new double[]{30, 10, 20}, new boolean[]{true, false, true, true}, new double[]{1, 0, 0, 0}, WATER, dt);
        near(1.25, trial.contents()[1].fraction() * 100, 1e-12, "junction sends actual liquid into first branch");
        near(2.5, trial.contents()[2].fraction() * 100, 1e-12, "second branch receives its conserved flow share");
        near(0, trial.waterOutward()[1], 1e-12, "junction creates no liquid storage");
        near(0, trial.waterOutward()[2] + trial.waterOutward()[3], 0, "dry outlets do not teleport liquid to destinations");
    }
    private static void residualAtMouth() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 1));
        for (double tiny : new double[]{4.785e-13, 1e-14, 1e-16}) {
            var old = new PipeContents[]{PipeContents.of(1 - tiny, 1), PipeContents.EMPTY};
            double dt = RotationMath.TICK_MINUTES / 8;
            var move = PipeFilling.sample(tubes, 3, old, new double[]{32, 32}, new double[]{32, 32},
                new boolean[]{true, false, true}, new double[3], WATER, dt);
            double before = old[0].fraction() * 50;
            near(before, move.contents()[0].fraction() * 50 + move.contents()[1].fraction() * 100,
                1e-12, "a thin exhausted-source remainder cannot become a full step of liquid");
            near(0, move.waterOutward()[1], 1e-12, "junction routes only the thin remainder it actually receives");
            near(0, move.waterOutward()[0] + move.waterOutward()[2], 0, "dry external mouths emit no new water");
        }
    }
    private static void reduction() {
        Random random = new Random(726);
        for (int n : new int[]{2, 8, 40}) {
            var edges = new java.util.ArrayList<IdealFluidSolver.Edge>();
            for (int i = 0; i < n; i++) edges.add(new IdealFluidSolver.Edge(i, i + 1, .001 + random.nextDouble() * .02));
            boolean[] boundary = new boolean[n + 1], active = new boolean[n]; Arrays.fill(active, true);
            boundary[0] = boundary[n] = true;
            var reduced = new ReducedFluidGraph(n + 1, edges, boundary, active);
            near(1, reduced.channels(), 0, "long pipe has one pressure channel");
            for (int sample = 0; sample < 20; sample++) {
                double[] mass = edges.stream().mapToDouble(IdealFluidSolver.Edge::inertia).toArray(), flow = new double[n], force = new double[n];
                for (int e = 0; e < n; e++) { flow[e] = random.nextDouble() * 40 - 20; force[e] = random.nextDouble() * 40 - 20; }
                double[] pressure = new double[n + 1]; pressure[0] = 12; pressure[n] = -7;
                var old = new IdealFluidSolver.Graph(n + 1, edges, boundary, active).sample(flow, force, pressure, .0001);
                var next = reduced.sample(flow, mass, force, pressure, .0001);
                for (int e = 0; e < n; e++) near(old.flow()[e], next.flow()[e], 1e-10, "exact pipe elimination matches unreduced pressure constraints");
                near(old.projectionLoss(), next.projectionLoss(), 1e-10, "connection impulse conserves the same momentum and accounts for the same loss");
            }
        }
        var edges = List.of(new IdealFluidSolver.Edge(0, 1, .01), new IdealFluidSolver.Edge(1, 2, .02), new IdealFluidSolver.Edge(2, 0, .03));
        var ring = new ReducedFluidGraph(3, edges, new boolean[3], new boolean[]{true, true, true});
        var trial = ring.sample(new double[]{9, 9, 9}, new double[]{.01, .02, .03}, new double[3], new double[3], 1);
        for (double q : trial.flow()) near(9, q, 0, "closed ideal ring does not acquire friction from reduction");
        var blocked = new ReducedFluidGraph(2, List.of(new IdealFluidSolver.Edge(0, 1, .01)), new boolean[]{true, false}, new boolean[]{true});
        var stop = blocked.sample(new double[]{5}, new double[]{.01}, new double[]{10}, new double[2], .001);
        near(0, stop.flow()[0], 0, "filled sealed pipe enforces exact zero motion");
        near(.125, stop.projectionLoss(), 1e-15, "sealed-mouth impulse records removed kinetic energy");
    }
    private static void sharedInventory() {
        near(.5, FluidBudget.limit(new double[]{1, 1}, new double[]{0, 0}, 1, 1), 1e-15,
            "two ports share one finite stock instead of each promising its whole amount");
        near(.6, FluidBudget.limit(new double[]{1, 1}, new double[]{.2, 0}, 1, 1), 1e-15,
            "owned fractional supply reduces the common extraction requirement");
        near(.2, FluidBudget.limit(new double[]{1, 3}, new double[]{.6, 0}, .6, 1), 1e-15,
            "one port's existing ownership is not counted twice by another port");
        near(.8, FluidBudget.limit(new double[]{2}, new double[]{.6}, 1, 1), 0,
            "single source hits its exact volume event");
        near(1, FluidBudget.limit(new double[]{0, 0}, new double[]{0, 0}, 0, 1), 0,
            "dry mouths require no fictional extraction");
    }
    private static void pumpTangent() {
        double dt = RotationMath.TICK_MINUTES / 8, rpm = 64, q0 = 9;
        for (double fraction : new double[]{1, 1e-4, 1e-10, 1e-14}) {
            double mass = WATER.inertia(1) * fraction;
            double slope = IdealFluidSolver.pumpSlope(WATER, rpm, 12);
            double expected = (mass * q0 + .5 * dt * IdealFluidSolver.coupling(WATER, rpm, 0) * rpm) / (mass - .5 * dt * slope);
            for (double guess : new double[]{1, 12, 40}) {
                var graph = new ReducedFluidGraph(2, List.of(new IdealFluidSolver.Edge(0, 1, mass)),
                    new boolean[]{true, true}, new boolean[]{true});
                var trial = graph.sample(new double[]{q0}, new double[]{mass - .5 * dt * slope},
                    new double[]{IdealFluidSolver.coupling(WATER, rpm, guess) * rpm + slope * (q0 - guess)}, new double[2], dt);
                double midpoint = trial.midpoint()[0];
                near(expected, midpoint, 1e-12, "pump tangent solves vanishing inertia without reducing the physical time step");
                double energy = .5 * mass * (trial.flow()[0] - q0) * (trial.flow()[0] + q0);
                double work = dt * IdealFluidSolver.coupling(WATER, rpm, midpoint) * rpm * midpoint;
                near(energy, work, 1e-12, "implicit pump tangent adds no physical inertia or dissipated work");
            }
        }
    }
    private static void near(double expected, double actual, double tolerance, String label) {
        checks++; if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + ": expected=" + expected + "; actual=" + actual);
    }
}
