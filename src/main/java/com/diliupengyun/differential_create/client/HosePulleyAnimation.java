package com.diliupengyun.differential_create.client;

/** Keep the hose head outside its casing without hiding the start of its travel. */
public final class HosePulleyAnimation {
    private HosePulleyAnimation() {}

    public static float offset(float extension) {
        if (extension >= 1) return extension;
        float x = Math.max(0, extension);
        float remaining = 1 - x;
        // Fade the retracted head's 3-pixel clearance over the first block.
        // Both ends have unit slope; the entire interval is strictly increasing.
        // Physics, suction position and saved extension still use the native value.
        return x + (3 / 16f) * remaining * remaining * (1 + 2 * x);
    }
}
