package com.diliupengyun.differential_create;

import java.util.ArrayList;
import com.diliupengyun.differential_create.tests.TrainDynamicsOracle;
import com.diliupengyun.differential_create.trains.TrainMechanics;

/** Ratios from impulse, work and reflected inertia, rather than monotonicity. */
public final class TrainQuantitativeRegression {
    private static final double TORQUE = 1024 * 16, POWER = TORQUE * 256 * Math.PI / 30;
    private static final double MASS = 25_000, J = 32;
    private static int checks;

    private TrainQuantitativeRegression() {}

    public static void main(String[] args) { run(); }

    public static void run() {
        for (int direction : new int[]{-1, 1}) for (double radius : new double[]{.25, .5}) {
            double signedRadius = direction * radius, grade = -direction * .008 / 9.81;
            for (int count : new int[]{1, 2, 4}) {
                double effectiveMass = MASS + count * J / (radius * radius);
                double lowReference = acceleration(count, 1, signedRadius, grade);
                for (double level : new double[]{.125, .5, 1, 2, 4, 8}) {
                    // All these starts remain below even the smallest power knee.
                    near(1, acceleration(count, level, signedRadius, grade) / lowReference, 1e-8,
                        "boiler levels have 1:1 starting acceleration below the power knee");
                    double gain = workGain(count, level, signedRadius, grade);
                    double expected = 2 * POWER * Math.min(level, count) * .01 / effectiveMass;
                    near(expected, gain, Math.abs(expected) * 3e-5,
                        "squared-speed gain equals 2 * available work / effective mass");
                    double referenceGain = workGain(count, .125, signedRadius, grade);
                    near(Math.min(level, count) / .125, gain / referenceGain, 4e-4,
                        "power-limited squared-speed gains have the specified boiler-level ratio");
                }
                double startExpected = count * TORQUE / radius / effectiveMass;
                near(startExpected, lowReference, startExpected * 1e-8, "starting impulse includes every rotor's inertia");
                double timed = workGain(count, count, signedRadius, grade, .05);
                near(2, workGain(count, count, signedRadius, grade, .1) / timed, 3e-5,
                    "twice the powered time gives twice the squared-speed gain");
                near(4, workGain(count, count, signedRadius, grade, .2) / timed, 4e-5,
                    "four times the powered time gives four times the squared-speed gain");
            }
            double one = acceleration(1, 1, signedRadius, grade);
            for (int count : new int[]{2, 4}) {
                double inertiaRatio = (MASS + J / (radius * radius)) / (MASS + count * J / (radius * radius));
                near(count * inertiaRatio, acceleration(count, 1, signedRadius, grade) / one, 1e-8,
                    "N powered bogeys multiply starting force with the calculated inertia correction");
                near(count * inertiaRatio, workGain(count, count, signedRadius, grade)
                    / workGain(1, 1, signedRadius, grade), 8e-5,
                    "sufficient steam multiplies work gain by N with the inertia correction");
                near(inertiaRatio, workGain(count, 1, signedRadius, grade)
                    / workGain(1, 1, signedRadius, grade), 3e-5,
                    "one shared boiler level has 1:1 total power, with only inertia changing");
            }
        }
        for (double grade : new double[]{-.125, 0, .125}) for (double brake : new double[]{0, 1}) {
            for (int count : new int[]{1, 2, 4}) {
                var engines = engines(count, 4, 1, 10);
                engines.forEach(e -> { e.stallTorque = 0; e.power = 0; });
                var setup = new TrainDynamicsOracle.Setup(MASS, .55 * 12, grade, count, 1, 0, 0, brake);
                double expected = TrainDynamicsOracle.advance(setup, 10, .05).speed();
                double measured = TrainMechanics.advance(10, MASS, .55 * 12, grade, brake, engines, .05).speed();
                near(expected, measured, 2e-5, "coasting/braking across engine counts follows force divided by effective mass");
            }
        }
        System.out.println("Train quantitative regression: " + checks + " checks passed");
    }

    private static double acceleration(int count, double level, double radius, double grade) {
        double speed = Math.copySign(.01, radius);
        var engines = engines(count, level, radius, speed);
        // Settle the substep clutch constraints without crossing the power knee.
        for (int i = 0; i < 2; i++) speed = TrainMechanics.advance(speed, MASS, 0, grade, 0, engines, .0025).speed();
        double before = speed;
        double after = TrainMechanics.advance(speed, MASS, 0, grade, 0, engines, .0025).speed();
        return Math.copySign(1, radius) * (after - before) / .0025;
    }

    private static double workGain(int count, double level, double radius, double grade) {
        return workGain(count, level, radius, grade, .01);
    }

    private static double workGain(int count, double level, double radius, double grade, double seconds) {
        // 384 input RPM is above the 256 RPM power knee and below regulation.
        double speed = radius * 384 * Math.PI / 30;
        var engines = engines(count, level, radius, speed);
        // Refine the force/power integration for the analytical work ratio;
        // the native matrix separately checks the ordinary 64-substep tick.
        double after = speed;
        int steps = (int) Math.ceil(seconds / (.01 / 4096));
        for (int i = 0; i < steps; i++) after = TrainMechanics.advance(after, MASS, 0, grade, 0, engines, seconds / steps).speed();
        for (var engine : engines) near(0, Math.max(0, Math.abs(engine.rpm()) - 448), 0,
            "work-ratio sample remains below governor regulation");
        return after * after - speed * speed;
    }

    private static ArrayList<TrainMechanics.Engine> engines(int count, double level, double radius, double speed) {
        var engines = new ArrayList<TrainMechanics.Engine>();
        for (int i = 0; i < count; i++) {
            var engine = new TrainMechanics.Engine();
            engine.ratio = radius / .5; engine.omega = speed / radius;
            engine.stallTorque = TORQUE; engine.power = POWER * Math.min(1, level / count);
            engines.add(engine);
        }
        return engines;
    }

    private static void near(double expected, double actual, double tolerance, String message) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
    }
}
