package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.BacktankCharge;

/** Independent displacement, capacity and prepaid-work accounting checks. */
public final class BacktankChargeRegression {
    private static int checks;
    public static void main(String[] args) { run(); }
    public static void run() {
        for (int pieces : new int[]{1, 2, 4, 16, 128, 1024}) {
            var charge = new BacktankCharge();
            int air = 0;
            for (int i = 0; i < pieces; i++) air = charge.advance(air, 900, 320d / pieces);
            check(air == 2, "segmented shaft work earns two whole air units");
            near(.5, charge.fraction(), "segmented shaft work retains its half unit");
        }
        for (double speed : new double[]{.001, .5, 1, 64, 128, 256, 8192}) {
            var charge = new BacktankCharge();
            int air = charge.advance(0, 100000, speed);
            near(speed / 128, air + charge.fraction(), "one tick of prescribed travel has no low-speed floor or high-speed cap");
        }
        var charge = new BacktankCharge();
        int air = charge.advance(10, 900, 32);
        near(.25, charge.fraction(), "fractional work is retained");
        air = charge.advance(air, 900, 0);
        check(air == 10, "standstill earns no whole air");
        near(.25, charge.fraction(), "standstill retains paid work");
        var reloaded = new BacktankCharge();
        reloaded.restore(charge.fraction());
        check(reloaded.advance(air, 900, 96) == 11, "restored work completes exactly once");
        near(0, reloaded.fraction(), "integer completion leaves no duplicate credit");
        charge.restore(.75);
        near(32, charge.remainingTravel(899, 900), "full-tank event uses only unpaid work");
        check(charge.advance(899, 900, 32) == 900, "exact full boundary");
        near(0, charge.fraction(), "full tank carries no credit for the next charge");
        check(charge.advance(900, 900, 1e100) == 900, "full tank cannot accumulate extra air");
        near(0, charge.fraction(), "full tank cannot accumulate work");
        check(charge.advance(899, 900, Double.MAX_VALUE) == 900, "oversized travel is bounded by remaining capacity");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 1, 2}) {
            charge.restore(invalid);
            near(0, charge.fraction(), "invalid saved fractions do not invent air");
        }
        charge.restore(.5);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            check(charge.advance(10, 900, invalid) == 10, "invalid travel earns no air");
            near(.5, charge.fraction(), "invalid travel leaves paid progress unchanged");
        }
        System.out.println("Backtank charging regression: " + checks + " checks passed");
    }
    private static void near(double expected, double actual, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-10, message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
