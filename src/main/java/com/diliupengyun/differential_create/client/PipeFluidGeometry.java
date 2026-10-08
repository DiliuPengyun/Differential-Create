package com.diliupengyun.differential_create.client;

/** Presentation of one liquid cell, independent of its actual store.
 * The full-width pool and narrow falling stream have the same combined volume
 * as the original filled interval. Neither geometry feeds back into physics. */
public final class PipeFluidGeometry {
    private PipeFluidGeometry() {}

    public record Column(double poolHeight, double streamRadius) {}

    /** A horizontal pipe is wet along its full length. Inventory changes the
     * layer's height, not the positions of two artificial axial water fronts. */
    public record Layer(double height) {
        public double bottom() { return 5 / 16d; }
        public double top() { return bottom() + height * 6 / 16d; }
        public double centerOffset() { return (height - 1) * 3 / 16d; }
    }

    public static Layer horizontal(double fill) { return new Layer(clampFill(fill)); }

    public static Column vertical(double fill, double incoming, double outgoing) {
        fill = clampFill(fill);
        if (fill == 0 || fill == 1 || !(incoming > 0) || !(outgoing > 0)
            || !Double.isFinite(incoming) || !Double.isFinite(outgoing)) return new Column(fill, 0);

        // Through-flow opens a stream gently. A restricted lower outlet keeps
        // an increasing share of the cell in its visible pool instead.
        double through = Math.min(incoming, outgoing);
        double blend = smooth(through / .5) * smooth(outgoing / Math.max(incoming, outgoing) / .85);
        double pool = fill * (1 - blend);
        double radius = Math.sqrt(Math.max(0, (fill - pool) / (1 - pool)));
        return new Column(pool, radius);
    }

    private static double clampFill(double fill) {
        return Double.isFinite(fill) ? Math.max(0, Math.min(1, fill)) : 0;
    }

    private static double smooth(double value) {
        value = Math.max(0, Math.min(1, value));
        return value * value * (3 - 2 * value);
    }
}
