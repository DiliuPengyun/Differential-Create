package com.diliupengyun.differential_create.kinetics;

/** Symmetric octaves around 1x. Storage remains signed sixteenths. */
public final class RatioScale {
    public static final int CENTER = 40, MAX = 80, OCTAVE = 10;
    private RatioScale() {}
    public static int raw(int column) {
        return Math.clamp((int) Math.round(16 * Math.pow(2, (Math.clamp(column, 0, MAX) - CENTER) / (double) OCTAVE)), 1, 256);
    }
    public static int column(int raw) {
        return Math.clamp((int) Math.round(CENTER + OCTAVE * Math.log(Math.max(1, Math.abs(raw)) / 16d) / Math.log(2)), 0, MAX);
    }
}
