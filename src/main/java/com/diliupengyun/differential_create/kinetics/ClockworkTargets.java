package com.diliupengyun.differential_create.kinetics;

/** Angles follow the whole game-time phase, including fractions of hours and minutes.
 * The caller retains Create's dimension time scale and bearing orientation. */
public final class ClockworkTargets {
    private ClockworkTargets() {}
    public static float hour(long dayTime, boolean cycle24, int direction) {
        int period = cycle24 ? 24000 : 12000;
        double phase = (Math.floorMod(dayTime, period) + 6000) % period;
        return (float) (-direction * 360 * phase / period);
    }
    public static float minute(long dayTime, int direction) {
        return (float) (-direction * 360d * Math.floorMod(dayTime, 1000) / 1000);
    }
}
