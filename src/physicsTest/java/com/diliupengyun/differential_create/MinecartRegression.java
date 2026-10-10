package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.*;

/** Source-independent native recurrences, positive-work limits and coupling invariants. */
public final class MinecartRegression {
    private static int checks;
    private static void near(double expected, double actual, double tolerance, String label) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static void run() {
        for (double initial : new double[]{-1.5, -.4, -.01, 0, .01, .4, 1.5})
            for (double factor : new double[]{.96, .997, .98F, .995F, .5, .96 * .95F}) {
                double expected = initial, actual = initial;
                for (int tick = 0; tick < 100; tick++) {
                    expected *= factor;
                    actual = MinecartMotion.coast(actual, factor, MinecartMotion.BASE_INERTIA, 0);
                    near(expected, actual, 0, "reference cart preserves native coasting at every tick");
                }
            }
        for (int sign : new int[]{-1, 1}) for (double initial : new double[]{0, .02, .2, .4, 1.5}) {
            double expected = sign * initial, actual = expected;
            for (int tick = 0; tick < 64; tick++) {
                expected = .8 * expected + sign;
                actual = MinecartMotion.affine(actual, .8, sign, MinecartMotion.BASE_INERTIA);
                near(expected, actual, 0, "unassembled furnace follows its native motor recurrence");
                expected *= .96; actual = MinecartMotion.coast(actual, .96, MinecartMotion.BASE_INERTIA, 0);
            }
        }
        double block = ContraptionGeometry.CUBE_MASS * Math.pow(ContraptionGeometry.LINEAR_RADIUS, 2);
        for (int sign : new int[]{-1, 1})
            near(sign * 2.06, MinecartMotion.impulse(sign * 2, sign * .06, MinecartMotion.BASE_INERTIA,
                MinecartMotion.RAIL_POWER), 1e-15, "rail work budget covers the entire native speed increment");
        for (int blocks : new int[]{0, 1, 120, 1000}) for (int sign : new int[]{-1, 1}) {
            double j = MinecartMotion.BASE_INERTIA + blocks * block;
            double attached = MinecartMotion.attachedSpeed(sign * .4, MinecartMotion.BASE_INERTIA, j);
            near(MinecartMotion.BASE_INERTIA * sign * .4, j * attached, 1e-18, "resting structure conserves cart momentum on attachment");
            check(MinecartMotion.energy(j, attached) <= MinecartMotion.energy(MinecartMotion.BASE_INERTIA, .4), "assembly is passive");
            near(attached, MinecartMotion.attachedSpeed(attached, j, MinecartMotion.BASE_INERTIA), 0, "disassembly carries away its own kinetic energy");
            double coast = MinecartMotion.coast(sign * .4, .96, j, 1e-8);
            check(coast * sign >= 0 && Math.abs(coast) < .4, "combined native drag and structure air resistance cannot reverse or accelerate");
            double start = MinecartMotion.impulse(0, sign * .06, j, MinecartMotion.RAIL_POWER);
            near(sign * .06 * MinecartMotion.BASE_INERTIA / j, start, 0, "rail impulse is shared with the attached inertia");
            double limited = MinecartMotion.impulse(sign * 100, sign * .06, j, 1);
            check(MinecartMotion.energy(j, limited) - MinecartMotion.energy(j, 100) <= RotationMath.TICK_MINUTES + 1e-7,
                "positive rail work is bounded even beyond native speeds");
        }
        for (double a : new double[]{.01, .1, 1}) for (double b : new double[]{.01, .1, 1})
            for (double wa : new double[]{-.4, 0, .4}) for (double wb : new double[]{-.4, 0, .4})
                for (double extension : new double[]{-.25, 0, .25}) {
                    var result = MinecartMotion.couple(wa, wb, a, b, 1, 1, extension, true, true);
                    near(a * wa + b * wb, a * result.first() + b * result.second(), 1e-15, "coupling transfers equal and opposite momentum");
                    double k = MinecartMotion.BASE_INERTIA / Math.pow(RotationMath.TICK_MINUTES, 2);
                    double before = MinecartMotion.energy(a, wa) + MinecartMotion.energy(b, wb)
                        + .5 * k * Math.pow(extension / MinecartMotion.BLOCKS_PER_TURN, 2);
                    double after = MinecartMotion.energy(a, result.first()) + MinecartMotion.energy(b, result.second())
                        + .5 * k * Math.pow(result.extension() / MinecartMotion.BLOCKS_PER_TURN, 2);
                    near(before, after + result.loss(), 1e-10, "spring energy and coupled kinetic energy pay the exact damper loss");
                }
        var fixed = MinecartMotion.couple(.4, 0, .01, .01, 1, 1, .1, true, false);
        near(0, fixed.second(), 0, "stalled coupling end cannot receive motion");
        var frozen = MinecartMotion.couple(0, .4, .01, .01, 1, 1, 0, true, false);
        near(0, frozen.first(), 0, "stored velocity on a frozen end cannot drive its moving neighbour");
        near(.4, frozen.second(), 0, "freezing retains the end's stored velocity without advancing it");
        check(Math.abs(MinecartMotion.target(0, .4, .1)) < Math.abs(MinecartMotion.target(0, .4, .01)), "loaded controller rail needs more time to reach its target");
        for (double j : new double[]{.01, .1, 1}) for (double speed : new double[]{0, 1, 64, 512})
            for (double torque : new double[]{0, 2, 25, 1000}) for (double boundary : new double[]{.1, 128, Double.POSITIVE_INFINITY}) {
                var cut = MinecartMotion.cut(speed, j, torque, boundary, RotationMath.TICK_MINUTES);
                near(.5 * j * speed * speed, .5 * j * cut.speed() * cut.speed() + torque * cut.travel() / 1200,
                    1e-9, "cart cutting pays exactly for its integrated feed");
                check(cut.travel() >= 0 && cut.travel() <= boundary && cut.speed() >= 0
                    && cut.minutes() <= RotationMath.TICK_MINUTES, "cutting stops at the target boundary or empty kinetic energy");
            }
        System.out.println("Minecart regression: " + checks + " checks passed");
    }
}
