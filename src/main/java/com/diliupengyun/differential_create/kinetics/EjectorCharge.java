package com.diliupengyun.differential_create.kinetics;

/** Paid winding work in local RPM-ticks, independent of the current target's percentage. */
public final class EjectorCharge {
    public static final String KEY = "DCEjectorChargeTravel";
    private double travel;

    public static double required(int horizontal, int vertical) {
        double distance = Math.hypot(horizontal, vertical);
        return PhysicsTuning.EJECTOR_TRAVEL_PER_DISTANCE * (distance == 0 ? 1 : distance);
    }
    public double travel() { return travel; }
    public double remaining(double required) { return Math.max(0, required - travel); }
    public void restore(double value, double required) {
        travel = Double.isFinite(value) ? Math.clamp(value, 0, required) : 0;
    }
    /** A cheaper target dissipates the excess; it cannot become credit for another shot. */
    public void retarget(double required) { travel = Math.min(travel, required); }
    public void advance(double amount, double required) {
        if (!Double.isFinite(amount) || amount < 0) return;
        travel += Math.min(amount, remaining(required));
        if (remaining(required) <= MachineWork.EPSILON) travel = required;
    }
    public void consume() { travel = 0; }
}
