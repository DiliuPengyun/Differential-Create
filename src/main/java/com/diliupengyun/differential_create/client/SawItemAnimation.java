package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.kinetics.SawFeedDirection;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.util.Mth;

/** Interpolate the workpiece's actual processing endpoints, including a stalled feed. */
public final class SawItemAnimation {
    public interface Owner { SawItemAnimation differentialcreate$itemAnimation(); }
    private float previous, position;
    private boolean initialized, occupied, pending, frozen;

    public synchronized void begin(SawBlockEntity saw) {
        initialize(saw);
        previous = position;
        pending = true;
    }

    public synchronized void finish(SawBlockEntity saw) {
        if (saw.inventory.isEmpty()) { occupied = false; return; }
        if (!occupied) previous = position = pose(saw);
        else position = pose(saw);
        occupied = true;
    }

    public synchronized void advance(boolean frozen, float partial) {
        if (pending) {
            if (frozen) previous = position;
            pending = false;
        } else if (frozen && !this.frozen) previous = position = Mth.lerp(partial, previous, position);
        this.frozen = frozen;
    }

    public synchronized float offset(SawBlockEntity saw, float partial) {
        initialize(saw);
        return Mth.lerp(Mth.clamp(partial, 0, 1), previous, position);
    }

    private void initialize(SawBlockEntity saw) {
        boolean hasItem = !saw.inventory.isEmpty();
        // Insertion can happen after the BE tick. A new batch starts at its own
        // entry edge, independently of the previous batch's exit or a render cache.
        if (!initialized || !occupied && hasItem) previous = position = pose(saw, true);
        initialized = true;
        occupied = hasItem;
    }

    private static float pose(SawBlockEntity saw) { return pose(saw, false); }

    private static float pose(SawBlockEntity saw, boolean incoming) {
        var inventory = saw.inventory;
        float offset;
        if (incoming && inventory.remainingTime == -1 && !inventory.appliedRecipe) offset = 1;
        else if (inventory.appliedRecipe) offset = .5f * Mth.clamp(inventory.remainingTime / Math.max(1, inventory.recipeDuration), 0, 1);
        else {
            // The production callback cuts at remainingTime=5, then starts the
            // twenty-unit exit phase. Both phases meet at the blade's centre.
            float remaining = (inventory.remainingTime - 5) / Math.max(1, inventory.recipeDuration - 5);
            offset = .5f + .5f * Mth.clamp(remaining, 0, 1);
        }
        boolean alongZ = !saw.getBlockState().getValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE);
        return (((SawFeedDirection) saw).differentialcreate$feedDirection() < 0 ^ alongZ) ? 1 - offset : offset;
    }
}
