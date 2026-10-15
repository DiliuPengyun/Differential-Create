package com.diliupengyun.differential_create.kinetics;

/** Water equivalent of admitted cylinder work, in mB per game tick.
 * The small filling/leakage allowance keeps finite starting torque at zero RPM. */
public final class SteamDemand {
    public static final double RATED_FLOW = 10;
    public static final double FILLING_RPM = 16;
    private SteamDemand() {}

    public static double flow(double stallTorque, double powerLimit, double rpm,
                              double governor, double governorRange, double ratedPower) {
        if (!Double.isFinite(stallTorque) || !Double.isFinite(powerLimit) || !Double.isFinite(rpm)
            || !Double.isFinite(ratedPower) || !(powerLimit > 0) || !(ratedPower > 0) || stallTorque == 0) return 0;
        double valve = SteamGovernor.opening(stallTorque, rpm, governor, governorRange);
        // Reversing also takes steam; absorbed shaft energy never refunds water.
        double work = Math.min(powerLimit, Math.abs(stallTorque) * Math.max(FILLING_RPM, Math.abs(rpm)));
        return RATED_FLOW * work / ratedPower * valve;
    }
}
