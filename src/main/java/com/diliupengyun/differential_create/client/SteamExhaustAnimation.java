package com.diliupengyun.differential_create.client;

/** Converts actual bypass flow into a steady particle count, retaining fractional emissions. */
public final class SteamExhaustAnimation {
    private double remainder;

    public int advance(double vent) {
        if (!Double.isFinite(vent) || vent <= 0) {
            remainder = 0;
            return 0;
        }
        // Two jets per tick at full supply; weak supply produces proportionally fewer puffs.
        remainder += 2 * Math.min(1, vent);
        int particles = (int) remainder;
        remainder -= particles;
        return particles;
    }
}
