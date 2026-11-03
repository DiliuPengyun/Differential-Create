package com.diliupengyun.differential_create.client;

import com.simibubi.create.content.kinetics.base.RotatingInstance;
import net.minecraft.util.Mth;

/** Tick endpoints belong to the block entity, independently of either renderer's lifetime. */
public final class MixerHeadAnimation {
    public interface Owner { MixerHeadAnimation differentialcreate$headAnimation(); }

    private double previousAngle, angle, shaftEnd;
    private float previousHeight, height;
    private int ratio;
    private boolean initialized, shaftKnown, pending, frozen;

    public synchronized void begin(double stroke, int gearing) {
        if (!initialized) previousHeight = height = height(stroke);
        initialized = true;
        previousHeight = height;
        previousAngle = angle;
        ratio = gearing;
        pending = true;
    }

    public synchronized void finish(double stroke) { height = height(stroke); }

    /** An unsynced input has incremental travel, but no comparable absolute shaft phase. */
    public synchronized void advanceFallback(double degrees, boolean frozen, float partial) {
        shaftKnown = false;
        advance(0, degrees, frozen, partial);
        shaftKnown = false;
    }

    /** Consume integrated input travel once, even if the head was never rendered this tick. */
    public synchronized void advance(double shaftStart, double shaftEnd, boolean frozen, float partial) {
        if (!shaftKnown) this.shaftEnd = shaftStart;
        shaftKnown = true;
        if (pending) {
            angle += (shaftEnd - this.shaftEnd) * ratio;
            this.shaftEnd = shaftEnd;
            pending = false;
            if (frozen) { previousAngle = angle; previousHeight = height; }
        } else if (frozen && !this.frozen) {
            previousAngle = angle = Mth.lerp(partial, previousAngle, angle);
            previousHeight = height = Mth.lerp(partial, previousHeight, height);
            this.shaftEnd = shaftEnd;
        } else if (frozen) {
            // A frozen input changes only when a complete server step is accepted.
            angle += (shaftEnd - this.shaftEnd) * ratio;
            this.shaftEnd = shaftEnd;
            previousAngle = angle;
            previousHeight = height;
        }
        this.frozen = frozen;
    }

    public synchronized float offset(double stroke, float partial) {
        return initialized ? Mth.lerp(Mth.clamp(partial, 0, 1), previousHeight, height) : height(stroke);
    }

    public synchronized float degrees(float partial) {
        return (float) (Mth.lerp(Mth.clamp(partial, 0, 1), previousAngle, angle) % 360);
    }

    public void apply(RotatingInstance head, float partial) {
        head.setRotationalSpeed(0);
        head.rotationOffset = degrees(partial);
        head.setChanged();
    }

    private static float height(double stroke) {
        double extension = stroke <= 20 ? stroke : 40 - stroke;
        return (float) ((1 - Math.cos(Math.PI * extension / 20)) / 2 + 7 / 16.0);
    }
}
