package com.diliupengyun.differential_create.trains;

import java.util.HashSet;
import java.util.Collection;
import java.util.Set;

/** Unit-square silhouettes in the train's track-aligned cross section.
 * Longitudinal gaps do not expose a second copy of the same area. */
public final class TrainAerodynamics {
    public static final double AIR_COEFFICIENT = .55;
    private TrainAerodynamics() {}

    public static long cell(int lateral, int height) {
        return ((long) lateral << 32) | (height & 0xffffffffL);
    }
    public static int lateral(long cell) { return (int) (cell >> 32); }
    public static int height(long cell) { return (int) cell; }

    /** An inverted bogey's anchor is two metres below an upright bogey's anchor. */
    public static void include(Set<Long> silhouette, Collection<Long> projection, boolean upsideDown) {
        int offset = upsideDown ? -2 : 0;
        for (long cell : projection) silhouette.add(cell(lateral(cell), height(cell) + offset));
    }
    public static int area(Iterable<? extends Collection<Long>> projections) {
        var silhouette = new HashSet<Long>();
        for (var projection : projections) silhouette.addAll(projection);
        return silhouette.size();
    }
}
