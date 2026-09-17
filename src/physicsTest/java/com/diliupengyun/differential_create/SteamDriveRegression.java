package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.DriveTorque;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;

/** Independent work/impulse expectations for the lossless, finite-torque steam drive. */
public final class SteamDriveRegression {
    private static int checks;
    private static final double DT = 1.0 / 1200, H = DT / 64;
    private static final double TORQUE = 1024, POWER = 262144, RATED = 256, J = .1;

    public static void run() {
        for (int sign : new int[]{-1, 1}) {
            for (double speed : new double[]{0, .001, 128, RATED, 8192, 73728, 1e7}) {
                double torque = DriveTorque.torque(sign * TORQUE, POWER, sign * speed);
                check(sign * torque > 0 && Math.abs(torque) <= TORQUE, "finite forward drive without overspeed braking");
                check(torque * sign * speed <= POWER + 1e-8, "positive shaft power never exceeds its steam budget");
                near(0, DriveTorque.torque(0, 0, sign * speed), 0, "disconnected supply has no hidden damping");
            }
            var body = List.of(new Body(J, sign * TORQUE, 0, 0, 0, POWER));
            double startupDuration = J * RATED / (2 * TORQUE);
            var low = isolated(body, 0, startupDuration, H);
            near(sign * TORQUE * startupDuration / J, low.speed()[0], 1e-8, "constant-pressure startup impulse");
            near(sign * .5 * TORQUE / J * startupDuration * startupDuration, low.angle()[0], 1e-9, "startup work and travel");

            var high = isolated(body, sign * 8192, .05, H);
            near(POWER * .05, .5 * J * (high.speed()[0] * high.speed()[0] - 8192.0 * 8192), 1e-4,
                "isolated constant-power acceleration credits exactly the available energy");
            near(sign * Math.sqrt(8192.0 * 8192 + 2 * POWER * .05 / J), high.speed()[0], 1e-6,
                "high-speed rotor follows square-root growth, with no finite no-load speed");

            var crossing = isolated(body, sign * (RATED - .1), DT, H);
            double timeToRated = J * .1 / TORQUE;
            double crossingSpeed = sign * Math.sqrt(RATED * RATED + 2 * POWER * (DT - timeToRated) / J);
            // A substep straddling the torque-curve kink has O((T*H/J)^2/RATED) speed error.
            double crossingTolerance = Math.pow(TORQUE * H / J, 2) / (8 * RATED) + 1e-8;
            near(crossingSpeed, crossing.speed()[0], crossingTolerance,
                "constant-torque to constant-power transition is continuous");
            near(crossingSpeed, isolated(body, sign * (RATED - .1), DT, H / 8).speed()[0], 1e-7,
                "refined substeps resolve the torque-curve transition more accurately");
            var reverse = isolated(body, -sign * 8, .025, H);
            near(sign * (TORQUE * .025 / J - 8), reverse.speed()[0], 1e-7, "commanded reversal crosses zero with finite torque");
        }

        var stalled = isolated(List.of(new Body(J, TORQUE, 0, 2048, 0, POWER)), 0, .01, H);
        near(0, stalled.speed()[0], 0, "overload holds an engine at rest without dividing by zero");
        var balanced = isolated(List.of(new Body(J, TORQUE, 0, 32, 0, POWER)), 8192, .05, H);
        near(8192, balanced.speed()[0], 1e-7, "external load determines the equilibrium above rated speed");
        var coast = isolated(List.of(new Body(J, 0, 0, 0, 0, 0)), 10000, .05, H);
        near(10000, coast.speed()[0], 0, "no supply and no load preserves speed");

        // A full one-minute run from rest, including both force regimes. The pure solver
        // skips no physical time; a coarser step is checked against the analytic work budget.
        var natural = isolated(List.of(new Body(J, TORQUE, 0, 0, 0, POWER)), 0, 1, DT);
        double accelerationTime = J * RATED / TORQUE;
        near(Math.sqrt(RATED * RATED + 2 * POWER * (1 - accelerationTime) / J), natural.speed()[0], 1e-5,
            "natural startup reaches and passes rated speed without a speed assignment");

        // The same nonlinear drive must also work through elastic links and signed gearing.
        for (double ratio : new double[]{1, -2}) {
            var bodies = List.of(new Body(J, TORQUE, 0, 0, 0, POWER), new Body(.2, 0, 0, 0, 0));
            var links = List.of(new Link(0, 1, ratio, 1e7, 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
            double[] initial = {8192, ratio * 8192};
            var result = TorsionSolver.advance(bodies, links, initial, new double[1], .01, H);
            double before = energy(bodies, initial), after = energy(bodies, result.speed()) + .5e7 * result.twist()[0] * result.twist()[0];
            near(POWER * .01, after - before, 1e-3, "geared network kinetic plus spring energy equals steam work");
            check(result.speed()[0] > initial[0], "coupled steam drive accelerates above its rated speed");
        }
        System.out.println("Steam drive regression: " + checks + " checks passed");
    }

    private static TorsionSolver.Result isolated(List<Body> bodies, double speed, double duration, double step) {
        return TorsionSolver.advance(bodies, List.of(), new double[]{speed}, new double[0], duration, step);
    }
    private static double energy(List<Body> bodies, double[] speed) {
        double result = 0;
        for (int i = 0; i < speed.length; i++) result += .5 * bodies.get(i).inertia() * speed[i] * speed[i];
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
