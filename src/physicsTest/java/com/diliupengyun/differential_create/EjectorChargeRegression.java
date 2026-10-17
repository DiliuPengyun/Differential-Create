package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.EjectorCharge;

public final class EjectorChargeRegression {
    private static int checks;
    public static void run() {
        near(1280, EjectorCharge.required(3, 4), "native distance calibration");
        near(EjectorCharge.required(3, 4), EjectorCharge.required(3, -4), "height sign preserves native winding budget");
        near(256, EjectorCharge.required(0, 0), "unset target still requires winding");
        for (int pieces : new int[]{1, 2, 4, 16, 128, 1024}) {
            var charge = new EjectorCharge();
            for (int i = 0; i < pieces; i++) charge.advance(333.25 / pieces, 2048);
            near(333.25, charge.travel(), "same shaft travel earns the same stored work");
            near(1714.75, charge.remaining(2048), "remaining work is independent of segmentation");
        }
        var charge = new EjectorCharge();
        charge.advance(.000001, 2048);
        near(.000001, charge.travel(), "tiny movement is retained without a floor");
        charge.advance(0, 2048);
        near(.000001, charge.travel(), "standstill earns nothing");
        charge.advance(300, 2048);
        double paid = charge.travel();
        charge.retarget(4096);
        near(paid, charge.travel(), "farther target keeps absolute paid work");
        charge.retarget(256);
        near(256, charge.travel(), "cheaper target dissipates excess");
        charge.retarget(4096);
        near(256, charge.travel(), "returning to expensive target cannot recover discarded work");
        charge.advance(Double.MAX_VALUE, 4096);
        near(4096, charge.travel(), "accepted work stops at full charge");
        charge.consume();
        near(0, charge.travel(), "one launch consumes the entire cycle");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            charge.restore(17.125, 2048);
            charge.advance(invalid, 2048);
            near(17.125, charge.travel(), "invalid travel cannot modify stored work");
            charge.restore(invalid, 2048);
            near(0, charge.travel(), "invalid saved work cannot grant a shot");
        }
        charge.restore(17.125, 2048);
        near(17.125, charge.travel(), "fractional work restores exactly");
        charge.restore(4096, 2048);
        near(2048, charge.travel(), "restored work is capped at this cycle's target");
        System.out.println("Ejector charging regression: " + checks + " checks passed");
    }
    private static void near(double expected, double actual, String message) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-10)
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        checks++;
    }
}
