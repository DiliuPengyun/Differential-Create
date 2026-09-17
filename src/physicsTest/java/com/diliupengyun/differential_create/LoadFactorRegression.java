package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.DriveTorque;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.LoadFactor.Member;
import com.diliupengyun.differential_create.kinetics.LoadFactor.Status;

/** Demand/supply expectations from shaft work, independent of UI and Minecraft. */
public final class LoadFactorRegression {
    private static int checks;

    public static void run() {
        for (int sign : new int[]{-1, 1}) {
            for (double speed : new double[]{0, 8, 16})
                fraction(0, List.of(new Member(1, sign * speed, sign * (64 - 4 * speed), 0, true)), 0,
                    "an unloaded accelerating or settled wheel has no sustained load");
            for (double[] point : new double[][]{{0, .125}, {12, .5}, {14, 1}, {15, 2}, {16, Double.POSITIVE_INFINITY}})
                fraction(point[1], List.of(new Member(1, sign * point[0], sign * (64 - 4 * point[0]), 0, true),
                    new Member(1, sign * point[0], 0, 8, false)), 0, "working press demand at current wheel speed");
            fraction(1.5, List.of(new Member(1, 0, sign * 64, 96, true)), 0,
                "a stalled overloaded wheel reports startup deficit, not zero power");

            for (double speed : new double[]{0, 128, 256, 512, 8192}) {
                double torque = DriveTorque.torque(sign * 1024, 262144, sign * speed);
                var engine = new Member(1, sign * speed, torque, 0, true);
                fraction(0, List.of(engine), 0, "lossless steam acceleration is not machine demand");
                fraction(speed <= 256 ? .5 : speed / 512,
                    List.of(engine, new Member(1, sign * speed, 0, 512, false)), 0,
                    "steam demand uses the available torque at this speed, including the constant-power region");
            }
            for (int count : new int[]{1, 2, 18}) {
                var members = new ArrayList<Member>();
                for (int i = 0; i < count; i++) members.add(new Member(1, sign * 512, sign * 512, 0, true));
                members.add(new Member(1, sign * 512, 0, 256, false));
                fraction(.5 / count, members, 0, "parallel engines share the same external load");
            }
        }

        // Signed gearing and arbitrary choices of reference shaft must leave physical work unchanged.
        for (double ratio : new double[]{2, -2, .5, -.5})
            for (double referenceScale : new double[]{1, -1, .125, -8})
                for (double speed : new double[]{0, 12, -12}) {
                    double direction = speed < 0 ? -1 : 1;
                    fraction(Math.abs(ratio) / 2, List.of(
                        new Member(referenceScale, speed, direction * 16, 0, true),
                        new Member(referenceScale * ratio, speed * ratio, 0, 8, false)), 0,
                        "geared torque at standstill agrees with moving shaft power in any reference basis");
                }

        fraction(.75, List.of(new Member(1, 12, 16, 8, true)), 48,
            "transmission dissipation adds to useful machine power");
        fraction(.75, List.of(new Member(1, 12, 16, 0, true), new Member(1, 12, -8, 4, true)), 0,
            "an opposing source absorbs part of the forward sources' output");
        status(Status.BRAKING, List.of(new Member(1, 12, -1024, 0, true)),
            "a reversing engine is braking while torque opposes motion");
        status(Status.NO_POWER, List.of(new Member(1, 12000, 0, 8, false)),
            "coasting stored energy does not masquerade as active supply");
        status(Status.NO_POWER, List.of(new Member(1, 0, 0, 0, false)), "unpowered rest");
        status(Status.UNKNOWN, List.of(), "missing network data");
        status(Status.UNKNOWN, List.of(new Member(1, Double.NaN, 64, 8, true)), "invalid state is not a fabricated percentage");

        // A preloaded spring stores energy but consumes no power while held still.
        near(0, LoadFactor.contactLoss(1e7, 128, Double.POSITIVE_INFINITY, .001, 0), "spring preload is not loss");
        for (double twist : new double[]{-.001, 0, .001})
            near(512, LoadFactor.contactLoss(1e7, 128, Double.POSITIVE_INFINITY, twist, 2),
                "only damping, not spring charging/discharging, is sustained contact demand");
        for (int sign : new int[]{-1, 1}) {
            near(320, LoadFactor.contactLoss(1e7, 128, 32, sign * 32e-7, sign * 10),
                "steady slipping clutch dissipates torque times slip speed");
            near(322.5, LoadFactor.contactLoss(1e7, 128, 32, sign * 40e-7, sign * 10),
                "slip and damping dissipate both transmitted work and released spring energy");
            near(320, LoadFactor.contactLoss(1e7, 0, 32, sign * 40e-7, sign * 10),
                "undamped ideal friction loss");
        }
        near(0, LoadFactor.contactLoss(1e7, 128, 0, .001, 10), "released clutch transmits and dissipates no power");
        System.out.println("Load factor regression: " + checks + " checks passed");
    }

    private static void fraction(double expected, List<Member> members, double loss, String label) {
        var reading = LoadFactor.calculate(members, loss);
        check(reading.status() == Status.AVAILABLE, label + ": " + reading.status());
        if (Double.isInfinite(expected)) check(reading.fraction() == expected, label + ": infinite deficit");
        else near(expected, reading.fraction(), label);
    }
    private static void status(Status expected, List<Member> members, String label) {
        check(LoadFactor.calculate(members, 0).status() == expected, label);
    }
    private static void near(double expected, double actual, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= 1e-10,
            label + ": expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
