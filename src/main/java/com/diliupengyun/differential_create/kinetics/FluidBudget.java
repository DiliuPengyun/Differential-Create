package com.diliupengyun.differential_create.kinetics;

/** Common finite-inventory event for several ports sharing an integer capability. */
public final class FluidBudget {
    // A normalized front belongs to a connection of up to one metre. Its position
    // arithmetic sets the precision of water handed to an integer capability.
    private static final double OWNERSHIP_SCALE = PipeFilling.MB_PER_METRE;
    private FluidBudget() {}
    public static double needed(double[] rates, double[] owned, double minutes) {
        double total = 0;
        for (int i = 0; i < rates.length; i++) total += Math.max(0, rates[i] * minutes - owned[i]);
        return total;
    }
    public static double limit(double[] rates, double[] owned, double available, double minutes) {
        if (needed(rates, owned, minutes) <= available) return minutes;
        if (rates.length == 1) return Math.min(minutes, (available + owned[0]) / rates[0]);
        double low = 0, high = minutes;
        for (int i = 0; i < 64; i++) {
            double middle = low + (high - low) * .5;
            if (needed(rates, owned, middle) <= available) low = middle; else high = middle;
        }
        return low;
    }
    /** Pending receipts already occupy the receiver's available capacity. Coalesce
     * only subtraction roundoff at that capacity event; retain every owned portion. */
    public static double receiptSpace(double capacity, double pending) {
        double remaining = capacity - pending;
        return remaining <= 32 * Math.ulp(Math.max(OWNERSHIP_SCALE, Math.max(capacity, pending))) ? 0 : remaining;
    }
    /** Complete an integer receipt at the same roundoff boundary, so an open-ended
     * native handler can consume its last mB and reopen. No minimum-rate delivery. */
    public static int deliveryAmount(double pending) {
        double nearest = Math.rint(pending);
        double whole = nearest > pending && receiptSpace(nearest, pending) == 0 ? nearest : Math.floor(pending);
        return (int) Math.min(Integer.MAX_VALUE, whole);
    }
}
