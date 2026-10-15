package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.SteamDemand;

/** Independent expected water use for pressure, power and governor limits. */
public final class SteamDemandRegression {
    private static int checks;
    private SteamDemandRegression() {}
    public static void run() {
        for (int direction : new int[]{-1, 1}) for (double share : new double[]{.125, .5, 1})
            for (double valve : new double[]{0, .03, .5, 1}) for (double rpm : new double[]{0, 16, 64, 128, 256, 384, 448, 480, 512, 640}) {
                double expected = 10 * valve * Math.min(share, Math.max(16, rpm) / 256)
                    * Math.clamp((512 - rpm) / 64, 0, 1);
                near(expected, SteamDemand.flow(direction * 1024 * valve, 262144 * share * valve,
                    direction * rpm, 512, 64, 262144), "water follows admitted cylinder work");
            }
        near(10, SteamDemand.flow(1024, 262144, -640, 512, 64, 262144), "reversal does not refund water or overdraw the rating");
        near(0, SteamDemand.flow(0, 262144, 128, 512, 64, 262144), "unpowered source uses no water");
        near(0, SteamDemand.flow(1024, 0, 128, 512, 64, 262144), "no supply uses no water");
        near(0, SteamDemand.flow(1024, 262144, Double.NaN, 512, 64, 262144), "invalid rotor cannot debit inventory");
        System.out.println("Steam demand regression: " + checks + " checks passed");
    }
    private static void near(double expected, double actual, String message) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-10)
            throw new AssertionError(message + ": " + expected + " != " + actual);
    }
}
