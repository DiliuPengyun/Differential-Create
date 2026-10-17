package com.diliupengyun.differential_create.kinetics;

/** Common feed coordinate and Create's whole-stack processing calibration. */
public final class CrushingWork {
    private CrushingWork() {}
    public static double speed(double a, double b) { return (a - b) / 2; }
    public static double batchFactor(int count) { return Math.max(1, Math.log(Math.max(1, count)) / Math.log(2)); }
    public static double travelPerProgress(int count) { return 12.5 * batchFactor(count); }
}
