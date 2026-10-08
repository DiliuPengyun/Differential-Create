package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;

/** Independent plug velocities, in mB/tick. Geometry is stored by PipeContents. */
public record PipeMotion(PipeContents contents, double[] flow, double mergeLoss) {
    public PipeMotion {
        flow = flow.clone();
        if (flow.length != contents.intervals()) throw new IllegalArgumentException("Liquid velocities do not match intervals");
        for (double q : flow) if (!Double.isFinite(q)) throw new IllegalArgumentException("Invalid liquid velocity");
    }
    public PipeMotion(PipeContents contents, double[] flow) { this(contents, flow, 0); }
    public static PipeMotion uniform(PipeContents contents, double flow) {
        double[] values = new double[contents.intervals()]; java.util.Arrays.fill(values, flow);
        return new PipeMotion(contents, values);
    }
    public double average() {
        double sum = 0;
        for (int i = 0; i < flow.length; i++) sum += (contents.end(i) - contents.start(i)) * flow[i];
        return contents.fraction() == 0 ? 0 : sum / contents.fraction();
    }
    public PipeMotion reverse() {
        var pieces = new ArrayList<Piece>();
        for (int i = 0; i < flow.length; i++) pieces.add(new Piece(1 - contents.end(i), 1 - contents.start(i), -flow[i]));
        var reversed = of(pieces);
        return new PipeMotion(reversed.contents, reversed.flow, mergeLoss + reversed.mergeLoss);
    }
    public PipeMotion slice(double from, double to) {
        var pieces = new ArrayList<Piece>();
        for (int i = 0; i < flow.length; i++) {
            double a = Math.max(from, contents.start(i)), b = Math.min(to, contents.end(i));
            if (b > a) pieces.add(new Piece((a - from) / (to - from), (b - from) / (to - from), flow[i]));
        }
        return of(pieces);
    }
    public static PipeMotion join(PipeMotion left, PipeMotion right, double cut) {
        var pieces = new ArrayList<Piece>();
        for (int i = 0; i < left.flow.length; i++)
            pieces.add(new Piece(left.contents.start(i) * cut, left.contents.end(i) * cut, left.flow[i]));
        for (int i = 0; i < right.flow.length; i++)
            pieces.add(new Piece(cut + right.contents.start(i) * (1 - cut), cut + right.contents.end(i) * (1 - cut), right.flow[i]));
        var joined = of(pieces);
        return new PipeMotion(joined.contents, joined.flow, joined.mergeLoss + cut * left.mergeLoss + (1 - cut) * right.mergeLoss);
    }
    public record Piece(double start, double end, double flow) {}
    public static PipeMotion of(List<Piece> input) {
        var pieces = new ArrayList<>(input); pieces.sort(java.util.Comparator.comparingDouble(Piece::start));
        var joined = new ArrayList<Piece>(); double loss = 0;
        for (var next : pieces) {
            if (!(next.end > next.start)) continue;
            if (next.start < 0 || next.end > 1) throw new IllegalArgumentException("Liquid moved beyond a pipe mouth");
            if (!joined.isEmpty() && next.start <= joined.getLast().end) {
                var previous = joined.removeLast();
                double a = previous.end - previous.start, b = next.end - next.start;
                double q = (a * previous.flow + b * next.flow) / (a + b);
                loss += a * b / (a + b) * (previous.flow - next.flow) * (previous.flow - next.flow);
                joined.add(new Piece(previous.start, Math.max(previous.end, next.end), q));
            } else joined.add(next);
        }
        double[] ends = new double[joined.size() * 2], flow = new double[joined.size()];
        for (int i = 0; i < flow.length; i++) {
            var piece = joined.get(i); ends[2 * i] = piece.start; ends[2 * i + 1] = piece.end; flow[i] = piece.flow;
        }
        return new PipeMotion(PipeContents.of(ends), flow, loss);
    }
}
