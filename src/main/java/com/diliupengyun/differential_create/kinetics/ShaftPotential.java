package com.diliupengyun.differential_create.kinetics;

import java.util.List;

/** Conservative energy in the solver's turns/minutes calibration. Each capture
 * starts at q=0; accepted substeps carry their own absolute displacement from it. */
public record ShaftPotential(double linear, List<Harmonic> harmonics) {
    public static final ShaftPotential NONE = new ShaftPotential(0, List.of());
    public static final double GRAVITY = 9.81;
    public static final double WEIGHT = 3600 * GRAVITY / (4 * Math.PI * Math.PI);
    public record Harmonic(double cosine, double sine, double frequency) {}
    public ShaftPotential { harmonics = List.copyOf(harmonics); }
    public boolean active() { return linear != 0 || !harmonics.isEmpty(); }

    /** Rebase a capture after accepting part of its displacement. */
    public ShaftPotential shifted(double position) {
        if (position == 0 || harmonics.isEmpty()) return this;
        return new ShaftPotential(linear, harmonics.stream().map(term -> {
            double a = term.frequency * position;
            return new Harmonic(term.cosine * Math.cos(a) + term.sine * Math.sin(a),
                term.sine * Math.cos(a) - term.cosine * Math.sin(a), term.frequency);
        }).toList());
    }

    /** Stable energy difference, including tiny displacements near a turning point. */
    public double change(double position, double displacement) {
        double result = linear * displacement;
        for (var term : harmonics) {
            double half = term.frequency * displacement / 2;
            double middle = term.frequency * position + half;
            result += 2 * Math.sin(half) * (term.sine * Math.cos(middle) - term.cosine * Math.sin(middle));
        }
        return result;
    }
    /** Negative discrete gradient; its work is exactly minus the potential change. */
    public double torque(double position, double displacement) {
        double result = -linear;
        for (var term : harmonics) {
            double half = term.frequency * displacement / 2;
            double middle = term.frequency * position + half;
            result -= term.frequency * sinc(half) * (term.sine * Math.cos(middle) - term.cosine * Math.sin(middle));
        }
        return result;
    }
    /** Derivative of the discrete torque with respect to the trial displacement. */
    public double slope(double position, double displacement) {
        double result = 0;
        for (var term : harmonics) {
            double half = term.frequency * displacement / 2;
            double middle = term.frequency * position + half;
            double a = term.sine * Math.cos(middle) - term.cosine * Math.sin(middle);
            double b = term.cosine * Math.cos(middle) + term.sine * Math.sin(middle);
            result += term.frequency * term.frequency / 2 * (b * sinc(half) - a * sincSlope(half));
        }
        return result;
    }
    private static double sinc(double x) {
        double square = x * x;
        return Math.abs(x) < 1e-4 ? 1 - square / 6 + square * square / 120 : Math.sin(x) / x;
    }
    private static double sincSlope(double x) {
        double square = x * x;
        return Math.abs(x) < 1e-4 ? x * (-1.0 / 3 + square / 30 - square * square / 840)
            : (x * Math.cos(x) - Math.sin(x)) / square;
    }
}
