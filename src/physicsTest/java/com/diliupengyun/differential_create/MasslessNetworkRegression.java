package com.diliupengyun.differential_create;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.EventLimit;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Result;

/** All-zero inertia graphs. Oracles come from torque balance, cycle closure and work,
 * not a second invocation of the production solver. No fictitious inertia is added.
 * Arbitrary preload is an inconsistent DAE initial state: its midpoint reflection
 * is tested as a discrete constraint solve, not mistaken for physical rotor motion. */
public final class MasslessNetworkRegression {
    private static final double INF = Double.POSITIVE_INFINITY, H = 1.0 / 1200 / 64;
    private static final Body FREE = new Body(0, 0, 0, 0, 0);
    private static int checks, rejected;

    public static void main(String[] args) { run(); }

    public static void run() {
        coastingGraphs();
        freeTrees();
        reorderedTrees();
        selfStressedCycles();
        longRuns();
        balancedForces();
        disconnectedAndEvents();
        singularLimits();
        System.out.println("Massless network regression: " + checks + " checks passed; "
            + rejected + " unsafe/inconsistent trials explicitly rejected");
    }

    private static List<Body> free(int n) { return Collections.nCopies(n, FREE); }
    private static Link link(int a, int b, double q, double k, double c) {
        return new Link(a, b, q, k, c, INF, INF);
    }

    private static void coastingGraphs() {
        for (int n : new int[]{3, 33, 257}) for (boolean cycle : new boolean[]{false, true}) {
            var links = new ArrayList<Link>();
            double[] ratio = new double[n]; ratio[0] = 1;
            for (int i = 1; i < n; i++) {
                double q = i % 2 == 0 ? -.5 : -2;
                ratio[i] = q * ratio[i - 1];
                links.add(link(i - 1, i, q, 1e7, 0));
            }
            if (cycle) links.add(link(n - 1, 0, 1 / ratio[n - 1], 1e7, 0));
            for (double common : new double[]{0, 1e-150, -128, 1e12, -1e12}) {
                double[] speed = Arrays.stream(ratio).map(q -> q * common).toArray(), twist = new double[links.size()];
                var capture = TorsionSolver.prepare(free(n), links, speed, twist);
                // One huge interval exercises analytic coasting without millions of substeps.
                Result r = capture.advance(1000, H);
                finite(r); check(r.broken() < 0, "unstressed free graph never fractures");
                for (int i = 0; i < n; i++) {
                    near(speed[i], r.speed()[i], 0, "selected free speed is preserved exactly");
                    near(1000 * speed[i], r.angle()[i], 0, "free angle has no substep drift");
                    near(Math.abs(r.angle()[i]), r.travel()[i], 0, "coasting travel");
                }
                for (double x : r.twist()) near(0, x, 0, "coasting cannot generate preload");
                Result zero = capture.advance(0, H);
                check(Arrays.equals(speed, zero.speed()) && Arrays.equals(twist, zero.twist()), "zero time is identity");
                if (common == 0 || Math.abs(common) < 1e-100) continue;
                double budget = Math.abs(speed[n - 1]) * H * .375;
                // Signed-angle limits force chronological graphSegment, including reverse motion.
                Result event = capture.advanceToEvent(H, H, List.of(new EventLimit(n - 1, INF, budget, budget)));
                near(.375 * H, event.duration(), 2e-14, "signed angle event time on a large free graph");
                near(budget, Math.abs(event.angle()[n - 1]), Math.max(2e-11, 32 * Math.ulp(budget)), "event pays its budget");
            }
        }
    }

    private static void freeTrees() {
        for (int n : new int[]{2, 9, 33}) for (boolean branched : new boolean[]{false, true})
            for (double h : new double[]{H, 1e-8, 1e-16}) for (double dampingRatio : new double[]{0, 1e-6, 1, 1e6}) {
                var links = new ArrayList<Link>();
                double[] twist = new double[n - 1], expected = new double[n - 1];
                Random random = new Random(103 + n);
                for (int i = 1; i < n; i++) {
                    int parent = branched ? (i - 1) / 2 : i - 1;
                    double q = i % 2 == 0 ? -.5 : -2;
                    // Twelve orders of stiffness, while every spring stores comparable energy.
                    double k = switch (i % 3) { case 0 -> 1; case 1 -> 1e6; default -> 1e12; };
                    double c = dampingRatio * h * k / 2;
                    links.add(link(parent, i, q, k, c));
                    twist[i - 1] = (random.nextDouble() - .5) / Math.sqrt(k);
                    // A free tree has no self-stress: each edge torque is exactly zero.
                    // Therefore c * dx/dt + k * x_mid = 0, independently of its ratio.
                    expected[i - 1] = twist[i - 1] * (dampingRatio - 1) / (dampingRatio + 1);
                }
                Result r = TorsionSolver.advance(free(n), links, new double[n], twist, h, h);
                finite(r); check(r.broken() < 0, "unbounded-strength free tree completes");
                for (int e = 0; e < links.size(); e++) {
                    near(expected[e], r.twist()[e], 2e-10 * Math.abs(twist[e]) + 1e-18, "free-tree analytic relaxation");
                    near(0, r.torque()[e], 2e-10 * Math.abs(links.get(e).stiffness() * twist[e]) + 1e-9,
                        "each free-tree contact balances torque");
                }
                constraints(links, twist, r);
                passiveWork(links, twist, r);
            }
    }

    private static List<Link> ring(double lastRatio, double damping) {
        return List.of(link(0, 1, -2, 1e7, damping), link(1, 2, .5, 2e7, damping),
            link(2, 3, -2, 4e7, damping), link(3, 0, lastRatio, 8e7, damping));
    }

    private static void reorderedTrees() {
        for (int seed = 0; seed < 12; seed++) {
            int n = 17;
            Random random = new Random(seed);
            var order = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) order.add(i);
            Collections.shuffle(order, random);
            var links = new ArrayList<Link>();
            var strains = new ArrayList<Double>();
            for (int i = 1; i < n; i++) {
                int a = order.get(random.nextInt(i)), b = order.get(i);
                double q = i % 2 == 0 ? -.5 : -2, k = Math.pow(10, 2 * (i % 7)), x = .25 / Math.sqrt(k);
                if (random.nextBoolean()) {
                    int swap = a; a = b; b = swap;
                    // Reversing a physical edge transforms x -> -x/q and K -> K*q^2.
                    x = -x / q; k *= q * q; q = 1 / q;
                }
                links.add(new Link(a, b, q, k, 0, INF, seed % 2 == 0 ? INF : .01));
                strains.add(x);
            }
            // Shuffle edge order together with its saved state, independently of node numbering.
            var edgeOrder = new ArrayList<Integer>();
            for (int e = 0; e < links.size(); e++) edgeOrder.add(e);
            Collections.shuffle(edgeOrder, random);
            var shuffled = new ArrayList<Link>();
            double[] initial = new double[n - 1];
            for (int e = 0; e < initial.length; e++) {
                shuffled.add(links.get(edgeOrder.get(e))); initial[e] = strains.get(edgeOrder.get(e));
            }
            Result r = TorsionSolver.advance(free(n), shuffled, new double[n], initial, H, H);
            finite(r); constraints(shuffled, initial, r); passiveWork(shuffled, initial, r);
            for (int e = 0; e < initial.length; e++) {
                near(-initial[e], r.twist()[e], 32 * Math.ulp(initial[e]), "permutation and orientation preserve free-tree strain");
                near(0, r.torque()[e], 0, "finite clutch limits do not invent free-tree torque");
                check(!r.slipping()[e], "zero required torque does not slip a finite clutch");
            }
        }
    }

    private static void selfStressedCycles() {
        var links = ring(.5, 0);
        // Node torque balance gives T = lambda * s. Cycle closure conserves s dot x.
        double[] selfStress = {1, 2, -1, -2};
        for (int sign : new int[]{1, -1}) for (double h : new double[]{H, 1e-8, 1e-16}) {
            double[] initial = {sign * 1e-4, sign * -2e-4, sign * 3e-4, sign * 4e-4};
            double circulation = 0, compliance = 0;
            for (int e = 0; e < 4; e++) {
                circulation += selfStress[e] * initial[e];
                compliance += selfStress[e] * selfStress[e] / links.get(e).stiffness();
            }
            double lambda = circulation / compliance;
            var capture = TorsionSolver.prepare(free(4), links, new double[4], initial);
            for (Result r : List.of(capture.advance(h, h), capture.advanceToEvent(h, h,
                List.of(new EventLimit(0, INF, 1, 1))))) {
                finite(r); constraints(links, initial, r); passiveWork(links, initial, r);
                double endCirculation = 0;
                for (int e = 0; e < 4; e++) {
                    double torque = lambda * selfStress[e];
                    near(torque, r.torque()[e], 1e-8, "closed-loop self-stress balances each zero-inertia node");
                    near(2 * torque / links.get(e).stiffness() - initial[e], r.twist()[e], 1e-16,
                        "cycle compliance determines midpoint preload");
                    endCirculation += selfStress[e] * r.twist()[e];
                }
                near(circulation, endCirculation, 1e-17, "cycle closure is invariant");
            }
        }
        // A balanced self-stress still has a structural strength, including when all shafts are stopped.
        for (double factor : new double[]{1 - 1e-8, 1 + 1e-8}) {
            var weak = new ArrayList<Link>(links);
            Link edge = weak.get(2);
            weak.set(2, new Link(edge.a(), edge.b(), edge.ratio(), edge.stiffness(), 0, 8192, INF));
            double[] preload = new double[4];
            for (int e = 0; e < 4; e++) preload[e] = 8192 * factor * selfStress[e] / links.get(e).stiffness();
            var capture = TorsionSolver.prepare(free(4), weak, new double[4], preload);
            for (Result r : List.of(capture.advance(H, H), capture.advanceToEvent(H, H,
                List.of(new EventLimit(0, INF, 1, 1))))) {
                finite(r);
                check(factor < 1 ? r.broken() < 0 : r.broken() == 2, "cycle self-stress obeys strength on the correct edge");
                if (factor > 1) check(r.duration() > 0 && r.duration() <= 1e-12, "existing cycle overload breaks immediately");
            }
        }
    }

    private static void longRuns() {
        for (boolean cycle : new boolean[]{false, true}) for (boolean damped : new boolean[]{false, true}) {
            var links = new ArrayList<Link>();
            for (int i = 0; i < (cycle ? 4 : 3); i++) links.add(link(i, (i + 1) % 4, 1, 1e7, damped ? 16 : 0));
            double[] original = cycle ? new double[]{1e-4, -2e-4, 3e-4, 4e-4} : new double[]{1e-4, -2e-4, 3e-4};
            double[] twist = original.clone(), speed = {32, 32, 32, 32};
            double equilibrium = cycle ? Arrays.stream(original).sum() / 4 : 0;
            double h = 1e-3, c = damped ? 16 : 0, decay = (c - h * 1e7 / 2) / (c + h * 1e7 / 2);
            double before = energy(links, twist);
            for (int tick = 1; tick <= 10000; tick++) {
                Result r = TorsionSolver.advance(free(4), links, speed, twist, h, h);
                finite(r); check(r.broken() < 0, "long-running graph does not invent fracture");
                double after = energy(links, r.twist());
                check(after <= before + 1e-12, "passive zero-inertia motion cannot increase energy");
                for (int e = 0; e < links.size(); e++) {
                    near(equilibrium + (original[e] - equilibrium) * Math.pow(decay, tick), r.twist()[e], 1e-14,
                        "long-time preload follows the independent closed-form recurrence");
                }
                // No physical momentum fixes the common speed. This well-conditioned fixture
                // should nevertheless have bounded numerical motion, not an accumulating free-mode error.
                for (double w : r.speed()) check(Math.abs(w - 32) < 2, "free numerical mode stays bounded over 10000 steps");
                before = after; speed = r.speed(); twist = r.twist();
            }
        }
    }

    private static void balancedForces() {
        for (double q : new double[]{.5, -2, 16}) for (double torque : new double[]{-64, 64})
            for (double h : new double[]{H, 1e-12}) {
                var bodies = List.of(new Body(0, q * torque, 0, 0, 0), new Body(0, -torque, 0, 0, 0));
                var links = List.of(link(0, 1, q, 1e7, 0));
                double[] preload = {torque / 1e7 * .25};
                Result r = TorsionSolver.advance(bodies, links, new double[2], preload, h, h);
                finite(r); constraints(links, preload, r);
                near(torque, r.torque()[0], 1e-10, "balanced applied torque needs no inertia");
                near(2 * torque / 1e7 - preload[0], r.twist()[0], 1e-17, "applied torque sets spring midpoint");
                double work = q * torque * r.angle()[0] - torque * r.angle()[1];
                near(work, energy(links, r.twist()) - energy(links, preload), 1e-12, "elastic energy equals external work");
            }
        // A viscous boundary removes the free speed, giving a unique algebraic equilibrium.
        for (double q : new double[]{.5, -2}) {
            double drive = 64, ca = 2, cb = 5, w = drive / (ca + q * q * cb), torque = cb * q * w;
            var bodies = List.of(new Body(0, drive, ca, 0, 0), new Body(0, 0, cb, 0, 0));
            var links = List.of(link(0, 1, q, 1e7, 0));
            Result r = TorsionSolver.advance(bodies, links, new double[]{w, q * w}, new double[]{torque / 1e7}, H, H);
            finite(r); near(w, r.speed()[0], 1e-12, "massless viscous equilibrium input");
            near(q * w, r.speed()[1], 1e-12, "massless viscous equilibrium output");
            near(torque, r.torque()[0], 1e-10, "massless viscous equilibrium torque");
        }
        // Coupled drives are updated on an existing capture. Its cached free-tree
        // classification must not bypass a subsequently applied force.
        var links = List.of(link(0, 1, 1, 1e7, 0));
        double[] preload = {1e-6};
        var capture = TorsionSolver.prepare(free(2), links, new double[2], preload);
        capture.advance(H, H);
        capture.updateDrives(new double[]{32, -32});
        Result driven = capture.advance(H, H);
        near(32, driven.torque()[0], 1e-10, "drive update invalidates the passive solve");
        near(64 / 1e7 - preload[0], driven.twist()[0], 1e-17, "updated drive uses captured initial strain");
        capture.updateDrives(new double[2]);
        Result freeAgain = capture.advance(H, H);
        near(-preload[0], freeAgain.twist()[0], 1e-18, "removing drive restores passive torque balance");
    }

    private static void disconnectedAndEvents() {
        var links = List.of(link(0, 1, -2, 1e7, 0), link(2, 3, .5, 1e7, 0),
            new Link(1, 2, 1, 1e7, 0, INF, 0));
        double[] speed = {16, -32, -128, -64, 1e12}, twist = {0, 0, .1};
        double[] savedSpeed = speed.clone(), savedTwist = twist.clone();
        var capture = TorsionSolver.prepare(free(5), links, speed, twist);
        double budget = 64 * H / 4;
        Result event = capture.advanceToEvent(H, H, List.of(new EventLimit(3, budget, INF, INF)));
        near(H / 4, event.duration(), 1e-14, "disconnected graphs use the earliest common event clock");
        for (int i = 0; i < speed.length; i++) {
            near(speed[i], event.speed()[i], 0, "open clutch cannot mix selected free speeds");
            near(speed[i] * event.duration(), event.angle()[i], 16 * Math.ulp(speed[i] * event.duration()),
                "unrelated graph stops at the same event");
        }
        near(0, event.twist()[2], 0, "open clutch releases preload");
        check(Arrays.equals(savedSpeed, speed) && Arrays.equals(savedTwist, twist), "trials preserve captured inputs");

        // A massless preload jump has finite displacement even as h tends to zero.
        // It cannot be squeezed into a smaller travel budget by halving the time step.
        var pair = List.of(link(0, 1, 1, 1e7, 0));
        double[] preload = {1e-3};
        var jump = TorsionSolver.prepare(free(2), pair, new double[2], preload);
        Result before = jump.advance(H, H);
        expectFailure(() -> jump.advanceToEvent(H, H,
            List.of(new EventLimit(0, 1e-6, INF, INF), new EventLimit(1, 1e-6, INF, INF))),
            "no budget-consistent step", "inconsistent DAE jump must not grant unpaid machine travel");
        Result after = jump.advance(H, H);
        check(Arrays.equals(before.twist(), after.twist()) && Arrays.equals(before.angle(), after.angle()),
            "rejected event search cannot poison a later trial");
        near(1e-3, preload[0], 0, "failed event does not mutate initial preload");
    }

    private static void singularLimits() {
        for (double q : new double[]{-1, 2}) {
            var links = List.of(link(0, 1, 1, 1e7, 0), link(1, 2, 1, 1e7, 0), link(2, 0, q, 1e7, 0));
            Result locked = TorsionSolver.advance(free(3), links, new double[]{32, 32, 32}, new double[3], H, H);
            finite(locked);
            for (double w : locked.speed()) near(0, w, 1e-10, "inconsistent rigid cycle has only zero unloaded speed");
            for (double x : locked.twist()) near(0, x, 1e-16, "locking an unstressed massless loop creates no energy");
        }
        // Independent 80-digit closure oracle, rather than squaring an ill-conditioned
        // incidence matrix. These moderate condition numbers must still solve normally.
        for (double closing : new double[]{-1, 1.01, .99}) for (double h : new double[]{H, 1e-12}) {
            var links = new ArrayList<Link>();
            for (int i = 0; i < 4; i++) links.add(link(i, (i + 1) % 4, i == 3 ? closing : 1, 1e7 * (1 << i), 0));
            double[] initial = {1e-4, -2e-4, 3e-4, 4e-4};
            double[] angle = ringAngles(links, initial);
            Result r = TorsionSolver.advance(free(4), links, new double[4], initial, h, h);
            finite(r); constraints(links, initial, r); passiveWork(links, initial, r);
            for (int i = 0; i < 4; i++) near(angle[i], r.angle()[i], 2e-11 * Math.abs(angle[i]) + 1e-14,
                "unique inconsistent-cycle displacement agrees with high-precision closure");
        }
        // Beyond this conditioning range the current double solver must refuse an
        // energy-inconsistent trial. A valid finite answer may replace the diagnostic
        // in a future solver, but NaNs, silently frozen preload and arbitrary crashes may not.
        for (double closing : new double[]{1.000001, .999999, 1.00000001, .99999999})
            for (double h : new double[]{H, 1e-12}) {
                var links = new ArrayList<Link>();
                for (int i = 0; i < 4; i++) links.add(link(i, (i + 1) % 4, i == 3 ? closing : 1, 1e7 * (1 << i), 0));
                double[] initial = {1e-4, -2e-4, 3e-4, 4e-4}, saved = initial.clone();
                var capture = TorsionSolver.prepare(free(4), links, new double[4], initial);
                try {
                    Result r = capture.advance(h, h);
                    finite(r); constraints(links, initial, r); passiveWork(links, initial, r);
                    double[] angle = ringAngles(links, initial);
                    for (int i = 0; i < 4; i++) {
                        near(angle[i], r.angle()[i], 1e-7 * Math.abs(angle[i]), "near-singular closure");
                        near(-initial[i], r.twist()[i], 1e-13, "near-singular spring still obeys zero midpoint torque");
                    }
                } catch (RuntimeException failure) {
                    check(failure.getClass().getSimpleName().equals("ConvergenceFailure")
                        && (failure.getMessage().startsWith("Torsion work balance did not converge")
                            || failure.getMessage().startsWith("Torsion linear solve did not converge")),
                        "ill-conditioned loop has an explicit convergence diagnostic: " + failure);
                    rejected++;
                }
                check(Arrays.equals(saved, initial), "failed near-singular solve does not mutate preload");
            }
        var unbalanced = List.of(new Body(0, 32, 0, 0, 0), FREE);
        expectFailure(() -> TorsionSolver.advance(unbalanced, List.of(link(0, 1, 1, 1e7, 0)),
            new double[2], new double[1], H, H), "Torsion linear solve did not converge",
            "unopposed torque on an unanchored zero-inertia graph has no algebraic solution");
    }

    private static double[] ringAngles(List<Link> links, double[] twist) {
        MathContext mc = new MathContext(80);
        BigDecimal product = BigDecimal.ONE, offset = BigDecimal.ZERO;
        for (int e = 0; e < links.size(); e++) {
            BigDecimal q = new BigDecimal(links.get(e).ratio(), mc);
            product = product.multiply(q, mc);
            // theta_next = q * theta + 2 * initialTwist (zero midpoint torque).
            offset = offset.multiply(q, mc).add(new BigDecimal(twist[e], mc).multiply(BigDecimal.TWO, mc), mc);
        }
        BigDecimal first = offset.divide(BigDecimal.ONE.subtract(product, mc), mc), current = first;
        double[] angle = new double[links.size()];
        for (int e = 0; e < links.size(); e++) {
            angle[e] = current.doubleValue();
            current = current.multiply(new BigDecimal(links.get(e).ratio(), mc), mc)
                .add(new BigDecimal(twist[e], mc).multiply(BigDecimal.TWO, mc), mc);
        }
        return angle;
    }

    private static void constraints(List<Link> links, double[] initial, Result r) {
        for (int e = 0; e < links.size(); e++) {
            Link edge = links.get(e);
            double left = edge.ratio() * r.angle()[edge.a()], right = r.angle()[edge.b()];
            near(r.twist()[e] - initial[e], left - right,
                64 * (Math.ulp(left) + Math.ulp(right)) + 1e-15 * Math.abs(initial[e]), "spring displacement identity");
        }
    }

    private static void passiveWork(List<Link> links, double[] initial, Result r) {
        double loss = 0;
        for (int e = 0; e < links.size(); e++) {
            Link edge = links.get(e);
            double rate = (r.twist()[e] - initial[e]) / r.duration();
            loss += r.duration() * edge.damping() * rate * rate;
        }
        double before = energy(links, initial), after = energy(links, r.twist());
        near(before, after + loss, 2e-12 * before + 1e-12, "stored energy plus dissipated work is conserved");
        check(after <= before + 2e-12 * before + 1e-12, "passive springs cannot create energy");
    }

    private static double energy(List<Link> links, double[] twist) {
        double result = 0;
        for (int e = 0; e < links.size(); e++) result += .5 * links.get(e).stiffness() * twist[e] * twist[e];
        return result;
    }

    private static void finite(Result r) {
        check(Double.isFinite(r.duration()) && r.duration() > 0, "finite positive accepted time");
        for (double[] array : new double[][]{r.speed(), r.twist(), r.angle(), r.travel(), r.torque(), r.workTravel()})
            for (double value : array) check(Double.isFinite(value), "no NaN or infinity in accepted state");
        for (int i = 0; i < r.travel().length; i++)
            check(r.travel()[i] >= Math.abs(r.angle()[i]) - 8 * Math.ulp(r.travel()[i]), "absolute travel covers net displacement");
    }

    private static void expectFailure(Runnable action, String diagnostic, String label) {
        try { action.run(); }
        catch (RuntimeException failure) {
            check(failure.getMessage() != null && failure.getMessage().contains(diagnostic), label + ": " + failure);
            rejected++; return;
        }
        throw new AssertionError(label + ": unexpectedly accepted");
    }

    private static void near(double expected, double actual, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            label + ": expected=" + expected + ", actual=" + actual + ", tolerance=" + tolerance);
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
