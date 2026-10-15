package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.fluids.tank.BoilerData;

/** Shared, paid cylinder supply for stationary and mounted boilers. */
public interface BoilerAdmission {
    String KEY = "DCTrainAdmission";
    String DEMAND_KEY = "DCSteamDemand";
    String FRACTION_KEY = "DCSteamWaterFraction";
    double differentialcreate$admission();
    void differentialcreate$admission(double opening);
    double differentialcreate$demand();
    double differentialcreate$waterFraction();
    void differentialcreate$supply(double demand, double fraction);
    SteamBoilerWater.State differentialcreate$waterState();

    static double opening(BoilerData boiler) {
        return boiler instanceof BoilerAdmission admission ? admission.differentialcreate$admission() : 1;
    }
    static void set(BoilerData boiler, double opening) {
        if (boiler instanceof BoilerAdmission admission)
            admission.differentialcreate$admission(Double.isFinite(opening) ? Math.clamp(opening, 0, 1) : 0);
    }
    static double demand(BoilerData boiler) {
        return ((BoilerAdmission) boiler).differentialcreate$demand();
    }
    static double fraction(BoilerData boiler) {
        return ((BoilerAdmission) boiler).differentialcreate$waterFraction();
    }
    static void supply(BoilerData boiler, double demand, double delivered) {
        boiler.waterSupply = (float) Math.max(0, delivered);
        ((BoilerAdmission) boiler).differentialcreate$supply(demand,
            demand > 0 ? Math.clamp(delivered / demand, 0, 1) : 0);
    }
}
