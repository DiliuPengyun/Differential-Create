package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;

/** Historical offline reference for lumped, adiabatic ambient air.
 * It is excluded from the mod's runtime source set.
 * Mass is kg; internal energy is joules.
 * Air does not belong to FluidStack inventories and is never rendered as water. */
public final class PipeAir {
    public static final double ATMOSPHERE = 101325;
    public static final double DENSITY = 1.225;
    public static final double GAMMA = 1.4;
    public static final double JOULES_PER_ENERGY = 60 * IdealFluidSolver.WATTS_PER_SU;
    public static final double AMBIENT_ENTHALPY = GAMMA / (GAMMA - 1) * ATMOSPHERE / DENSITY;
    public static final PipeAir UNINITIALIZED = new PipeAir(List.of());
    public record Portion(double start, double end, double mass, double energy) {
        public Portion {
            if (!(end > start) || start < 0 || end > 1 || mass < 0 || energy < 0
                || !Double.isFinite(mass) || !Double.isFinite(energy)) throw new IllegalArgumentException("Invalid pipe air");
        }
    }
    private final List<Portion> portions;
    public PipeAir(List<Portion> portions) { this.portions = List.copyOf(portions); }
    public List<Portion> portions() { return portions; }
    public static double volume(double length, double fraction) { return IdealFluidSolver.AREA * length * fraction; }
    public static double internalEnergy(double volume, double pressure) { return pressure * volume / (GAMMA - 1); }
    public static double pressure(double volume, double energy) { return volume == 0 ? ATMOSPHERE : (GAMMA - 1) * energy / volume; }
    public static double gaugePressure(double volume, double energy) {
        return volume == 0 ? 0 : Math.fma(GAMMA - 1, energy, -ATMOSPHERE * volume) / volume;
    }
    /** Gauge-pressure work uses internal energy relative to the ambient enthalpy reservoir. */
    public static double availableEnergy(double volume, double mass, double energy) {
        return (energy + ATMOSPHERE * volume - AMBIENT_ENTHALPY * mass) / JOULES_PER_ENERGY;
    }
    public static double compressedEnergy(double volume, double energy, double nextVolume) {
        if (!(nextVolume > 0)) return energy == 0 ? 0 : Double.POSITIVE_INFINITY;
        return energy * Math.pow(volume / nextVolume, GAMMA - 1);
    }
    /** Exact average pressure of an adiabatic volume change; no numerical damping. */
    public static double averagePressure(double volume, double energy, double nextVolume) {
        return averagePressureChange(volume, energy, nextVolume - volume, nextVolume);
    }
    /** Preserve displacements below one ULP of the region's absolute volume. */
    public static double averagePressureChange(double volume, double energy, double change) {
        return averagePressureChange(volume, energy, change, volume + change);
    }
    private static double averagePressureChange(double volume, double energy, double change, double nextVolume) {
        if (energy == 0) return 0;
        if (!(nextVolume > 0)) return Double.POSITIVE_INFINITY;
        double delta = change / volume;
        if (delta == 0) return pressure(volume, energy);
        return -energy * Math.expm1(-(GAMMA - 1) * volumeLog(volume, nextVolume, delta)) / change;
    }
    /** Resolve a small compression force without adding and subtracting atmosphere. */
    public static double averageGaugePressureChange(double volume, double energy, double change) {
        double delta = change / volume;
        if (Math.abs(delta) >= 1e-4) return averagePressureChange(volume, energy, change) - ATMOSPHERE;
        double coefficient = 1, power = 1, excess = 0;
        for (int order = 1; order <= 6; order++) {
            coefficient *= -(GAMMA + order - 1) / (order + 1);
            power *= delta;
            excess += coefficient * power;
        }
        return gaugePressure(volume, energy) + pressure(volume, energy) * excess;
    }
    /** Derivative of the discrete pressure work, used only to solve the implicit step. */
    public static double pressureSlope(double volume, double energy, double nextVolume) {
        return pressureSlopeChange(volume, energy, nextVolume - volume, nextVolume);
    }
    public static double pressureSlopeChange(double volume, double energy, double change) {
        return pressureSlopeChange(volume, energy, change, volume + change);
    }
    private static double pressureSlopeChange(double volume, double energy, double change, double nextVolume) {
        if (energy == 0) return 0;
        double delta = change / volume;
        double factor;
        if (Math.abs(delta) < 1e-4)
            factor = -GAMMA / 2 + GAMMA * (GAMMA + 1) * delta / 3
                - GAMMA * (GAMMA + 1) * (GAMMA + 2) * delta * delta / 8;
        else {
            double log = volumeLog(volume, nextVolume, delta);
            factor = (delta * Math.exp(-GAMMA * log)
                + Math.expm1(-(GAMMA - 1) * log) / (GAMMA - 1)) / (delta * delta);
        }
        return pressure(volume, energy) / volume * factor;
    }
    private static double volumeLog(double volume, double nextVolume, double delta) {
        // For strong compression, (next - old) / old rounds to -1 even
        // though the next volume is positive. Retain that resolved volume.
        if (Math.abs(delta) < .5) return Math.log1p(delta);
        double ratio = nextVolume / volume;
        return ratio > 0 && Double.isFinite(ratio) ? Math.log(ratio) : Math.log(nextVolume) - Math.log(volume);
    }
    public PipeAir slice(double from, double to) {
        var next = new ArrayList<Portion>();
        for (var air : portions) {
            double a = Math.max(from, air.start), b = Math.min(to, air.end);
            if (b <= a) continue;
            double part = (b - a) / (air.end - air.start);
            double start = (a - from) / (to - from), end = (b - from) / (to - from);
            if (end > start) next.add(new Portion(start, end, air.mass * part, air.energy * part));
        }
        return new PipeAir(next);
    }
    public PipeAir reverse() {
        var next = new ArrayList<Portion>();
        for (int i = portions.size() - 1; i >= 0; i--) {
            var air = portions.get(i); double start = 1 - air.end, end = 1 - air.start;
            if (end > start) next.add(new Portion(start, end, air.mass, air.energy));
        }
        return new PipeAir(next);
    }
    public static PipeAir join(PipeAir left, PipeAir right, double cut) {
        var next = new ArrayList<Portion>();
        for (var air : left.portions) {
            double start = air.start * cut, end = air.end * cut;
            if (end > start) next.add(new Portion(start, end, air.mass, air.energy));
        }
        for (var air : right.portions) {
            double start = cut + air.start * (1 - cut), end = cut + air.end * (1 - cut);
            if (end > start) next.add(new Portion(start, end, air.mass, air.energy));
        }
        return new PipeAir(next);
    }
    /** Current empty geometry. Old saves/new pipe arms are initialized at atmospheric pressure once. */
    public PipeAir fit(PipeContents liquid, double length) {
        var next = new ArrayList<Portion>(); double start = 0;
        for (int i = 0; i <= liquid.intervals(); i++) {
            double end = i < liquid.intervals() ? liquid.start(i) : 1;
            if (end > start) {
                double covered = 0, mass = 0, energy = 0;
                for (var old : portions) {
                    double overlap = Math.max(0, Math.min(end, old.end) - Math.max(start, old.start));
                    double share = overlap / (old.end - old.start);
                    covered += overlap; mass += old.mass * share; energy += old.energy * share;
                }
                double missing = Math.max(0, end - start - covered), volume = volume(length, missing);
                next.add(new Portion(start, end, mass + DENSITY * volume, energy + internalEnergy(volume, ATMOSPHERE)));
            }
            if (i < liquid.intervals()) start = liquid.end(i);
        }
        return new PipeAir(next);
    }
}
