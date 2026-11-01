package com.diliupengyun.differential_create.kinetics;

/** Both hands advance on the same whole game-minute boundary.
 * The caller retains Create's dimension time scale and bearing orientation. */
public final class ClockworkTargets {
    private ClockworkTargets() {}
    public static float hour(long dayTime, boolean cycle24, int direction) {
        int period = cycle24 ? 24000 : 12000;
        long minutes = Math.floorMod(Math.floorMod(dayTime, period) * 60 / 1000 + 360, period * 60 / 1000);
        return (float) (-direction * 360d * minutes / (period * 60 / 1000));
    }
    public static float minute(long dayTime, int direction) {
        return -direction * 6 * (Math.floorMod(dayTime, 1000) * 60 / 1000);
    }
}
