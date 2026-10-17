package com.diliupengyun.differential_create.kinetics;

/** Native conveyor units, with the travelled arc retained until after event detection. */
public final class ChainConveyorTravel {
    private ChainConveyorTravel() {}

    public static double degrees(double rpm) { return rpm / (1.5 * Math.PI); }
    public static double distance(double rpm) { return Math.abs(rpm) / 360; }
    public static double wrap(double angle) {
        double result = angle % 360;
        return result < 0 ? result + 360 : result;
    }
    /** Distance to the first encounter, including a port at the starting position. */
    public static double toAngle(double start, double target, int direction) {
        return wrap(direction * (target - start));
    }
    public static boolean crosses(double start, double target, double signedArc) {
        return Double.isFinite(signedArc) && signedArc != 0
            && toAngle(start, target, signedArc > 0 ? 1 : -1) <= Math.abs(signedArc);
    }
}
