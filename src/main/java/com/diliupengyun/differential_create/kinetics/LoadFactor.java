package com.diliupengyun.differential_create.kinetics;

import java.util.List;

/** Sustained demand / current drive. Inertial and elastic energy storage are not demand. */
public final class LoadFactor {
    public enum Status { AVAILABLE, NO_POWER, BRAKING, UNKNOWN, UNDEFINED }
    public record Reading(Status status, double fraction) {
        public static final Reading UNKNOWN = new Reading(Status.UNKNOWN, 0);
        public static final Reading NO_POWER = new Reading(Status.NO_POWER, 0);
        public static final Reading UNDEFINED = new Reading(Status.UNDEFINED, 0);
    }
    /** ratio is the signed nominal local/reference speed ratio, also valid at standstill. */
    public record Member(double ratio, double speed, double driveTorque, double loadTorque, boolean powered) {}
    private LoadFactor() {}

    /** Remove only cancellation-sized readout noise, without changing physical forces. */
    public static double resolvedTorque(double value, double scale) {
        return Double.isFinite(scale) && Math.abs(value) <= 64 * Math.ulp(scale) ? 0 : value;
    }

    public static Reading calculate(List<Member> members, double transmissionLossPower) {
        if (members.isEmpty()) return Reading.UNKNOWN;
        if (!Double.isFinite(transmissionLossPower) || transmissionLossPower < 0) return Reading.UNDEFINED;
        boolean moving = false, powered = false;
        double startingDrive = 0;
        for (Member member : members) {
            if (!Double.isFinite(member.ratio) || !Double.isFinite(member.speed)
                || !Double.isFinite(member.driveTorque) || !Double.isFinite(member.loadTorque)) return Reading.UNDEFINED;
            moving |= Math.abs(member.speed) > 1e-9;
            powered |= member.powered;
            startingDrive += member.ratio * member.driveTorque;
        }
        if (!powered) return Reading.NO_POWER; // Stored flywheel energy is not an active drive.
        double direction = startingDrive < 0 ? -1 : 1;
        double supply = 0, demand = moving ? transmissionLossPower : 0, braking = 0;
        for (Member member : members) {
            // At rest use the torque needed to start the pending operation, not zero watts.
            double weight = moving ? member.speed : member.ratio * direction;
            double drive = member.driveTorque * weight;
            supply += Math.max(0, drive);
            braking += Math.max(0, -drive);
            demand += Math.max(0, member.loadTorque) * Math.abs(weight);
        }
        demand += braking;
        if (!Double.isFinite(supply) || !Double.isFinite(demand)) return Reading.UNDEFINED;
        if (supply == 0 && braking > 0) return new Reading(Status.BRAKING, 0);
        // An unloaded water wheel at its no-load speed is 0%, not an undefined 0/0.
        double fraction = demand == 0 ? 0 : supply == 0 ? Double.POSITIVE_INFINITY : demand / supply;
        return new Reading(Status.AVAILABLE, fraction);
    }

    /** Instantaneous loss of the elastic/damped contact in series with dry friction. */
    public static double contactLoss(double stiffness, double damping, double friction, double twist, double relativeSpeed) {
        if (!(friction > 0)) return 0;
        double spring = stiffness * twist, raw = spring + damping * relativeSpeed;
        if (Math.abs(raw) <= friction) return damping * relativeSpeed * relativeSpeed;
        double torque = Math.copySign(friction, raw);
        if (damping == 0) return Math.max(0, torque * relativeSpeed);
        double elasticRate = (torque - spring) / damping;
        return Math.max(0, damping * elasticRate * elasticRate + torque * (relativeSpeed - elasticRate));
    }
}
