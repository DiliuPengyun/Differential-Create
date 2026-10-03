package com.diliupengyun.differential_create.kinetics;

/** Common finite-inventory event for several ports sharing an integer capability. */
public final class FluidBudget {
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
}
