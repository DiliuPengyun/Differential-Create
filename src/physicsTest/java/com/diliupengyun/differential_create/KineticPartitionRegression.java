package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.*;

/** Supplementary partitions for drive laws, saved work, phase graphs and shared event time. */
public final class KineticPartitionRegression {
    private static int cases, checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void near(double expected, double actual, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": " + actual + " != " + expected);
    }
    public static void run() {
        drives(); work(); phases(); boundaries(); loadReadings();
        System.out.println("Kinetic partitions: " + cases + " cases, " + checks + " checks passed");
    }
    private static void drives() {
        for (int sign : new int[]{-1, 1}) for (double stall : new double[]{0, 1, 1024})
            for (double power : new double[]{0, 1, 1024, Double.POSITIVE_INFINITY})
                for (double speed : new double[]{-2048, -1, 0, Math.nextDown(1d), 1, Math.nextUp(1d), 256, 1024, 2048}) {
                    cases++;
                    double t = DriveTorque.torque(sign * stall, power, sign * speed);
                    check(Math.abs(t) <= stall && t * sign >= 0, "cylinder pressure has finite commanded torque");
                    check(t * sign * speed <= power, "positive mechanical work obeys supplied power");
                    near(-t, DriveTorque.torque(-sign * stall, power, -sign * speed), 0, "drive direction symmetry");
                    if (stall > 0 && power > 0 && sign * stall * sign * speed > power * 1.01) {
                        double w = sign * speed, delta = Math.abs(w) * 1e-5;
                        double derivative = (DriveTorque.torque(sign * stall, power, w + delta)
                            - DriveTorque.torque(sign * stall, power, w - delta)) / (2 * delta);
                        near(-derivative, DriveTorque.slope(sign * stall, power, w), 1e-6 * (1 + Math.abs(derivative)), "nonlinear solve uses actual force derivative");
                    }
                }
        for (double drive : new double[]{-1024, 0, 1024}) for (double limit : new double[]{0, 1, 512, Double.POSITIVE_INFINITY})
            for (double range : new double[]{-1, 0, 1, 64, 1024})
                for (double speed : new double[]{-2048, -513, -512, -448, -1, 0, 1, 448, 512, 513, 2048}) {
                    cases++;
                    double valve = SteamGovernor.opening(drive, speed, limit, range);
                    check(valve >= 0 && valve <= 1, "governor admission stays bounded");
                    near(valve, SteamGovernor.opening(-drive, -speed, limit, range), 0, "governor direction symmetry");
                    if (!(range > 0) || !Double.isFinite(limit)) near(1, valve, 0, "disabled governor remains open");
                    else if (drive != 0) {
                        double next = SteamGovernor.opening(drive, speed + Math.signum(drive) * .01, limit, range);
                        check(next <= valve, "forward overspeed cannot reopen admission");
                        double w = Math.signum(drive) * speed;
                        if (w > limit - range + .1 && w < limit - .1) {
                            double derivative = Math.abs((SteamGovernor.opening(drive, speed + .01, limit, range)
                                - SteamGovernor.opening(drive, speed - .01, limit, range)) / .02);
                            near(derivative, SteamGovernor.slope(drive, speed, limit, range), 1e-10, "governor derivative inside its regulation band");
                        }
                    }
                }
    }
    private static void work() {
        for (double saved : new double[]{-3, -1, -.5, 0, .5, Math.nextDown(1d), 1, Math.nextUp(1d), 2e10})
            for (double units : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1, 0, 1d / 1024, .5, 1, 2e10}) {
                cases++; var counter = new WorkAccumulator(); counter.restore(saved);
                int result = counter.advance(units);
                if (!(units > 0) || !Double.isFinite(units)) {
                    check(result == 0 && counter.remainder() == saved, "invalid or stopped work cannot alter a saved debt");
                } else {
                    check(result >= 0, "work never issues a negative native counter increment");
                    near(saved + units, result + counter.remainder(), Math.ulp(saved + units) * 2, "integer work and debt conserve earned travel");
                }
            }
        for (int seed = 0; seed < 128; seed++) {
            cases++; var random = new Random(seed); var counter = new WorkAccumulator();
            long earned = -random.nextInt(8192), emitted = 0; counter.restore(earned / 1024d);
            for (int operation = 0; operation < 512; operation++) {
                int travel = random.nextInt(4096);
                if (operation % 17 == 0) { counter.borrowOne(); earned -= 1024; }
                earned += travel; emitted += counter.advance(travel / 1024d);
                near(earned / 1024d, emitted + counter.remainder(), 0, "binary exact debt ledger across random work and contact advances");
                if (operation % 13 == 0) { var replacement = new WorkAccumulator(); replacement.restore(counter.remainder()); counter = replacement; }
            }
        }
        cases++; var huge = new WorkAccumulator(); huge.restore(Double.MAX_VALUE);
        check(huge.advance(Double.MAX_VALUE) == 0 && huge.remainder() == Double.MAX_VALUE, "overflow rejects the new increment without granting infinite work");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            huge.restore(invalid); near(0, huge.remainder(), 0, "nonfinite saved work restores an empty ledger");
        }
    }
    private static void phases() {
        for (int count : new int[]{1, 2, 8, 64}) for (int seed = 0; seed < 128; seed++) {
            cases++; var random = new Random(seed * 71L + count);
            double[] expected = new double[count], input = new double[count];
            for (int i = 0; i < count; i++) { expected[i] = random.nextDouble() * 200 - 100; input[i] = random.nextDouble(); }
            input[0] = expected[0]; int split = count / 2;
            if (split > 0) input[split] = expected[split];
            var links = new ArrayList<ShaftPhases.Contact>();
            for (int i = 1; i < count; i++) {
                if (i == split) continue;
                int parent = i < split ? random.nextInt(i) : split + random.nextInt(i - split);
                double q = new double[]{-2, -.5, .5, 2}[random.nextInt(4)];
                links.add(new ShaftPhases.Contact(parent, i, q, q * expected[parent] - expected[i]));
            }
            for (int i = 2; i < count; i++) {
                if (i == split) continue;
                int parent = i < split ? 0 : split;
                links.add(new ShaftPhases.Contact(parent, i, 1, expected[parent] - expected[i]));
            }
            var reversed = links.stream().map(c -> new ShaftPhases.Contact(c.b(), c.a(), 1 / c.ratio(), -c.twist() / c.ratio())).toList();
            var normal = ShaftPhases.align(input, links); var other = ShaftPhases.align(input, reversed);
            for (int i = 0; i < count; i++) {
                near(expected[i], normal[i], 1e-10, "consistent cyclic/disconnected phase graph");
                near(normal[i], other[i], 1e-10, "contact orientation cannot change assembly phase");
            }
            check(input[0] == expected[0], "alignment does not mutate input angles");
            var repeated = ShaftPhases.align(normal, links);
            for (int i = 0; i < count; i++) near(normal[i], repeated[i], 1e-10, "phase reconstruction is idempotent");
        }
    }
    private static void boundaries() {
        for (double low : new double[]{0, .000001, .25, 10}) for (double width : new double[]{1e-9, 1e-5, 1, 100})
            for (double fraction : new double[]{0, Math.ulp(1d), .25, .5, .99999, 1}) {
                cases++; double high = low + width, root = low + fraction * width;
                double found = ShaftClock.firstBoundary(low, high, t -> t >= root);
                near(root, found, Math.max(Math.ulp(high) * 4, width / Math.pow(2, 47)), "common clock locates earliest monotone event");
                check(found >= low && found <= high, "event capture stays within physical interval");
                near(low, ShaftClock.firstBoundary(low, high, t -> true), 0, "already reached event is immediate");
                near(high, ShaftClock.firstBoundary(low, high, t -> false), 0, "unreached event uses the full interval");
            }
    }
    private static void loadReadings() {
        for (int seed = 0; seed < 256; seed++) {
            cases++; var random = new Random(seed); var members = new ArrayList<LoadFactor.Member>();
            for (int i = 0; i < 8; i++) members.add(new LoadFactor.Member(random.nextBoolean() ? 2 : -.5,
                seed % 3 == 0 ? 0 : random.nextDouble() * 100 - 50, random.nextDouble() * 1024 - 512,
                random.nextDouble() * 100, seed % 7 != 0));
            double loss = random.nextDouble() * 20;
            var normal = LoadFactor.calculate(members, loss);
            var reverse = LoadFactor.calculate(members.stream().map(m -> new LoadFactor.Member(-m.ratio(), -m.speed(), -m.driveTorque(), m.loadTorque(), m.powered())).toList(), loss);
            check(normal.status() == reverse.status() && normal.fraction() == reverse.fraction(), "load reading cannot depend on world rotation sign");
            for (double reference : new double[]{-16, -.5, .25, 8}) {
                var shifted = LoadFactor.calculate(members.stream().map(m -> new LoadFactor.Member(m.ratio() * reference, m.speed(), m.driveTorque(), m.loadTorque(), m.powered())).toList(), loss);
                check(normal.status() == shifted.status(), "reference choice preserves power classification");
                if (Double.isFinite(normal.fraction())) near(normal.fraction(), shifted.fraction(), 1e-10, "reference choice preserves demand fraction");
            }
        }
        for (double k : new double[]{0, 1, 1e7}) for (double d : new double[]{0, .01, 32, 8192})
            for (double cap : new double[]{0, 1, 1024, Double.POSITIVE_INFINITY})
                for (double x : new double[]{-1, 0, 1e-4, 1}) for (double speed : new double[]{-512, 0, 512}) {
                    cases++; double loss = LoadFactor.contactLoss(k, d, cap, x, speed);
                    check(loss >= 0 && Double.isFinite(loss), "contact loss cannot generate energy");
                    near(loss, LoadFactor.contactLoss(k, d, cap, -x, -speed), 1e-8, "contact dissipation respects direction symmetry");
                }
    }
}
