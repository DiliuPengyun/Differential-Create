package com.diliupengyun.differential_create.kinetics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A bounded instrument scale, not a modification of the physical load factor. */
public final class LoadGaugeScale {
    public static final float REST = -.16f;
    public static final int IDLE_COLOR = 0x888888;
    private LoadGaugeScale() {}

    public static boolean available(LoadFactor.Reading reading) {
        return reading.status() == LoadFactor.Status.AVAILABLE && reading.fraction() >= 0
            && !Double.isNaN(reading.fraction());
    }

    /** 0 -> 0, equilibrium -> 1/2, infinity -> 1. Avoid infinity / infinity. */
    public static float position(LoadFactor.Reading reading) {
        if (!available(reading)) return REST;
        double load = reading.fraction();
        return (float) (load <= 1 ? load / (1 + load) : 1 / (1 + 1 / load));
    }

    /** Redstone has no invalid value; a parked instrument emits no signal. */
    public static int signal(LoadFactor.Reading reading) {
        return available(reading) ? (int) Math.ceil(15 * (double) position(reading)) : 0;
    }

    public static int color(LoadFactor.Reading reading) {
        if (!available(reading)) return IDLE_COLOR;
        long percent = Math.round(reading.fraction() * 100);
        return percent > 100 ? 0xFF5555 : percent < 90 ? 0x55FF55 : 0xFFFF55;
    }

    public static String percent(double load) {
        if (load == Double.POSITIVE_INFINITY) return "∞%";
        // Large finite loads must not overflow the percentage's integer conversion.
        return BigDecimal.valueOf(load).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
