package com.diliupengyun.differential_create.kinetics;

import java.util.Arrays;

/** Liquid intervals in an existing pipe arm, measured from its centre to its face.
 * Empty space has no inventory, mass, gravity or compression in this ideal model. */
public final class PipeContents {
    public static final PipeContents EMPTY = new PipeContents(new double[0]);
    public static final PipeContents FULL = new PipeContents(new double[]{0, 1});
    private final double[] ends;
    private final double fraction, moment;

    private PipeContents(double[] ends) {
        this.ends = ends;
        double volume = 0, firstMoment = 0;
        for (int i = 0; i < ends.length; i += 2) {
            volume += ends[i + 1] - ends[i];
            firstMoment += .5 * (ends[i + 1] - ends[i]) * (ends[i + 1] + ends[i]);
        }
        fraction = volume; moment = firstMoment;
    }
    public static PipeContents of(double... ends) {
        if ((ends.length & 1) != 0) throw new IllegalArgumentException("Unpaired water fronts");
        double previous = 0;
        for (int i = 0; i < ends.length; i += 2) {
            if (!Double.isFinite(ends[i]) || !Double.isFinite(ends[i + 1])
                || ends[i] < previous || ends[i + 1] <= ends[i] || ends[i + 1] > 1)
                throw new IllegalArgumentException("Invalid liquid interval");
            previous = ends[i + 1];
        }
        return canonical(ends.clone());
    }
    private static PipeContents canonical(double[] ends) {
        int count = 0;
        for (int i = 0; i < ends.length; i += 2) {
            double a = ends[i], b = ends[i + 1];
            // Affine coordinate changes can round both ends of a sub-ULP slug to
            // the same position. It owns no representable length in this frame
            // and must not survive as an invalid interval in saved/client data.
            if (a == b) continue;
            if (count > 0 && ends[count - 1] == a) ends[count - 1] = b;
            else { ends[count++] = a; ends[count++] = b; }
        }
        if (count != ends.length) ends = Arrays.copyOf(ends, count);
        if (ends.length == 0) return EMPTY;
        if (ends.length == 2 && ends[0] == 0 && ends[1] == 1) return FULL;
        return new PipeContents(ends);
    }
    public double fraction() { return fraction; }
    public double moment() { return moment; }
    public int intervals() { return ends.length / 2; }
    public double start(int interval) { return ends[interval * 2]; }
    public double end(int interval) { return ends[interval * 2 + 1]; }
    public boolean atStart() { return ends.length != 0 && ends[0] <= 1e-12; }
    public boolean atEnd() { return ends.length != 0 && ends[ends.length - 1] >= 1 - 1e-12; }
    public double[] data() { return ends.clone(); }
    public PipeContents reverse() {
        if (this == EMPTY || this == FULL) return this;
        double[] result = new double[ends.length];
        for (int i = 0; i < ends.length; i++) result[i] = 1 - ends[ends.length - 1 - i];
        return canonical(result);
    }
    public PipeContents slice(double from, double to) {
        if (this == EMPTY || this == FULL) return this;
        double[] result = new double[ends.length]; int count = 0;
        for (int i = 0; i < ends.length; i += 2) {
            double a = Math.max(from, ends[i]), b = Math.min(to, ends[i + 1]);
            if (b > a) { result[count++] = (a - from) / (to - from); result[count++] = (b - from) / (to - from); }
        }
        return canonical(Arrays.copyOf(result, count));
    }
    public static PipeContents join(PipeContents left, PipeContents right, double cut) {
        if (left == EMPTY && right == EMPTY) return EMPTY;
        if (left == FULL && right == FULL) return FULL;
        double[] result = new double[left.ends.length + right.ends.length]; int count = 0;
        for (double end : left.ends) result[count++] = end * cut;
        for (int i = 0; i < right.ends.length; i += 2) {
            double a = cut + right.ends[i] * (1 - cut), b = cut + right.ends[i + 1] * (1 - cut);
            if (count > 0 && result[count - 1] == a) result[count - 1] = b;
            else { result[count++] = a; result[count++] = b; }
        }
        return canonical(Arrays.copyOf(result, count));
    }
    /** Exact plug translation between events at a junction or inventory mouth.
     * A junction with mixed liquid/empty inflow routes its owned liquid in flow proportion. */
    public PipeContents move(double displacement, double inletFraction) {
        if (displacement == 0) return this;
        if (displacement < 0) return reverse().move(-displacement, inletFraction).reverse();
        if (!(inletFraction >= 0 && inletFraction <= 1) || displacement > 1 + 1e-10)
            throw new IllegalArgumentException("Liquid step crossed a pipe mouth");
        if (this == FULL && inletFraction == 1) return FULL;
        double[] result = new double[ends.length + 2]; int count = 0;
        double admitted = displacement * inletFraction;
        if (admitted > 0) { result[count++] = displacement - admitted; result[count++] = Math.min(1, displacement); }
        for (int i = 0; i < ends.length; i += 2) {
            double a = Math.max(0, ends[i] + displacement), b = Math.min(1, ends[i + 1] + displacement);
            if (b <= a) continue;
            if (count > 0 && a <= result[count - 1] + 1e-14) result[count - 1] = b;
            else { result[count++] = a; result[count++] = b; }
        }
        return canonical(Arrays.copyOf(result, count));
    }
    public double travelToMouth(double direction) {
        double distance = 1;
        for (double end : ends) {
            double next = direction > 0 ? 1 - end : end;
            // Coalesce face events at position roundoff, without erasing inventory:
            // exiting() still integrates every owned interval across this step.
            if (next > 1e-12) distance = Math.min(distance, next);
        }
        return distance;
    }
    /** Liquid crossing the mouth during a step, including a sub-roundoff-size slug.
     * Mouth contact alone must never promote such a slug to a whole step of water. */
    public double exiting(double displacement) {
        if (displacement == 0 || this == EMPTY) return 0;
        if (displacement < 0) return reverse().exiting(-displacement);
        if (this == FULL) return displacement;
        if (ends[ends.length - 1] == 1 && ends[ends.length - 2] <= 1 - displacement) return displacement;
        double amount = 0;
        for (int i = 0; i < ends.length; i += 2)
            amount += Math.max(0, ends[i + 1] - Math.max(ends[i], 1 - displacement));
        return Math.min(displacement, amount);
    }
    /** Discrete gravitational work after accounting for transported endpoint potential. */
    public double gravityFraction(double displacement, double inletFraction) {
        if (displacement == 0) return fraction;
        if (displacement < 0) return reverse().gravityFraction(-displacement, inletFraction);
        if (this == EMPTY) return .5 * displacement * (2 * inletFraction - inletFraction * inletFraction);
        if (ends[ends.length - 1] == 1 && ends[ends.length - 2] <= 1 - displacement)
            return fraction + .5 * displacement * (2 * inletFraction - inletFraction * inletFraction - 1);
        double remaining = fraction, exitMoment = 0;
        for (int i = 0; i < ends.length; i += 2) {
            double a = Math.max(ends[i], 1 - displacement), b = ends[i + 1];
            if (b <= a) continue;
            remaining -= b - a;
            exitMoment += (b - a) * ((1 - a) + (1 - b)) * .5 / displacement;
        }
        return remaining + exitMoment + .5 * displacement * (2 * inletFraction - inletFraction * inletFraction);
    }
    public PipeContents interpolate(PipeContents previous, double partial) {
        if (previous.ends.length != ends.length || this == previous) return this;
        double[] result = new double[ends.length];
        for (int i = 0; i < ends.length; i++) result[i] = previous.ends[i] + partial * (ends[i] - previous.ends[i]);
        return canonical(result);
    }
    public long visualHash() {
        long result = ends.length;
        for (double end : ends) result = result * 31 + Math.round(end * 4096);
        return result;
    }
}
