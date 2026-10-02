package com.diliupengyun.differential_create.kinetics;

/** Admission control in the commanded direction; closing the valve never brakes a rotor. */
public final class SteamGovernor {
    private SteamGovernor() {}

    public static double opening(double drive, double speed, double limit, double range) {
        if (!Double.isFinite(limit) || !(range > 0)) return 1;
        double forwardSpeed = Math.signum(drive) * speed;
        return Math.clamp((limit - forwardSpeed) / range, 0, 1);
    }

    /** Magnitude of the valve-opening derivative inside the regulation band. */
    public static double slope(double drive, double speed, double limit, double range) {
        if (!Double.isFinite(limit) || !(range > 0)) return 0;
        double forwardSpeed = Math.signum(drive) * speed;
        return forwardSpeed > limit - range && forwardSpeed < limit ? 1 / range : 0;
    }
}
