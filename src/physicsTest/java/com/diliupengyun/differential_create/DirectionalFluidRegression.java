package com.diliupengyun.differential_create;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.DirectionalFluidJunctions;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.kinetics.PipeFilling;
import com.diliupengyun.differential_create.kinetics.ReducedFluidGraph;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Analytical free-flight and pressure-driven expectations, without Minecraft. */
public final class DirectionalFluidRegression {
    private static final IdealFluidSolver.Material WATER = new IdealFluidSolver.Material(1000, false);
    private static int checks;
    public static void run() {
        ballisticCross(); releasedSlugs(); dryInletRelease(); pressureOpensBranches(); wallTurnsWater(); filledNetwork();
        System.out.println("Directional fluid regression: " + checks + " checks passed");
    }
    private static void ballisticCross() {
        for (int[] directions : new int[][]{{4, 5, 2, 3}, {2, 3, 0, 1}, {5, 4, 3, 2}}) {
            var edges = crossEdges(6); var tubes = crossTubes(6);
            var junctions = new DirectionalFluidJunctions(5, edges, tubes, directions, new int[]{-1, -1, -1, -1});
            var contents = new PipeContents[]{PipeContents.of(.05, .1), PipeContents.EMPTY, PipeContents.EMPTY, PipeContents.EMPTY};
            double[] flow = {-16, 16, 0, 0};
            double volume = volume(tubes, contents), energy = energy(tubes, contents, flow);
            double remaining = RotationMath.TICK_MINUTES * 24;
            int steps = 0;
            while (remaining > 1e-15) {
                var step = move(junctions, contents, flow, Math.min(IdealFluidSolver.MAX_STEP, remaining));
                contents = step.move.contents(); flow = step.motion.flow(); remaining -= step.minutes;
                near(volume, volume(tubes, contents), 2e-9, "a free slug crossing a dry junction conserves actual liquid");
                near(energy, energy(tubes, contents, flow), 2e-11, "dry lanes neither damp nor energize coasting liquid");
                near(0, contents[2].fraction() + contents[3].fraction(), 0, "no transverse force means no sideways liquid");
                if (++steps > 2000) throw new AssertionError("ballistic face events must advance time");
            }
            near(volume, contents[1].fraction() * 600, 2e-9, "the complete slug continues into the opposite arm");
        }
    }
    private record Step(IdealFluidSolver.Trial motion, PipeFilling.Move move, double minutes) {}
    private static Step move(DirectionalFluidJunctions junctions, PipeContents[] contents, double[] initial, double minutes) {
        boolean[] boundary = new boolean[junctions.nodes()], enabled = new boolean[initial.length];
        for (int n = 1; n <= 4; n++) boundary[n] = true;
        Arrays.fill(enabled, true);
        for (int retry = 0; retry < 32; retry++) {
            var layout = junctions.select(contents);
            double[] mass = new double[initial.length];
            for (int e = 0; e < mass.length; e++) mass[e] = WATER.inertia(6) * Math.max(1e-12, contents[e].fraction());
            var graph = layout.graph(boundary, enabled);
            double[] origin = graph.sample(initial, mass, new double[mass.length], new double[boundary.length], minutes).flow();
            double[] guess = origin.clone();
            IdealFluidSolver.Trial motion = null; PipeFilling.Move move = null;
            boolean converged = false;
            for (int iteration = 0; iteration < 100; iteration++) {
                var prediction = PipeFilling.predict(layout.tubes(), junctions.nodes(), contents, origin, guess,
                    layout.boundary(boundary), new double[boundary.length], WATER, minutes);
                double[] meanMass = prediction.inertia().clone();
                for (int e = 0; e < mass.length; e++) meanMass[e] = Math.max(meanMass[e], WATER.inertia(6) * 1e-12);
                motion = graph.sample(origin, meanMass, prediction.correction(), new double[boundary.length], minutes);
                var next = junctions.releaseDryInlets(layout, contents, motion.midpoint());
                if (next == layout) next = junctions.pressurise(layout, contents, motion.pressure(), motion.midpoint(), WATER);
                if (next != layout) {
                    layout = next; graph = layout.graph(boundary, enabled);
                    origin = graph.sample(initial, mass, new double[mass.length], new double[boundary.length], minutes).flow();
                    guess = origin.clone(); continue;
                }
                double difference = 0;
                for (int e = 0; e < guess.length; e++) {
                    difference = Math.max(difference, Math.abs(motion.midpoint()[e] - guess[e]));
                    guess[e] = .5 * (guess[e] + motion.midpoint()[e]);
                }
                if (difference < 1e-11) { converged = true; break; }
            }
            if (!converged) throw new AssertionError("ballistic midpoint force must converge");
            move = PipeFilling.sample(layout.tubes(), junctions.nodes(), contents, origin, motion.midpoint(),
                layout.boundary(boundary), new double[boundary.length], WATER, minutes);
            if (move.allowedMinutes() < minutes * (1 - 1e-12)) { minutes = move.allowedMinutes(); continue; }
            for (int n = 0; n < boundary.length; n++) if (n == 0 || n > 4)
                near(0, move.waterOutward()[n], 1e-9, "a numerical free face never deletes water at a junction");
            return new Step(motion, move, minutes);
        }
        throw new AssertionError("ballistic face time must be resolvable");
    }
    private static void releasedSlugs() {
        var edges = crossEdges(1); var tubes = crossTubes(1);
        var junctions = new DirectionalFluidJunctions(5, edges, tubes, new int[]{4, 5, 2, 3}, new int[]{-1, -1, -1, -1});
        var contents = new PipeContents[]{PipeContents.of(.2, .4), PipeContents.of(.3, .5), PipeContents.of(.1, .2), PipeContents.EMPTY};
        double[] flow = {-7, 3, 4, 0}, mass = new double[4];
        for (int e = 0; e < 4; e++) mass[e] = WATER.inertia(1) * Math.max(1e-12, contents[e].fraction());
        boolean[] enabled = {true, true, true, true}, boundary = {false, true, true, true, true};
        var motion = junctions.select(contents).graph(boundary, enabled).sample(flow, mass, new double[4], new double[junctions.nodes()], 1e-6);
        for (int e = 0; e < 4; e++) near(flow[e], motion.flow()[e], 0, "separated liquid retains independent velocity");
        near(0, motion.projectionLoss(), 0, "dry contact release does not project away kinetic energy");
    }
    private static void dryInletRelease() {
        var edges = crossEdges(1); var tubes = crossTubes(1);
        var junctions = new DirectionalFluidJunctions(5, edges, tubes, new int[]{4, 5, 2, 3}, new int[]{-1, -1, -1, -1});
        for (int dry = 0; dry < 4; dry++) {
            var contents = new PipeContents[]{PipeContents.FULL, PipeContents.FULL, PipeContents.FULL, PipeContents.FULL};
            contents[dry] = PipeContents.of(.4, .8);
            var contact = junctions.select(contents);
            double[] guess = {1, 1, 1, 1}; guess[dry] = -3;
            var released = junctions.releaseDryInlets(contact, contents, guess);
            if (released == contact) throw new AssertionError("a dry incoming gap must release its junction pressure constraint");
            boolean[] boundary = {false, true, true, true, true}, enabled = {true, true, true, true};
            double[] initial = new double[4], mass = new double[4]; initial[dry] = -7;
            for (int i = 0; i < 4; i++) mass[i] = WATER.inertia(1) * contents[i].fraction();
            var motion = released.graph(boundary, enabled).sample(initial, mass, new double[4], new double[junctions.nodes()], 1e-6);
            near(-7, motion.flow()[dry], 0, "liquid beyond a dry mouth keeps its independent inward motion");
            for (int i = 0; i < 4; i++) if (i != dry) near(0, motion.flow()[i], 0, "a dry gap exerts no pressure or fake inflow on wetted arms");
            near(0, motion.projectionLoss(), 0, "releasing a dry inlet loses no liquid kinetic energy");
        }
    }
    private static void pressureOpensBranches() {
        var edges = crossEdges(1); var tubes = crossTubes(1);
        var junctions = new DirectionalFluidJunctions(5, edges, tubes, new int[]{4, 5, 2, 3}, new int[]{-1, -1, -1, -1});
        var contents = new PipeContents[]{PipeContents.FULL, PipeContents.FULL, PipeContents.EMPTY, PipeContents.EMPTY};
        var layout = junctions.select(contents);
        boolean[] boundary = {false, true, true, true, true}, enabled = {true, true, true, true};
        double[] pressure = new double[junctions.nodes()]; pressure[1] = WATER.gravityForce(4);
        double[] mass = {WATER.inertia(1), WATER.inertia(1), WATER.inertia(1) * 1e-12, WATER.inertia(1) * 1e-12};
        var axial = layout.graph(boundary, enabled).sample(new double[4], mass, new double[4], pressure, 1e-6);
        var contact = junctions.pressurise(layout, contents, axial.pressure(), axial.midpoint(), WATER);
        if (contact == layout || contact.joined() != 1) throw new AssertionError("positive actual liquid pressure opens the dry lateral faces");
        var motion = contact.graph(boundary, enabled).sample(new double[4], mass, new double[4], pressure, 1e-6);
        if (!(motion.flow()[2] > 0 && motion.flow()[3] > 0)) throw new AssertionError("pressure actually accelerates both lateral branches");
        near(0, Arrays.stream(motion.flow()).sum(), 1e-10, "opened contact preserves volume continuity");
        near(motion.energyChange(), motion.forceWork() + motion.boundaryWork(), 1e-12, "opening pressure supplies the fluid energy");
    }
    private static void wallTurnsWater() {
        var edges = crossEdges(1).subList(0, 3); var tubes = crossTubes(1).subList(0, 3);
        var junctions = new DirectionalFluidJunctions(4, edges, tubes, new int[]{0, 4, 5}, new int[]{-1, -1, -1});
        var contents = new PipeContents[]{PipeContents.of(0, .2), PipeContents.EMPTY, PipeContents.EMPTY};
        if (junctions.select(contents).joined() != 1) throw new AssertionError("a wall supplies the reaction when no straight exit exists");
        var dry = new PipeContents[]{PipeContents.of(.2, .4), PipeContents.EMPTY, PipeContents.EMPTY};
        if (junctions.select(dry).joined() != 0) throw new AssertionError("a distant slug has not collided with the junction wall yet");
        checks += 2;
    }
    private static void filledNetwork() {
        var edges = crossEdges(1); var tubes = crossTubes(1);
        var junctions = new DirectionalFluidJunctions(5, edges, tubes, new int[]{4, 5, 2, 3}, new int[]{-1, -1, -1, -1});
        var contents = new PipeContents[]{PipeContents.FULL, PipeContents.FULL, PipeContents.FULL, PipeContents.FULL};
        boolean[] boundary = {false, true, true, true, true}, enabled = {true, true, true, true};
        var legacy = new ReducedFluidGraph(5, edges, boundary, enabled);
        var layout = junctions.select(contents);
        if (layout != junctions.select(contents)) throw new AssertionError("unchanged liquid contact reuses the junction layout");
        Random random = new Random(0xC20552026L);
        for (int sample = 0; sample < 200; sample++) {
            double[] force = new double[4], mass = new double[4], pressure = new double[junctions.nodes()];
            for (int e = 0; e < 4; e++) { force[e] = random.nextDouble() * 20 - 10; mass[e] = WATER.inertia(1 + random.nextDouble() * 10); }
            for (int n = 1; n < 5; n++) pressure[n] = random.nextDouble() * 20 - 10;
            var reference = legacy.sample(new double[4], mass, force, Arrays.copyOf(pressure, 5), 1e-5);
            var actual = layout.graph(boundary, enabled).sample(new double[4], mass, force, pressure, 1e-5);
            for (int e = 0; e < 4; e++) near(reference.flow()[e], actual.flow()[e], 1e-8, "filled junction retains all pump and outlet pressure interactions");
            near(actual.energyChange(), actual.forceWork() + actual.boundaryWork(), 1e-10, "arbitrary combined forces conserve work");
        }
    }
    private static List<IdealFluidSolver.Edge> crossEdges(double length) {
        return List.of(new IdealFluidSolver.Edge(0, 1, WATER.inertia(length)), new IdealFluidSolver.Edge(0, 2, WATER.inertia(length)),
            new IdealFluidSolver.Edge(0, 3, WATER.inertia(length)), new IdealFluidSolver.Edge(0, 4, WATER.inertia(length)));
    }
    private static List<PipeFilling.Tube> crossTubes(double length) {
        return List.of(new PipeFilling.Tube(0, 1, length, 0, 0), new PipeFilling.Tube(0, 2, length, 0, 0),
            new PipeFilling.Tube(0, 3, length, 0, 0), new PipeFilling.Tube(0, 4, length, 0, 0));
    }
    private static double volume(List<PipeFilling.Tube> tubes, PipeContents[] contents) {
        double result = 0; for (int e = 0; e < tubes.size(); e++) result += tubes.get(e).length() * PipeFilling.MB_PER_METRE * contents[e].fraction();
        return result;
    }
    private static double energy(List<PipeFilling.Tube> tubes, PipeContents[] contents, double[] flow) {
        double result = 0; for (int e = 0; e < tubes.size(); e++) result += .5 * WATER.inertia(tubes.get(e).length()) * contents[e].fraction() * flow[e] * flow[e];
        return result;
    }
    private static void near(double expected, double actual, double tolerance, String reason) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(reason + ": " + expected + " / " + actual);
    }
}
