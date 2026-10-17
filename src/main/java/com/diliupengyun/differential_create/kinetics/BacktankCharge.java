package com.diliupengyun.differential_create.kinetics;

/** Fractional compressor work. 128 local RPM-ticks earn one unit of air. */
public final class BacktankCharge {
    private final WorkAccumulator work = new WorkAccumulator();

    public double fraction() { return work.remainder(); }
    public void restore(double fraction) {
        work.restore(Double.isFinite(fraction) && fraction >= 0 && fraction < 1 ? fraction : 0);
    }
    public void reset() { work.reset(); }

    public double remainingTravel(int air, int capacity) {
        return Math.max(0, (double) capacity - air - fraction()) * PhysicsTuning.BACKTANK_TRAVEL_PER_AIR;
    }

    /** The scheduler locates the full-tank boundary; only its accepted travel earns air. */
    public int advance(int air, int capacity, double travel) {
        if (!Double.isFinite(travel) || travel < 0) return air;
        if (air >= capacity) { reset(); return air; }
        double remaining = remainingTravel(air, capacity);
        boolean full = travel + MachineWork.EPSILON >= remaining;
        int earned = work.advance(Math.min(travel, remaining) / PhysicsTuning.BACKTANK_TRAVEL_PER_AIR);
        int next = air + Math.min(earned, capacity - air);
        if (full || next >= capacity) { reset(); return capacity; }
        return next;
    }
}
