package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.LoadGaugeScale;

/** Instrument calibration, invalid states and finite/infinite endpoint handling. */
public final class LoadGaugeRegression {
    private static int checks;
    public static void main(String[] args) { LoadFactorRegression.run(); run(); }
    public static void run() {
        for (double[] point : new double[][]{{0, 0}, {.25, .2}, {.5, 1d / 3}, {1, .5}, {2, 2d / 3}, {4, .8},
                {Double.MAX_VALUE, 1}, {Double.POSITIVE_INFINITY, 1}}) {
            var reading = value(point[0]);
            check(Math.abs(LoadGaugeScale.position(reading) - point[1]) < 1e-7, "calibrated position " + point[0]);
            check(LoadGaugeScale.available(reading), "finite and positive infinite loads are valid");
        }
        check(LoadGaugeScale.signal(value(0)) == 0, "zero emits zero");
        check(LoadGaugeScale.signal(value(1)) == 8, "equilibrium is centred in redstone range");
        check(LoadGaugeScale.signal(value(Double.POSITIVE_INFINITY)) == 15, "infinity emits full signal");
        float previous = 0;
        for (int i = -320; i <= 308; i++) {
            var reading = value(Math.pow(10, i));
            float position = LoadGaugeScale.position(reading);
            check(position >= previous && position >= 0 && position <= 1, "monotone, bounded scale over double range");
            check(LoadGaugeScale.signal(reading) >= 0 && LoadGaugeScale.signal(reading) <= 15, "bounded signal");
            previous = position;
        }
        for (var reading : new LoadFactor.Reading[]{LoadFactor.Reading.UNDEFINED, LoadFactor.Reading.UNKNOWN,
                LoadFactor.Reading.NO_POWER, new LoadFactor.Reading(LoadFactor.Status.BRAKING, 0),
                value(Double.NaN), value(-1), value(Double.NEGATIVE_INFINITY)}) {
            check(LoadGaugeScale.position(reading) == LoadGaugeScale.REST, "invalid and nonnumeric states park below zero");
            check(LoadGaugeScale.signal(reading) == 0, "parked instruments emit zero");
            check(LoadGaugeScale.color(reading) == LoadGaugeScale.IDLE_COLOR, "parked instruments have neutral colour");
        }
        check(LoadGaugeScale.percent(1).equals("100%"), "equilibrium label");
        check(LoadGaugeScale.percent(2).equals("200%"), "overload is not clamped");
        check(LoadGaugeScale.percent(Double.POSITIVE_INFINITY).equals("∞%"), "infinite label");
        check(LoadGaugeScale.percent(1e20).equals("10000000000000000000000%"), "large finite label does not overflow long");
        System.out.println("Load gauge regression: " + checks + " checks passed");
    }
    private static LoadFactor.Reading value(double fraction) { return new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, fraction); }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
