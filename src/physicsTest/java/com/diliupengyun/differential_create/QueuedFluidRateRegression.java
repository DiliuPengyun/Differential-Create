package com.diliupengyun.differential_create;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.*;

/** Independent high-flow steady-state, inertial circulation and transport-clock oracles. */
public final class QueuedFluidRateRegression {
    static final double TICK = RotationMath.TICK_MINUTES;
    static final IdealFluidSolver.Material WATER = new IdealFluidSolver.Material(1000, false);
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static void physical(double expected, double actual, String name) {
        boolean pass = Double.isFinite(actual) && Math.abs(expected - actual) <= 1e-7 * Math.max(1, Math.abs(expected));
        check(pass, name + ": expected=" + expected + ", actual=" + actual);
    }
    static double freeLoopFlow(double rpm) { return Math.abs(rpm) / 2; }
    static double openFlow(double rpm) {
        // Pump head = 2 open-boundary kinetic-head losses. Horizontal, full,
        // identical water, no pipe friction. Derived in SI, not from sample().
        double a = Math.abs(rpm) / 64;
        double quadratic = Math.pow(20e-6 / 1e-4, 2);
        double linear = 9.81 * 16 * a / 32;
        double constant = 9.81 * 16 * a * a;
        return 2 * constant / (linear + Math.sqrt(linear * linear + 4 * quadratic * constant));
    }
    static List<QueuedFluidSolver.Cell> cells(int n, double capacity, double rpm, double q) {
        var result = new ArrayList<QueuedFluidSolver.Cell>();
        for (int i = 0; i < n; i++) result.add(new QueuedFluidSolver.Cell(capacity, 0, 1000, 1000, i == 0 ? rpm : 0, i == 0 ? q : 0));
        return result;
    }
    static List<QueuedFluidSolver.Link> ringLinks(boolean pump) {
        return List.of(new QueuedFluidSolver.Link(0, 1, .75, 0, pump ? 1 : 0, 0, true),
            new QueuedFluidSolver.Link(1, 2, 1, 0, 0, 0, true),
            new QueuedFluidSolver.Link(2, 0, .75, 0, 0, pump ? -1 : 0, true));
    }
    static double ring(double capacity, double rpm, double initial, double dt, int steps, boolean pump) {
        double[] q = {initial, initial, initial};
        var links = ringLinks(pump);
        for (int t = 0; t < steps; t++) {
            var r = QueuedFluidSolver.sample(cells(3, capacity, rpm, q[0]), links,
                new QueuedFluidSolver.Boundary[0], q, dt, Long.MAX_VALUE, (e, f) -> true);
            check(r.solves() > 0, "ring solve must run");
            q = r.flow();
            for (double v : q) check(Double.isFinite(v) && Math.abs(v - q[0]) < 1e-8, "ring continuity and finite state");
        }
        return q[0];
    }
    static double line(int n, double capacity, double rpm, double dt) {
        var links = new ArrayList<QueuedFluidSolver.Link>();
        for (int i = 0; i < n - 1; i++) links.add(new QueuedFluidSolver.Link(i, i + 1, i == 0 ? .75 : 1, 0, i == 0 ? 1 : 0, 0, true));
        links.add(new QueuedFluidSolver.Link(0, n, .25, 0, -1, 0, true));
        links.add(new QueuedFluidSolver.Link(n - 1, n + 1, n == 1 ? .25 : .5, 0, n == 1 ? 1 : 0, 0, true));
        var boundaries = new QueuedFluidSolver.Boundary[]{new QueuedFluidSolver.Boundary(0, true, true, 1000), new QueuedFluidSolver.Boundary(0, true, true, 1000)};
        double[] q = new double[links.size()];
        int stable = 0;
        for (int t = 0; t < 10000; t++) {
            var r = QueuedFluidSolver.sample(cells(n, capacity, rpm, q[q.length - 1]), links, boundaries, q, dt, Long.MAX_VALUE, (e, f) -> true);
            check(r.solves() > 0, "line solve must run");
            double delta = 0;
            for (int e = 0; e < q.length; e++) {
                delta = Math.max(delta, Math.abs(q[e] - r.flow()[e]));
                check(Double.isFinite(r.flow()[e]), "finite line flow");
            }
            q = r.flow();
            double through = q[q.length - 1];
            for (int e = 0; e < q.length; e++) check(Math.abs((e == n - 1 ? -q[e] : q[e]) - through) < 1e-7, "line continuity");
            stable = delta < 1e-10 ? stable + 1 : 0;
            if (t > 100 && stable >= 20) return through;
        }
        throw new AssertionError("line failed to settle");
    }
    static void branch() {
        // A valid steady Y-flow: three full straight arms around a 150 mB
        // junction. Boundary pressures exactly pay their respective outlet
        // kinetic-head losses; internal pressure is constant, all pumps off.
        var cells = new ArrayList<QueuedFluidSolver.Cell>();
        cells.add(new QueuedFluidSolver.Cell(150, 0, 1000, 1000, 0, 0));
        for (int i = 0; i < 3; i++) cells.add(new QueuedFluidSolver.Cell(100, 0, 1000, 1000, 0, 0));
        var links = new ArrayList<QueuedFluidSolver.Link>();
        for (int i = 1; i <= 3; i++) links.add(new QueuedFluidSolver.Link(0, i, 1, 0, 0, 0, true));
        for (int i = 1; i <= 3; i++) links.add(new QueuedFluidSolver.Link(i, i + 3, .5, 0, 0, 0, true));
        double[] initial = {-160, 128, 32, -160, 128, 32};
        var boundaries = new QueuedFluidSolver.Boundary[3];
        for (int i = 0; i < 3; i++) boundaries[i] = new QueuedFluidSolver.Boundary(-WATER.outletForce(initial[i]), true, true, 1000);
        var r = QueuedFluidSolver.sample(cells, links, boundaries, initial, TICK / 64, Long.MAX_VALUE, (e, f) -> true);
        check(r.solves() > 0, "branch solve must run");
        check(Math.abs(r.flow()[0] + r.flow()[1] + r.flow()[2]) < 1e-8, "branch volume continuity");
        for (int i = 0; i < 3; i++) physical(initial[i], r.flow()[i], "steady_Y_branch_" + i);
    }
    static void controls() {
        var links = ringLinks(false);
        var c = cells(3, 100, 0, 0);
        var still = QueuedFluidSolver.sample(c, links, new QueuedFluidSolver.Boundary[0], new double[3], TICK, Long.MAX_VALUE, (e, f) -> true);
        check(Arrays.stream(still.flow()).allMatch(v -> v == 0), "unforced loop at rest stays at rest");
        var blocked = new ArrayList<>(links);
        var e = blocked.get(0);
        blocked.set(0, new QueuedFluidSolver.Link(e.from(), e.to(), e.length(), 0, 0, 0, false));
        var stop = QueuedFluidSolver.sample(c, blocked, new QueuedFluidSolver.Boundary[0], new double[]{100, 100, 100}, TICK, Long.MAX_VALUE, (k, f) -> true);
        check(Arrays.stream(stop.flow()).allMatch(v -> v == 0), "closing full incompressible loop physically stops motion");
        var q = new FluidQueue<String>(String::equals); q.add("water", 100, false);
        boolean rejected = false;
        try { q.take(128, true); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && q.volume() == 100, "one-pass transport cannot just remove more than start-of-tick inventory");
    }
    static void fastTransport() {
        var cells = cells(4, 100, 0, 0);
        var links = List.of(new QueuedFluidSolver.Link(0, 1, 1, 0, 0, 0, true),
            new QueuedFluidSolver.Link(1, 2, 1, 0, 0, 0, true),
            new QueuedFluidSolver.Link(2, 3, 1, 0, 0, 0, true),
            new QueuedFluidSolver.Link(0, 4, .5, 0, 0, 0, true),
            new QueuedFluidSolver.Link(3, 5, .5, 0, 0, 0, true));
        double[] rates = {400, 400, 400, -400, 400};
        var queues = new ArrayList<FluidQueue<String>>();
        for (int n = 0; n < 4; n++) {
            var q = new FluidQueue<String>(String::equals); q.add("air", 100, false); queues.add(q);
        }
        double introduced = 0, outputWater = 0, outputAir = 0;
        for (int tick = 0; tick < 4; tick++) {
            double remaining = TICK;
            while (remaining > 0) {
                double dt = QueuedFluidSolver.transportMinutes(cells, links, rates, remaining);
                double amount = 400 * dt / TICK;
                check(amount <= 100 + 1e-12, "substep respects all donor stock without changing velocity");
                var packets = new ArrayList<List<FluidQueue.Part<String>>>();
                for (var q : queues) packets.add(q.take(amount, true));
                queues.get(0).add("water", amount, false); introduced += amount;
                for (int n = 1; n < 4; n++) queues.get(n).receive(packets.get(n - 1), false);
                for (var p : packets.get(3)) {
                    if (p.identity().equals("water")) outputWater += p.amount(); else outputAir += p.amount();
                }
                for (var q : queues) check(Math.abs(q.volume() - 100) < 1e-9, "each substep keeps cells full");
                remaining = Math.max(0, remaining - dt);
            }
        }
        physical(1600, introduced, "four_ticks_400_mB_input");
        physical(1200, outputWater, "four_ticks_400_mB_output_after_pipe_fill");
        physical(400, outputAir, "initial_air_is_displaced_once");
        check(Math.abs(introduced - outputWater - queues.stream().mapToDouble(q -> q.volume("water"::equals)).sum()) < 1e-8,
            "full elapsed time, actual high-speed transfer and independent water ledger agree");
    }
    static void inventoryBoundaries(int height, double density, double initial) {
        int n = Math.max(4, height);
        var cells = new ArrayList<QueuedFluidSolver.Cell>();
        var links = new ArrayList<QueuedFluidSolver.Link>();
        for (int i = 0; i < n; i++) cells.add(new QueuedFluidSolver.Cell(100,
            height == 0 ? 0 : height - i - .5, density, density, 0, 0));
        for (int i = 0; i < n - 1; i++) links.add(new QueuedFluidSolver.Link(i, i + 1, 1,
            height == 0 ? 0 : height - i - 1, 0, 0, true));
        links.add(new QueuedFluidSolver.Link(0, n, .5, height, 0, 0, true));
        links.add(new QueuedFluidSolver.Link(n - 1, n + 1, .5, 0, 0, 0, true));
        var boundaries = new QueuedFluidSolver.Boundary[]{
            new QueuedFluidSolver.Boundary(0, true, true, density),
            new QueuedFluidSolver.Boundary(0, true, true, density)};
        double[] flow = new double[links.size()]; Arrays.fill(flow, initial); flow[n - 1] = -initial;
        // rho*g*H balances two rho*v^2/2 exchange losses. No inventory
        // pressure or pump is present; density cancels from the terminal speed.
        double terminal = Math.sqrt(9.81 * height) / .2;
        for (int tick = 0; tick < 1600; tick++) {
            double remaining = TICK;
            while (remaining > 0) {
                double dt = QueuedFluidSolver.transportMinutes(cells, links, flow, remaining);
                var result = QueuedFluidSolver.sample(cells, links, boundaries, flow, dt, Long.MAX_VALUE, (e, f) -> true);
                check(result.solves() > 0, "inventory boundary solve completes");
                double before = flow[n], after = result.flow()[n];
                if (height == 0) {
                    check(before * after >= 0 && Math.abs(after) <= Math.abs(before) + 1e-9,
                        "container exchange cannot increase coasting energy or reverse horizontal flow");
                } else check(after >= -1e-8 && after <= terminal + 1e-7,
                    "gravity startup remains finite and bounded by the independent terminal speed");
                flow = result.flow();
                for (int e = 0; e < flow.length; e++) physical(after, e == n - 1 ? -flow[e] : flow[e], "inventory boundary continuity");
                remaining = Math.max(0, remaining - dt);
            }
        }
        if (height > 0) physical(terminal, flow[n], "finite gravity loop, H=" + height + ", density=" + density);
        else if (initial == 0) physical(0, flow[n], "horizontal equal-pressure rest");
        else check(Math.abs(flow[n]) < .3 && Math.abs(flow[n]) > 0,
            "horizontal coasting decays without an artificial cutoff");
    }
    static void actualTransportStock() {
        double stock = 100 - 128 * Math.ulp(100d);
        var queue = new FluidQueue<String>(String::equals); queue.add("water", stock, false);
        var cells = List.of(new QueuedFluidSolver.Cell(queue.volume(), 0, 1000, 1000, 0, 0));
        var links = List.of(new QueuedFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        double dt = QueuedFluidSolver.transportMinutes(cells, links, new double[]{100}, TICK);
        check(dt < TICK, "actual parcel stock shortens a transfer even below nominal geometry by roundoff");
        double sent = queue.take(100 * dt / TICK, true).stream().mapToDouble(FluidQueue.Part::amount).sum();
        physical(stock, sent + queue.volume(), "roundoff-limited transfer conserves every owned parcel");
    }
    static void singlePortCycles() {
        var cells = cells(8, 100, 0, 0);
        var links = new ArrayList<QueuedFluidSolver.Link>();
        for (int ring = 0; ring < 2; ring++) for (int n = 0; n < 4; n++)
            links.add(new QueuedFluidSolver.Link(4 * ring + n, 4 * ring + (n + 1) % 4, 1, 0, 0, 0, true));
        links.add(new QueuedFluidSolver.Link(0, 8, .5, 0, 0, 0, true));
        links.add(new QueuedFluidSolver.Link(4, 9, .5, 0, 0, 0, true));
        double[] flow = {3, 3, 3, 3, -5, -5, -5, -5, 0, 0};
        for (double pressure : new double[]{-1e12, 0, 1e12}) {
            var boundaries = new QueuedFluidSolver.Boundary[]{
                new QueuedFluidSolver.Boundary(pressure, true, true, 1000),
                new QueuedFluidSolver.Boundary(-pressure, true, true, 1000)};
            var result = QueuedFluidSolver.sample(cells, links, boundaries, flow, TICK, Long.MAX_VALUE, (e, f) -> true);
            check(result.solves() > 0 && result.flow()[8] == 0 && result.flow()[9] == 0,
                "each single-port closed cycle has exactly zero external exchange at any boundary pressure");
            for (int e = 0; e < 8; e++) physical(flow[e], result.flow()[e], "closing the sole port retains internal circulation");
        }
    }
    public static void run() {
        controls();
        fastTransport();
        actualTransportStock();
        singlePortCycles();
        for (double density : new double[]{1000, 3000}) {
            for (int height : new int[]{4, 16, 64}) inventoryBoundaries(height, density, 0);
            for (double initial : new double[]{0, -64, 64, -16384, 16384}) inventoryBoundaries(0, density, initial);
        }
        for (double initial : new double[]{0, 32, 50, 64, 100, -100, 1000})
            for (int divisor : new int[]{1, 8, 64}) {
                double actual = ring(100, 0, initial, TICK / divisor, 1, false);
                physical(initial, actual, "inviscid_initial_" + initial + "_dt_1/" + divisor);
            }
        double q = ring(100, 0, 100, TICK, 200, false);
        physical(100, q, "inviscid_circulation_after_200_updates");
        for (double capacity : new double[]{25, 100, 1000})
            physical(freeLoopFlow(256), ring(capacity, 256, 0, TICK / 8, 1000, true), "closed_pump256_capacity_" + capacity);
        for (int n : new int[]{1, 5, 17}) for (double rpm : new double[]{64, 96, 128, 256, 512, 1024}) {
            double actual = line(n, 100, rpm, TICK);
            check(Math.abs(actual - openFlow(rpm)) < 1e-7, "pump steady state agrees with the independent head balance, rpm=" + rpm + ", cells=" + n);
        }
        for (int divisor : new int[]{1, 8, 64}) for (double capacity : new double[]{100, 1000}) {
            double actual = line(5, capacity, 256, TICK / divisor);
            check(Math.abs(actual - openFlow(256)) < 1e-7, "capacity and numerical step do not set the physical steady-flow limit");
        }
        branch();
        System.out.println("Queued fluid rate regression passed: " + checks + " checks");
    }
}
