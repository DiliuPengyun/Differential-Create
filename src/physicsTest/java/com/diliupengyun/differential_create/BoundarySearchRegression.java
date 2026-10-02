package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.ShaftClock;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Result;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.EventLimit;

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
        chronologicalEvents();
        roundingBoundaries();
        typedFractureClock();
        directionWakeups();
        absoluteOnlyFullProof();
        System.out.println("Boundary search regression: " + checks + " checks passed");
    }

    private static void absoluteOnlyFullProof() {
        var bodies = List.of(new Body(.15, 64, 4, 0, 0), new Body(0, 0, 0, 0, 0),
            new Body(.5, 0, 0, 8, .001), new Body(.2, -8, 0, 0, 0));
        var links = List.of(new Link(0, 1, -2, 1e7, 0, INF, INF), new Link(1, 2, -.5, 1e7, 0, INF, INF));
        double[] speed = {128, -256, 128, 7}, twist = {0, 0};
        Result expected = TorsionSolver.advance(bodies, links, speed, twist, DT, H);
        Result noEvent = TorsionSolver.prepare(bodies, links, speed, twist).advanceToEvent(DT, H,
            List.of(new EventLimit(1, 4 * expected.travel()[1], INF, INF), new EventLimit(3, 1, INF, INF)));
        same(expected, noEvent);
        // Zero initial speed makes the cheap scheduling estimate choose a full
        // trial. Its actual motion must reject that shortcut when a source moves
        // far enough to consume the budget within the requested horizon.
        double budget = 1e-5, drive = 1e6;
        Result startup = TorsionSolver.prepare(List.of(new Body(1, drive, 0, 0, 0)), List.of(),
            new double[1], new double[0]).advanceToEvent(DT, H, List.of(new EventLimit(0, budget, INF, INF)));
        double exactTime = Math.sqrt(2 * budget / drive);
        check(Math.abs(startup.duration() - exactTime) <= 1e-12 && Math.abs(startup.travel()[0] - budget) <= DT * 1e-8,
            "an inaccurate initial-speed estimate cannot skip a real acceleration event");
        check(Math.abs(startup.speed()[0] - drive * startup.duration()) <= 1e-12,
            "the acceleration boundary still commits the actual physical endpoint");
        var fractureBodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0), new Body(.2, 8, 0, 0, 0));
        var fractureLinks = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF));
        double[] fractureSpeed = {16, 0, 7};
        expected = TorsionSolver.advance(fractureBodies, fractureLinks, fractureSpeed, new double[1], DT, H);
        Result fracture = TorsionSolver.prepare(fractureBodies, fractureLinks, fractureSpeed, new double[1])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, 1, INF, INF), new EventLimit(2, 1, INF, INF)));
        check(fracture.broken() == 0 && fracture.duration() < DT, "the full no-event proof still stops at a preceding fracture");
        same(expected, fracture);
    }

    private static void directionWakeups() {
        for (int direction : new int[]{1, -1}) {
            double initial = -direction * 1e-6, acceleration = direction * 640;
            var body = List.of(new Body(1, acceleration, 0, 0, 0));
            Result wake = TorsionSolver.prepare(body, List.of(), new double[]{initial}, new double[0])
                .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, INF, direction)));
            double expected = (1e-6 + 1.5e-9) / 640;
            check(wake.duration() > 0 && Math.abs(wake.duration() - expected) <= 1e-14,
                "source reversal wakes the permitted direction on its first visible speed");
            check(direction * wake.speed()[0] >= 1e-9 && direction * wake.speed()[0] < 2e-9,
                "direction wake publishes a nonzero actual endpoint speed");
            check(Math.abs(wake.speed()[0] - (initial + acceleration * wake.duration())) <= 1e-15,
                "direction wake state follows the independent constant-acceleration solution");
        }
        var still = List.of(new Body(1, 0, 0, 0, 0));
        Result result = TorsionSolver.prepare(still, List.of(), new double[1], new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, INF, 1)));
        check(result.duration() == DT && result.speed()[0] == 0, "an unpowered stationary shaft does not manufacture a wakeup");
        result = TorsionSolver.prepare(List.of(new Body(1, 1e-12, 0, 0, 0)), List.of(), new double[1], new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, INF, 1)));
        check(result.duration() == DT && result.speed()[0] < 1e-9,
            "a source weaker than the visible speed resolution keeps the full interval");
        result = TorsionSolver.prepare(List.of(new Body(1, 1e12, 0, 0, 0)), List.of(), new double[1], new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, INF, 1)));
        check(result.duration() > 0 && result.duration() < 1e-14 && result.speed()[0] >= 1e-9,
            "large acceleration does not force a coarse minimum wake interval");
        result = TorsionSolver.prepare(still, List.of(), new double[]{1}, new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, INF, 1)));
        check(result.duration() == DT, "a shaft already in the permitted direction needs no zero-time wakeup");
        var bodies = List.of(new Body(.15, 64, 4, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, INF, INF), new Link(1, 2, 1, 1e7, 0, INF, INF));
        result = TorsionSolver.prepare(bodies, links, new double[]{-1e-6, -1e-6, -1e-6}, new double[2])
            .advanceToEvent(DT, H, List.of(new EventLimit(1, INF, INF, INF, 1)));
        check(result.duration() > 0 && result.duration() < H && result.speed()[1] >= 1e-9,
            "a massless work output wakes using recovered physical endpoint speed");
        result = TorsionSolver.prepare(List.of(new Body(1, 640, 0, 0, 0)), List.of(), new double[]{-1e-6}, new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, 1e-16, 1)));
        check(result.duration() > 0 && result.speed()[0] < 0 && Math.abs(result.angle()[0] + 1e-16) <= DT * 1e-8,
            "an earlier work angle budget remains binding before the direction wakeup");
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
        double[] speeds = {128, -244.44892172821793, 125.83293452609978, -247.01596834841894,
                127.61917189253825, -230.388492083727, 115.25820057401182, -124.5666668549444,
                503.2375537208956, -264.81072734813256, 78.05603397723071, 227.96346042002813};
        double[] twists = {2.568798562207102e-5, 2.910248591240663e-5, -4.53652424177371e-5,
                -9.808590222191554e-5, 2.3292462391982396e-5, 7.363654652502654e-5,
                3.89678532571365e-5, -2.571839223439023e-5, 5.164240290451845e-5,
                -5.4148254879328395e-5, -8.884733729388384e-6};
        compare(bodies, links, speeds, twists);
        // The full-duration family is non-monotone for this inconsistent initial
        // return-mapping state. It is not a continuous reference trajectory. The
        // chronological search must either pay the budget with actual accepted
        // steps, or diagnose an unresolved jump; a clamped overshoot is not valid.
        double budget = 4.906480999526314e-5;
        boolean diagnosed = false;
        Result masslessEvent = null;
        try {
            masslessEvent = TorsionSolver.prepare(bodies, links, speeds, twists).advanceToEvent(DT / 8, H,
                List.of(new EventLimit(10, budget, INF, INF)));
        } catch (IllegalStateException failure) {
            diagnosed = failure.getMessage().contains("budget-consistent step") && failure.getMessage().contains("inertialess");
        }
        if (!diagnosed) {
            check(masslessEvent != null && masslessEvent.duration() > 0 && masslessEvent.duration() <= DT / 8,
                "massless boundary is on the accepted chronological interval");
            check(Math.abs(masslessEvent.travel()[10] - budget) <= DT * 1e-8,
                "massless accepted motion pays the actual budget without a clamped overshoot");
            double initialEnergy = 0, finalEnergy = 0, sourceWorkBound = 0;
            for (int i = 0; i < bodies.size(); i++) {
                initialEnergy += .5 * bodies.get(i).inertia() * speeds[i] * speeds[i];
                finalEnergy += .5 * bodies.get(i).inertia() * masslessEvent.speed()[i] * masslessEvent.speed()[i];
                sourceWorkBound += Math.abs(bodies.get(i).drive()) * masslessEvent.travel()[i];
            }
            for (int i = 0; i < links.size(); i++) {
                initialEnergy += .5 * links.get(i).stiffness() * twists[i] * twists[i];
                finalEnergy += .5 * links.get(i).stiffness() * masslessEvent.twist()[i] * masslessEvent.twist()[i];
            }
            check(finalEnergy <= initialEnergy + sourceWorkBound + 1e-7,
                "massless accepted state cannot create energy beyond the source work");
            if (Boolean.getBoolean("differentialcreate.eventDiagnostic")) {
                System.out.println("Massless accepted event: time=" + masslessEvent.duration() + ", budget=" + budget
                    + ", travel=" + masslessEvent.travel()[10] + ", initialEnergy=" + initialEnergy + ", finalEnergy=" + finalEnergy);
                System.out.println("Massless event speed=" + Arrays.toString(masslessEvent.speed()));
                System.out.println("Massless event twist=" + Arrays.toString(masslessEvent.twist()));
                System.out.println("Massless event angle=" + Arrays.toString(masslessEvent.angle()));
                System.out.println("Massless event travel=" + Arrays.toString(masslessEvent.travel()));
            }
        } else check(true, "unresolvable massless jump is explicitly diagnosed");
        var motion = TorsionSolver.advance(bodies, links, speeds, twists, DT / 8, H);
        for (int i = 0; i < bodies.size(); i++)
            check(motion.travel()[i] + 1e-15 >= Math.abs(motion.angle()[i]), "absolute travel contains signed displacement");
    }

    private static void chronologicalEvents() {
        var rotor = List.of(new Body(1, -2, 0, 0, 0));
        // The positive displacement reaches .25, then returns to zero. A search
        // conditioned only on the horizon endpoint misses the .2 boundary.
        var reversal = TorsionSolver.prepare(rotor, List.of(), new double[]{1}, new double[0]);
        Result first = reversal.advanceToEvent(1, 1, List.of(new EventLimit(0, INF, .2, INF)));
        double expected = (1 - Math.sqrt(.2)) / 2;
        check(Math.abs(first.duration() - expected) <= 1e-12, "endpoint-false signed event retains its first crossing");
        check(Math.abs(first.angle()[0] - .2) <= 1e-11, "signed boundary and committed displacement agree");
        check(Math.abs(first.speed()[0] - (1 - 2 * first.duration())) <= 1e-12, "boundary commits the real analytic state");
        check(Math.abs(.5 * (first.speed()[0] * first.speed()[0] - 1) + 2 * first.angle()[0]) <= 1e-12,
            "boundary retains the actual source work balance");
        var stationary = TorsionSolver.prepare(List.of(new Body(1, 0, 0, 0, 0)), List.of(), new double[1], new double[0]);
        check(stationary.advanceToEvent(1, 1, List.of(new EventLimit(0, INF, 0, 0))).duration() == 1,
            "zero signed displacement does not select either directional zero limit");
        var forward = TorsionSolver.prepare(List.of(new Body(1, 0, 0, 0, 0)), List.of(), new double[]{1}, new double[0]);
        check(forward.advanceToEvent(1, 1, List.of(new EventLimit(0, INF, INF, 0))).duration() == 1,
            "an unused reverse zero limit does not stop forward motion");
        Result tiny = forward.advanceToEvent(1e-16, H, List.of(new EventLimit(0, 5e-17, INF, INF)));
        check(tiny.duration() <= 1e-16 && tiny.travel()[0] >= 5e-17,
            "event time resolution never extends a very short requested interval");
        var oscillators = List.of(new Body(.5, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var spring = List.of(new Link(0, 1, 1, 1, 0, INF, INF));
        first = TorsionSolver.prepare(oscillators, spring, new double[]{1, -1}, new double[1])
            .advanceToEvent(2, .1, List.of(new EventLimit(0, INF, .2, INF)));
        check(Math.abs(first.angle()[0] - .2) <= 1e-11, "midpoint boundary respects the consumer travel tolerance");
        double energy = .25 * (first.speed()[0] * first.speed()[0] + first.speed()[1] * first.speed()[1])
            + .5 * first.twist()[0] * first.twist()[0];
        check(Math.abs(energy - .5) <= 1e-10, "shortened physical steps preserve spring plus kinetic energy");
        check(first.duration() < .3, "oscillator first crossing is not replaced by a later cycle");
        double exactTime = Math.asin(.4) / 2;
        Result finer = TorsionSolver.prepare(oscillators, spring, new double[]{1, -1}, new double[1])
            .advanceToEvent(2, .05, List.of(new EventLimit(0, INF, .2, INF)));
        check(Math.abs(finer.duration() - exactTime) < Math.abs(first.duration() - exactTime) / 2,
            "accepted midpoint first-event time converges toward the independent oscillator solution");
    }

    private static void typedFractureClock() {
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0), new Body(.2, 8, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, 8192, INF));
        double[] speed = {16, 0, 7};
        Result expected = TorsionSolver.advance(bodies, links, speed, new double[1], DT, H);
        Result fracture = TorsionSolver.prepare(bodies, links, speed, new double[1])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, INF, INF)));
        check(fracture.broken() == 0 && Math.abs(fracture.duration() - expected.duration()) <= 1e-12,
            "typed chronology retains the first structural fracture");
        check(Math.abs(fracture.speed()[2] - (7 + 40 * fracture.duration())) <= 1e-12,
            "an independent analytic rotor stops on the fracture's common clock");
        check(Math.abs(fracture.angle()[2] - fracture.duration() * (7 + 20 * fracture.duration())) <= 1e-15,
            "independent displacement shares the exact fracture interval");
        double budget = expected.travel()[0] / 2;
        Result event = TorsionSolver.prepare(bodies, links, speed, new double[1])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, budget, INF, INF)));
        check(event.broken() == -1 && event.duration() < fracture.duration(),
            "a machine boundary preceding fracture is committed first");
        check(Math.abs(event.travel()[0] - budget) <= DT * 1e-8,
            "the pre-fracture machine pays only its actual travel budget");
        double energy = .25 * (event.speed()[0] * event.speed()[0] + event.speed()[1] * event.speed()[1])
            + .5e7 * event.twist()[0] * event.twist()[0];
        check(Math.abs(energy - 64) <= 1e-9, "fracture search and event shortening preserve the coupled rotors' energy");
    }

    private static void roundingBoundaries() {
        var rotor = List.of(new Body(.5, 0, 0, 0, 0));
        double budget = .4266666666666678;
        Result event = TorsionSolver.prepare(rotor, List.of(), new double[]{-1024}, new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, budget, INF, INF)));
        check(Math.abs(event.travel()[0] - budget) <= DT * 1e-8,
            "a 1024-RPM sequencer's decimal budget settles within the consumer tolerance");
        check(Math.abs(event.duration() - budget / 1024) <= 1e-14,
            "the 1024-RPM event does not require an oversized minimum interval");
        event = TorsionSolver.prepare(rotor, List.of(), new double[]{1024}, new double[0])
            .advanceToEvent(DT, H, List.of(new EventLimit(0, INF, 0, INF)));
        check(event.duration() > 0 && event.angle()[0] > 0 && event.angle()[0] <= DT * 1e-8,
            "a high-speed directional zero boundary uses a budget-sized positive interval");
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var links = List.of(new Link(0, 1, 1, 1e7, 0, INF, INF), new Link(1, 2, 1, 1e7, 0, INF, INF));
        double edge = 13 * H;
        for (double duration : new double[]{Math.nextUp(H), edge, edge + 4 * Math.ulp(edge), edge + 8 * Math.ulp(edge)}) {
            Result expected = TorsionSolver.advance(bodies, links, new double[]{128, 128, 128}, new double[2], duration, H);
            Result actual = TorsionSolver.prepare(bodies, links, new double[]{128, 128, 128}, new double[2])
                .advanceToEvent(duration, H, List.of(new EventLimit(2, INF, INF, INF)));
            same(expected, actual);
        }
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
