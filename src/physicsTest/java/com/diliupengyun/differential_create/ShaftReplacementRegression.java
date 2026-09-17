package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;

/** Loaded replacements, detached prestressed shafts and short shared fracture intervals. */
public final class ShaftReplacementRegression {
    private static final double INF = Double.POSITIVE_INFINITY, DT = 1.0 / 1200;
    private static int checks;

    public static void main(String[] args) { run(); }

    public static void run() {
        // A convergence regression must fail instead of hanging the entire test process.
        var task = new FutureTask<Void>(() -> {
            for (int direction : new int[]{1, -1}) for (int order = 0; order < 4; order++) replace(direction, order);
            return null;
        });
        Thread.ofPlatform().daemon(true).name("shaft-replacement-regression").start(task);
        try { task.get(15, TimeUnit.SECONDS); }
        catch (TimeoutException failure) {
            task.cancel(true);
            throw new AssertionError("Loaded shaft replacements must complete within 15 seconds", failure);
        }
        catch (ExecutionException failure) { throw new AssertionError("Loaded shaft replacement regression failed", failure.getCause()); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Loaded shaft replacement regression interrupted", failure);
        }
        System.out.println("Shaft replacement regression: " + checks + " checks passed (8000 ticks)");
    }

    private static void replace(int direction, int order) {
        // The player's saved arrangement: two coasting flywheels, then a much faster
        // flywheel driven through a brass clutch and 16x controller by a creative motor.
        var bodies = new ArrayList<Body>();
        for (int i = 0; i < 13; i++) bodies.add(i == 11 ? new Body(.01f, direction * 32768, 64, 0, 0)
            : new Body(i == 0 || i == 4 || i == 7 ? .5 : 0, 0, 0, 0, 0));
        var allLinks = new ArrayList<Link>();
        for (int i = 0; i < 10; i++) allLinks.add(link(i, i + 1, 1, i == 8 ? 1024 : INF));
        allLinks.add(link(10, 12, 1.0 / 16, INF)); allLinks.add(link(11, 12, 1, INF));
        double[] speed = {167.56082907714512, 167.56082907714512, 0, 0, 195.8180256473347, 0,
            8044.6580292513545, 8044.6580292513545, 8044.65802740985, 8044.658025568345,
            8044.65802372684, 502.79108222926703, 502.79111169334266};
        double[] twist = {-.0007163709952172117, 0, 0, 0, 0, 0, -.0008191569783805716,
            -3.683279350466442e-6, -3.6832793509486733e-6, -3.683279350224616e-6,
            -2.9581337285392334e-5, 5.8932469611310096e-5};
        for (int i = 0; i < speed.length; i++) speed[i] *= direction;
        for (int e = 0; e < twist.length; e++) twist[e] *= direction;
        boolean[] present = {true, true, false, false, true, false, true, true, true, true, true, true, true};
        int[] candidates = order == 0 ? new int[]{2, 3} : order == 1 ? new int[]{5} : new int[]{2, 3, 5, 1, 6};
        Random random = new Random(order);
        int fractures = 0;
        for (int tick = 0; tick < 1000; tick++) {
            if (tick % 4 == 0) for (int offset = 0; offset < candidates.length; offset++) {
                int node = candidates[(offset + (order == 3 ? random.nextInt(candidates.length) : 0)) % candidates.length];
                if (!present[node]) { present[node] = true; speed[node] = 0; break; }
            }
            double remaining = DT;
            for (int event = 0; remaining > 1e-14; event++) {
                check(event < 32, "a tick contains finitely many fracture events");
                var links = new ArrayList<Link>(); var indices = new ArrayList<Integer>();
                for (int e = 0; e < allLinks.size(); e++) {
                    Link edge = allLinks.get(e);
                    if (present[edge.a()] && present[edge.b()]) { links.add(edge); indices.add(e); }
                }
                double[] localTwist = new double[links.size()];
                for (int e = 0; e < links.size(); e++) localTwist[e] = twist[indices.get(e)];
                double before = momentum(bodies, speed);
                var result = TorsionSolver.advance(bodies, links, speed, localTwist, remaining, DT / 64);
                double supplied = (direction * 32768 * result.duration() - 64 * result.angle()[11]) / 16;
                near(before + supplied, momentum(bodies, result.speed()), 1e-8,
                    "motor work changes generalized momentum by its actual geared impulse");
                check(result.duration() > 0 && result.duration() <= remaining, "each event advances the shared clock");
                for (double value : result.speed()) check(Double.isFinite(value), "every surviving component has finite speed");
                for (int e = 0; e < links.size(); e++) {
                    check(Math.abs(result.torque()[e]) <= links.get(e).friction(), "clutch retains its physical torque limit");
                    twist[indices.get(e)] = result.twist()[e];
                }
                speed = result.speed();
                if (result.broken() >= 0) {
                    Link edge = links.get(result.broken());
                    int removed = bodies.get(edge.a()).inertia() == 0 ? edge.a() : edge.b();
                    check(bodies.get(removed).inertia() == 0, "the weaker shaft fails, preserving the physical rotors");
                    present[removed] = false; speed[removed] = 0; fractures++;
                    for (int e = 0; e < allLinks.size(); e++) {
                        Link contact = allLinks.get(e);
                        if (contact.a() == removed || contact.b() == removed) twist[e] = 0;
                    }
                }
                remaining -= result.duration();
            }
        }
        check(fractures > 0, "replacement scenario actually exercises structural failure");
    }

    private static Link link(int a, int b, double ratio, double friction) {
        return new Link(a, b, ratio, 2e7 / (1 + ratio * ratio), 0, 8192, friction);
    }

    private static double momentum(List<Body> bodies, double[] speed) {
        double result = 0;
        for (int i = 0; i < speed.length; i++) result += bodies.get(i).inertia() * speed[i] * (i == 11 ? 1.0 / 16 : 1);
        return result;
    }

    private static void near(double expected, double actual, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": " + expected + " != " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
