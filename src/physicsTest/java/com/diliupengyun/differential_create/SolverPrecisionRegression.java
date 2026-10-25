package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Result;

/** Physical work budgets and independence from unrelated bodies, ordering and world scale. */
public final class SolverPrecisionRegression {
    private static int checks;
    private static final double DT = 1.0 / 1200, H = DT / 64, INF = Double.POSITIVE_INFINITY;
    private record Graph(List<Body> bodies, List<Link> links, double[] speed, double[] twist, int press) {
        Result advance(double duration) { return TorsionSolver.advance(bodies, links, speed, twist, duration, H); }
    }
    public static void main(String[] args) { run(); }
    public static void run() {
        for (double inertia : new double[]{.05, .5, 5})
            for (double ratio : new double[]{1, 16, -16, 64}) cycleWork(press(inertia, ratio));
        networkIndependence();
        ordering();
        commonFractureTime();
        replacedShaftFracture();
        shortMasslessTrials();
        masslessFracture();
        freeMasslessMotion();
        prestressedFreeFragment();
        prestressedFreeShaftWork();
        masslessSubsteps();
        masslessEndpointSpeeds();
        masslessSeriesCompliance();
        masslessSlidingContact();
        coasting();
        vanishingContact();
        System.out.println("Solver precision regression: " + checks + " checks passed");
    }

    private static Graph press(double inertia, double ratio) {
        var bodies = new ArrayList<Body>();
        bodies.add(new Body(0, 0, 0, 0, 0)); bodies.add(new Body(0, 0, 0, 0, 0));
        bodies.add(new Body((double) .01f, 0, 0, 8, 0));
        for (int i = 0; i < 7; i++) bodies.add(new Body(inertia, 0, 0, 0, 0));
        bodies.add(new Body(0, 0, 0, 0, 0));
        var links = new ArrayList<Link>();
        links.add(link(0, 1, 1)); links.add(link(1, 2, 1)); links.add(link(0, 10, ratio));
        for (int i = 3; i < 10; i++) links.add(link(i, i + 1, 1));
        double[] speed = new double[bodies.size()];
        for (int i = 0; i < speed.length; i++) speed[i] = i < 3 ? 256 : ratio * 256;
        return new Graph(bodies, links, speed, new double[links.size()], 2);
    }

    private static void cycleWork(Graph graph) {
        double[] speed = graph.speed.clone(), twist = graph.twist.clone();
        double before = energy(graph, speed, twist), travel = 0, damping = 0;
        int steps = 0;
        while (travel < 1.6 - 1e-12 && steps++ < 1000) {
            double duration = H;
            Result result = TorsionSolver.advance(graph.bodies, graph.links, speed, twist, duration, duration);
            if (travel + result.travel()[graph.press] > 1.6) {
                double low = 0, high = duration;
                for (int i = 0; i < 40; i++) {
                    double mid = (low + high) / 2;
                    Result trial = TorsionSolver.advance(graph.bodies, graph.links, speed, twist, mid, mid);
                    if (travel + trial.travel()[graph.press] < 1.6) low = mid; else high = mid;
                }
                duration = high;
                result = TorsionSolver.advance(graph.bodies, graph.links, speed, twist, duration, duration);
            }
            travel += result.travel()[graph.press];
            for (Link link : graph.links) {
                double relative = (link.ratio() * result.angle()[link.a()] - result.angle()[link.b()]) / duration;
                damping += duration * link.damping() * relative * relative;
            }
            speed = result.speed(); twist = result.twist();
        }
        near(1.6, travel, 1e-11, "full press travel");
        double lost = before - energy(graph, speed, twist);
        near(12.8 + damping, lost, 1e-4, "large stored energy must still debit small press work");
    }

    private static void networkIndependence() {
        Graph target = press(.5, 16);
        Result alone = target.advance(DT);
        var bodies = new ArrayList<>(target.bodies);
        int offset = bodies.size();
        bodies.add(new Body(1e12, 0, 0, 0, 0)); bodies.add(new Body(0, 0, 0, 0, 0));
        bodies.add(new Body(.1, 1024, 0, 0, 0, 262144));
        var links = new ArrayList<>(target.links);
        links.add(link(offset, offset + 1, 1));
        double[] speeds = java.util.Arrays.copyOf(target.speed, bodies.size());
        speeds[offset] = speeds[offset + 1] = 1e8; speeds[offset + 2] = 8192;
        Result together = new Graph(bodies, links, speeds, new double[links.size()], 2).advance(DT);
        compare(alone, together, target.speed.length, target.links.size(), 0, "unrelated fast/heavy network");
        Result steamAlone = TorsionSolver.advance(List.of(bodies.get(offset + 2)), List.of(), new double[]{8192},
            new double[0], DT, H);
        near(steamAlone.speed()[0], together.speed()[offset + 2], 0, "isolated nonlinear steam remains independent");

        links.add(new Link(0, offset, 1, 1e7, 128, INF, 0));
        double[] twists = new double[links.size()]; twists[twists.length - 1] = .001;
        Result released = new Graph(bodies, links, speeds, twists, 2).advance(DT);
        compare(alone, released, target.speed.length, target.links.size(), 0, "open clutch does not couple precision");
        near(0, released.twist()[twists.length - 1], 0, "released contact drops its prestress");
        near(.001, twists[twists.length - 1], 0, "trials do not mutate stored contacts");
        Result zero = TorsionSolver.advance(bodies, links, speeds, twists, 0, H);
        near(.001, zero.twist()[twists.length - 1], 0, "zero time changes no state");
    }

    private static void ordering() {
        Graph original = press(.5, 16);
        Result expected = original.advance(DT);
        for (int seed = 0; seed < 4; seed++) {
            var order = new ArrayList<Integer>();
            for (int i = 0; i < original.speed.length; i++) order.add(i);
            Collections.shuffle(order, new Random(seed));
            int[] destination = new int[order.size()];
            var bodies = new ArrayList<Body>();
            double[] speeds = new double[order.size()];
            for (int i = 0; i < order.size(); i++) {
                int source = order.get(i); destination[source] = i;
                bodies.add(original.bodies.get(source)); speeds[i] = original.speed[source];
            }
            var links = new ArrayList<Link>();
            for (Link link : original.links) links.add(new Link(destination[link.a()], destination[link.b()], link.ratio(),
                link.stiffness(), link.damping(), link.strength(), link.friction()));
            Collections.shuffle(links, new Random(seed + 10));
            Graph shuffled = new Graph(bodies, links, speeds, new double[links.size()], destination[original.press]);
            Result actual = shuffled.advance(DT);
            for (int i = 0; i < order.size(); i++) {
                near(expected.speed()[i], actual.speed()[destination[i]], 1e-10, "node/edge ordering preserves speed");
                near(expected.travel()[i], actual.travel()[destination[i]], 1e-12, "ordering preserves machine progress");
            }
            cycleWork(shuffled);
        }
    }

    private static void commonFractureTime() {
        var pair = List.of(new Body(.5, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var weak = List.of(new Link(0, 1, 1, 1e7, 128, 8192, INF));
        Result first = TorsionSolver.advance(pair, weak, new double[]{32, 0}, new double[1], DT, H);
        var bodies = new ArrayList<>(pair); bodies.addAll(pair); bodies.add(new Body(.2, 8, 0, 0, 0));
        var links = List.of(weak.getFirst(), new Link(2, 3, 1, 1e7, 128, 8192, INF));
        Result all = TorsionSolver.advance(bodies, links, new double[]{16, 0, 32, 0, 7}, new double[2], DT, H);
        near(first.duration(), all.duration(), 0, "all components stop at first fracture");
        check(all.broken() == 1, "fracture maps back to the original edge index");
        Result slow = TorsionSolver.advance(pair, weak, new double[]{16, 0}, new double[1], all.duration(), H);
        near(slow.speed()[0], all.speed()[0], 1e-10, "later collision is rewound to shared event time");
        near(first.speed()[0], all.speed()[2], 0, "earliest collision retains its impulse");
        near(7 + 8 * all.duration() / .2, all.speed()[4], 1e-12, "unrelated source advances only to fracture");
        near(7 * all.duration() + 20 * all.duration() * all.duration(), all.angle()[4], 1e-12,
            "unrelated shaft travel shares the event boundary");
    }

    private static void replacedShaftFracture() {
        // Saved at the 2026-09-17 crash: the third shaft has just been replaced.
        // An older contact retains preload from the preceding fracture.
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0),
            new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        for (int sign : new int[]{1, -1}) for (boolean reversed : new boolean[]{false, true})
            for (int substeps : new int[]{64, 63, 128}) {
                var links = new ArrayList<Link>();
                for (int i = 0; i < 4; i++) links.add(new Link(reversed ? i + 1 : i,
                    reversed ? i : i + 1, 1, 1e7, 0, 8192, INF));
                double a = sign * 165.86302232741826, b = sign * 197.51583239706156;
                double[] speed = {a, a, a, 0, b};
                double twistSign = sign * (reversed ? -1 : 1);
                double[] twist = {twistSign * -0.0007733898555839802, twistSign * 1.110223024625144e-18, 0, 0};
                Graph graph = new Graph(bodies, links, speed, twist, 0);
                Result result = TorsionSolver.advance(bodies, links, speed, twist, DT, DT / substeps);
                check(result.broken() >= 0, "replacing an overloaded shaft still causes structural fracture");
                check(result.duration() > 0 && result.duration() < DT, "fracture time is located inside the tick");
                near(.5 * (a + b), .5 * (result.speed()[0] + result.speed()[4]), 1e-10,
                    "short fracture trials preserve rotor momentum");
                near(energy(graph, speed, twist), energy(graph, result.speed(), result.twist()), 1e-9,
                    "fracture trial conserves energy until the failed shaft is removed");
                int edge = result.broken();
                check(Math.max(Math.abs(result.torque()[edge]), 1e7 * Math.abs(result.twist()[edge])) >= 8192,
                    "the reported failed contact actually reaches its strength");
                near(a, speed[0], 0, "fracture trials do not mutate initial rotor state");
                near(twistSign * -0.0007733898555839802, twist[0], 0, "fracture trials do not mutate saved preload");
                for (double value : result.speed()) check(Double.isFinite(value), "fracture endpoint speeds remain finite");
            }
    }

    private static void shortMasslessTrials() {
        // Eliminate a series of massless shafts analytically. All contacts have the
        // same midpoint torque, so summing their compliances gives a two-rotor law.
        for (int shafts : new int[]{1, 3, 7, 15}) for (double common : new double[]{0, 165.9, 8032})
            for (double h : new double[]{H, H / 256, 1e-10, 1e-12}) {
                int last = shafts + 1;
                var bodies = new ArrayList<Body>();
                var links = new ArrayList<Link>();
                double[] speed = new double[last + 1], twist = new double[last];
                double preload = 0, stiffness = 1e7, ja = .5, jb = .15f;
                for (int i = 0; i <= last; i++) {
                    bodies.add(new Body(i == 0 ? ja : i == last ? jb : 0, 0, 0, 0, 0));
                    speed[i] = common + (i == last ? 31.65 : 0);
                    if (i == last) continue;
                    links.add(new Link(i, i + 1, 1, stiffness, 0, INF, INF));
                    twist[i] = i == 0 ? -.000773 : (i % 2 == 0 ? .0002 : -.0002);
                    preload += twist[i];
                }
                double torque = stiffness * (preload + h * (speed[0] - speed[last]) / 2)
                    / (last + h * h * stiffness / 4 * (1 / ja + 1 / jb));
                Graph graph = new Graph(bodies, links, speed, twist, 0);
                Result result = TorsionSolver.advance(bodies, links, speed, twist, h, h);
                near(h, result.duration(), 0, "tiny event trial advances its full interval");
                near(speed[0] - h * torque / ja, result.speed()[0], 1e-10, "series compliance gives input impulse");
                near(speed[last] + h * torque / jb, result.speed()[last], 1e-10, "series compliance gives output impulse");
                for (double actual : result.torque()) near(torque, actual, 1e-7, "every massless shaft balances torque");
                double before = energy(graph, speed, twist);
                near(before, energy(graph, result.speed(), result.twist()), Math.max(1e-9, 32 * Math.ulp(before)),
                    "tiny event trials conserve kinetic plus elastic energy");
            }
    }

    private static void coasting() {
        Graph graph = press(.5, 16);
        var bodies = new ArrayList<>(graph.bodies); bodies.set(2, new Body(.01, 0, 0, 0, 0));
        Result coast = TorsionSolver.advance(bodies, graph.links, graph.speed, graph.twist, 120 * DT, H);
        for (int i = 0; i < bodies.size(); i++) near(graph.speed[i], coast.speed()[i], 0, "unloaded train coasts without drift");

        var rotors = List.of(new Body(.5, 0, 0, 0, 0), new Body(.8, 0, 0, 0, 0));
        var elastic = List.of(new Link(0, 1, 1, PhysicsTuning.TORSIONAL_STIFFNESS,
            PhysicsTuning.TORSIONAL_DAMPING, INF, INF));
        Graph oscillating = new Graph(rotors, elastic, new double[]{4096, 0}, new double[1], 0);
        Result result = oscillating.advance(120 * DT);
        near(energy(oscillating, oscillating.speed, oscillating.twist), energy(oscillating, result.speed(), result.twist()),
            1e-5, "configured contacts conserve kinetic plus elastic energy during long oscillation");
        near(.5 * 4096, .5 * result.speed()[0] + .8 * result.speed()[1], 1e-7, "long motion conserves momentum");
    }

    private static void masslessFracture() {
        var bodies = List.of(new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF), new Link(1, 2, 1, 1e7, 0, 8192, INF));
        for (int sign : new int[]{1, -1}) for (int substeps : new int[]{63, 64, 128}) {
            Graph graph = new Graph(bodies, links,
                new double[]{sign * 8.444249299855451e9, sign * 6.333186974104151e9, sign * 4.222124648352853e9},
                new double[]{sign * 8.192000001635171e-4, sign * 8.192000001635159e-4}, 0);
            double h = DT / substeps;
            var capture = TorsionSolver.prepare(bodies, links, graph.speed, graph.twist);
            // Angle limits force the chronological path instead of its ordinary-integration shortcut.
            for (Result result : List.of(capture.advance(DT, h), capture.advanceToEvent(DT, h,
                List.of(new TorsionSolver.EventLimit(0, INF, 10, 10))))) {
                check(result.broken() >= 0, "prestressed massless fragment still obeys structural strength");
                check(result.duration() > 0 && result.duration() <= 1e-12,
                    "massless overload fractures at the time-resolution boundary");
                check(1e7 * Math.abs(result.twist()[result.broken()]) >= 8192,
                    "reported massless fracture actually reaches structural strength");
                near(energy(graph, graph.speed, graph.twist), energy(graph, result.speed(), result.twist()), 1e-9,
                    "massless fracture speeds cannot spoil the undamped energy balance");
            }
        }
    }

    private static void freeMasslessMotion() {
        var bodies = List.of(new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        for (double q : new double[]{1, -2, .5}) {
            var links = List.of(new Link(0, 1, q, 1e7, 0, 8192, INF));
            for (double common : new double[]{0, 16, -16, 1e12, -1e12}) {
                double[] speed = {common, q * common};
                var capture = TorsionSolver.prepare(bodies, links, speed, new double[1]);
                Result coast = capture.advance(DT, H);
                for (int i = 0; i < speed.length; i++) {
                    near(speed[i], coast.speed()[i], 0, "compatible free massless shafts retain their selected speed");
                    near(DT * speed[i], coast.angle()[i], 0, "free massless motion retains its analytic travel");
                }
                check(coast.broken() < 0, "unstressed free shafts do not fracture");
                if (common != 0) {
                    double budget = Math.abs(speed[1]) * DT / 4;
                    Result event = capture.advanceToEvent(DT, H, List.of(new TorsionSolver.EventLimit(1, budget, INF, INF)));
                    near(DT / 4, event.duration(), 1e-12, "free massless travel reaches the correct event time");
                    near(budget, event.travel()[1], Math.max(1e-12, 16 * Math.ulp(budget)), "free massless event pays its travel budget");
                    near(speed[1], event.speed()[1], 0, "event search preserves the free massless speed");
                }
            }
            // A sub-strength preload still requires a torque-balanced midpoint.
            // Zero midpoint spring torque implies x_end = -x_start and B^T angle = -2*x_start.
            for (double preload : new double[]{1e-4, -1e-4}) {
                var capture = TorsionSolver.prepare(bodies, links, new double[2], new double[]{preload});
                for (Result relaxed : List.of(capture.advance(H, H), capture.advanceToEvent(H, H,
                    List.of(new TorsionSolver.EventLimit(0, INF, 1, 1))))) {
                    check(relaxed.broken() < 0, "sub-strength massless preload does not fracture");
                    near(-preload, relaxed.twist()[0], 1e-15, "massless preload is solved rather than silently frozen");
                    near(-2 * preload, q * relaxed.angle()[0] - relaxed.angle()[1], 1e-15,
                        "massless displacement follows the stored elastic preload");
                }
            }
            Result incompatible = TorsionSolver.advance(bodies, links, new double[]{16, q * 32}, new double[1], H, H);
            near(q * incompatible.speed()[0], incompatible.speed()[1], 1e-10,
                "unloaded zero-inertia endpoints obey the shaft ratio despite inconsistent initial speeds");
        }
    }

    private static void prestressedFreeFragment() {
        // Saved after the 65536-strength counter-rotation fixture fractured.
        // Two dangling shafts retain large preload while the opposite free port
        // carries almost no torque; every equation must still converge.
        for (int sign : new int[]{1, -1}) for (int substeps : new int[]{64, 63, 128}) {
            var bodies = List.of(new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0),
                new Body((double) .15f, sign * -64, 4, 0, 0), new Body(0, 0, 0, 0, 0));
            var links = List.of(new Link(2, 3, 1, 1e7, 0, 65536, INF),
                new Link(0, 1, 1, 1e7, 0, 65536, INF), new Link(1, 2, 1, 1e7, 0, 65536, INF));
            double[] speed = {0, sign * -47.43267723760564, sign * -94.86535447521125, sign * -94.86535447521125};
            double[] twist = {sign * -1.66144540666709e-20, sign * .0065536000297318165, sign * .006553600029731817};
            Graph graph = new Graph(bodies, links, speed, twist, 2);
            Result result = TorsionSolver.advance(bodies, links, speed, twist, DT, DT / substeps);
            check(result.broken() >= 0, "prestressed free fragment reaches its actual strength");
            check(result.duration() > 0 && result.duration() <= 1e-12, "existing overload fractures at the time-resolution boundary");
            double h = result.duration();
            Body rotor = bodies.get(2);
            double delta = (rotor.drive() - rotor.damping() * speed[2]) / (2 * rotor.inertia() / h + rotor.damping());
            double midpoint = speed[2] + delta, expected = speed[2] + 2 * delta;
            for (double actual : result.speed()) near(expected, actual, 1e-11, "free shafts add no spurious rotor impulse");
            for (double actual : result.torque()) near(0, actual, 1e-7, "dangling massless shafts balance torque");
            near(h * midpoint, result.angle()[2], 1e-18, "fracture retains the analytic rotor travel");
            double work = h * midpoint * (rotor.drive() - rotor.damping() * midpoint);
            near(energy(graph, speed, twist) + work, energy(graph, result.speed(), result.twist()), 1e-9,
                "refining a tiny port equation preserves the original physical work budget");
            near(sign * -94.86535447521125, speed[2], 0, "fracture trial preserves its initial rotor state");
            near(sign * .0065536000297318165, twist[1], 0, "fracture trial preserves its saved preload");
        }
    }

    private static void prestressedFreeShaftWork() {
        // The repeated replacement fixture leaves this unbroken free branch.
        // A few ulps of unbalanced preload used to fail its physical work budget
        // even after all twelve smaller-step retries.
        var bodies = List.of(new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, 65536, INF), new Link(1, 2, 1, 1e7, 0, 65536, INF));
        for (int sign : new int[]{1, -1}) for (int substeps : new int[]{64, 63, 128}) {
            double[] speed = {sign * 4000.000000000001, sign * 2000.7323823747254, sign * 1.46476474945004};
            double[] twist = {sign * .005730966779338059, sign * .005730966779338077};
            Graph graph = new Graph(bodies, links, speed, twist, 2);
            Result result = TorsionSolver.advance(bodies, links, speed, twist, DT, DT / substeps);
            check(result.broken() < 0, "free preload below structural strength does not fracture");
            near(DT, result.duration(), 0, "free shaft motion completes the requested physical interval");
            for (double actual : result.speed()) near(speed[2], actual, 1e-11, "prestress adds no impulse to the unloaded flywheel");
            for (double actual : result.torque()) near(0, actual, 1e-7, "free shaft torques remain balanced");
            near(speed[2] * DT, result.angle()[2], 1e-15, "polishing preserves the actual flywheel travel");
            near(energy(graph, speed, twist), energy(graph, result.speed(), result.twist()), 1e-10,
                "polishing cannot spend or create the dangling springs' stored energy");
            near(sign * 1.46476474945004, speed[2], 0, "free branch trial preserves its initial flywheel state");
            near(sign * .005730966779338059, twist[0], 0, "free branch trial preserves its initial preload");
        }
    }

    private static void masslessSubsteps() {
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF));
        Result result = TorsionSolver.advance(bodies, links, new double[]{16, 16}, new double[]{1e-6}, DT, H);
        near(16, result.speed()[0], 1e-9, "massless port adds no impulse");
        near(16, result.speed()[1], 1e-9,
            "tick boundary must not expose algebraic constraint residuals as massless rotor speed");
    }

    private static void masslessEndpointSpeeds() {
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        for (double q : new double[]{1, -2, .5}) {
            var links = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF), new Link(1, 2, q, 1e7, 0, 8192, INF));
            for (int substeps : new int[]{63, 64, 128}) {
                double[] speed = {1024, 1024, q * 1024}, twist = {0, 6e-5};
                Graph graph = new Graph(bodies, links, speed, twist, 0);
                double before = energy(graph, speed, twist);
                for (int tick = 0; tick < 4; tick++) {
                    Result result = TorsionSolver.advance(bodies, links, speed, twist, DT, DT / substeps);
                    speed = result.speed(); twist = result.twist();
                    near(1024, speed[0], 1e-9, "massless preload cannot accelerate the coasting flywheel");
                    near(speed[0], speed[1], 1e-9, "unloaded shaft endpoint speed is independent of substep parity");
                    near(q * speed[1], speed[2], 1e-9, "unloaded gear endpoint speed respects the signed ratio");
                    near(before, energy(graph, speed, twist), 1e-9, "velocity reconstruction does not damp stored energy");
                }
            }
        }
        var accelerating = List.of(new Body(.5, 3, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        var link = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF));
        Result result = TorsionSolver.advance(accelerating, link, new double[]{16, 16}, new double[1], DT, H);
        near(16 + 6 * DT, result.speed()[0], 1e-10, "unloaded source has its physical endpoint acceleration");
        near(result.speed()[0], result.speed()[1], 1e-10, "shaft reports endpoint speed rather than midpoint or tick average");
        near(16 * DT + 3 * DT * DT, result.angle()[1], 1e-12, "actual integrated shaft motion is retained");
    }

    private static void masslessSeriesCompliance() {
        // Eliminate a massless junction analytically: two springs in series must behave
        // exactly like their equivalent spring between the same two real flywheels.
        var rotors = List.of(new Body(.5, 0, 0, 0, 0), new Body(.8, 0, 0, 0, 0));
        var bodies = List.of(rotors.get(0), new Body(0, 0, 0, 0, 0), rotors.get(1));
        double ka = 1e7, kb = 3e7;
        for (double qa : new double[]{1, -2}) for (double qb : new double[]{1, -.5, 2}) {
            var links = List.of(new Link(0, 1, qa, ka, 0, INF, INF), new Link(1, 2, qb, kb, 0, INF, INF));
            var equivalent = List.of(new Link(0, 1, qa * qb, ka * kb / (ka + qb * qb * kb), 0, INF, INF));
            double[] speed = {32, qa * ka * 32 / (ka + qb * qb * kb), 0}, twist = new double[2];
            double[] directSpeed = {32, 0}, directTwist = new double[1];
            Graph graph = new Graph(bodies, links, speed, twist, 0);
            double energy = energy(graph, speed, twist);
            for (int tick = 0; tick < 8; tick++) {
                Result result = TorsionSolver.advance(bodies, links, speed, twist, DT, H);
                Result direct = TorsionSolver.advance(rotors, equivalent, directSpeed, directTwist, DT, H);
                speed = result.speed(); twist = result.twist();
                directSpeed = direct.speed(); directTwist = direct.twist();
                near(directSpeed[0], speed[0], 1e-9, "junction recovery preserves real driver oscillation");
                near(directSpeed[1], speed[2], 1e-9, "junction recovery preserves real driven oscillation");
                near((qa * ka * speed[0] + qb * kb * speed[2]) / (ka + qb * qb * kb), speed[1], 1e-9,
                    "massless endpoint follows both elastic branches, not a forced common network speed");
                near(energy, energy(graph, speed, twist), 1e-9, "massless recovery preserves undamped system energy");
            }
        }
    }

    private static void masslessSlidingContact() {
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF), new Link(1, 2, 1, 1e7, 0, 8192, 32));
        Result result = TorsionSolver.advance(bodies, links, new double[]{16, 16, 0}, new double[]{32 / 1e7, 32 / 1e7}, DT, H);
        check(result.slipping()[1], "sliding clutch remains a finite friction contact");
        near(16 - 32 * DT / .5, result.speed()[0], 1e-9, "sliding applies the specified finite impulse");
        near(result.speed()[0], result.speed()[1], 1e-9, "steady slipping torque leaves the input shaft synchronized");
        check(result.speed()[1] - result.speed()[2] > 15, "endpoint recovery cannot lock a slipping clutch");
    }

    private static void compare(Result expected, Result actual, int n, int m, double tolerance, String label) {
        for (int i = 0; i < n; i++) {
            near(expected.speed()[i], actual.speed()[i], tolerance, label + " speed");
            near(expected.angle()[i], actual.angle()[i], tolerance, label + " angle");
            near(expected.travel()[i], actual.travel()[i], tolerance, label + " travel");
        }
        for (int e = 0; e < m; e++) {
            near(expected.twist()[e], actual.twist()[e], tolerance, label + " elastic state");
            near(expected.torque()[e], actual.torque()[e], tolerance, label + " torque");
        }
    }
    private static void vanishingContact() {
        var bodies = List.of(new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0));
        var links = List.of(link(0, 1, 1), link(1, 2, -1));
        Result result = TorsionSolver.advance(bodies, links, new double[3], new double[]{1e-168, -1e-169}, DT, H);
        for (double speed : result.speed()) near(0, speed, 1e-150, "vanishing contact force cannot prevent convergence");
    }
    private static Link link(int a, int b, double ratio) {
        return new Link(a, b, ratio, 2e7 / (1 + ratio * ratio), 256 / (1 + ratio * ratio), INF, INF);
    }
    private static double energy(Graph graph, double[] speed, double[] twist) {
        double sum = 0;
        for (int i = 0; i < speed.length; i++) sum += .5 * graph.bodies.get(i).inertia() * speed[i] * speed[i];
        for (int e = 0; e < twist.length; e++) sum += .5 * graph.links.get(e).stiffness() * twist[e] * twist[e];
        return sum;
    }
    private static void near(double expected, double actual, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": " + expected + " != " + actual);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
