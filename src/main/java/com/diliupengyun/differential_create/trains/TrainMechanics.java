package com.diliupengyun.differential_create.trains;

import java.util.List;
import java.util.ArrayList;
import com.diliupengyun.differential_create.kinetics.DriveTorque;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** SI translation and engine rotors. A gearbox is a passive, finite-torque clutch,
 * never a speed assignment. This class deliberately has no Minecraft dependency. */
public final class TrainMechanics {
    public static final double WHEEL_RADIUS = .5;
    public static final double BLOCK_MASS = 250;
    public static final double TORQUE_SCALE = 16;
    public static final double BRAKE_ACCELERATION = 8;
    public static final double ROLLING_ACCELERATION = .008;
    public static final double GRAVITY = 9.81;
    public static final double TICK_SECONDS = .05;
    public static final double RPM_PER_SPEED = 60 * 20 / (2 * Math.PI * WHEEL_RADIUS);
    public static final int FORWARD_GEARS = 5;
    public static final int REVERSE_GEAR = FORWARD_GEARS + 1;
    public static final double[] GEARS = {0, .5, 1, 2, 4, 8, -.5};

    public static int clampGear(int gear) { return Math.clamp(gear, 0, REVERSE_GEAR); }

    /** Player order is R, N, 1 through 5, independently of the saved values. */
    public static int stepGear(int gear, int step, boolean cycle) {
        int valid = clampGear(gear);
        int ordered = valid == REVERSE_GEAR ? -1 : valid;
        int next = ordered + Integer.signum(step);
        next = cycle ? Math.floorMod(next + 1, GEARS.length) - 1 : Math.clamp(next, -1, FORWARD_GEARS);
        return next < 0 ? REVERSE_GEAR : next;
    }

    /** Multiple cylinders on one input network obey one shaft constraint.
     * referenceRatio converts the source RPM to this shared rotor's RPM. */
    public record Cylinder(double stallTorque, double power, double referenceRatio,
                           double governor, double governorRange) {
        public double torque(double referenceRpm) {
            return DriveTorque.torque(stallTorque, power * 60 / (2 * Math.PI), referenceRpm / referenceRatio,
                governor, governorRange) / referenceRatio;
        }
    }

    public static final class Engine {
        public double omega, turns;
        public double inertia = 32;
        public double stallTorque, power, governor = 512, governorRange = 64;
        public double ratio = 1;
        public double shiftTime;
        public double clutchHeat;
        public final List<Cylinder> cylinders = new ArrayList<>();
        public double rpm() { return omega * 60 / (2 * Math.PI); }
        public void shift(double nextRatio) {
            if (nextRatio == ratio) return;
            ratio = nextRatio;
            shiftTime = .35;
        }
    }

    public record Motion(double speed, double distance) {}

    private TrainMechanics() {}

    public static Motion advance(double speed, double mass, double drag, double grade,
                                 double brake, List<Engine> engines, double seconds) {
        if (!Double.isFinite(speed) || !Double.isFinite(mass) || !(mass > 0)
            || !Double.isFinite(drag) || drag < 0 || !Double.isFinite(grade)
            || !Double.isFinite(brake) || !Double.isFinite(seconds) || seconds < 0)
            throw new IllegalArgumentException("Invalid train motion");
        double distance = 0;
        int steps = Math.max(1, (int) Math.ceil(seconds / (TICK_SECONDS / 64)));
        double dt = seconds / steps;
        for (int step = 0; step < steps; step++) {
            // External forces use an exact drag/friction solution and cannot
            // reverse the train merely because a brake crosses zero in a tick.
            var free = RotationMath.motion(speed, -mass * GRAVITY * grade, 0,
                mass * (ROLLING_ACCELERATION + Math.clamp(brake, 0, 1) * BRAKE_ACCELERATION), mass, drag, dt);
            double before = speed;
            speed = free.speed();
            for (Engine engine : engines) {
                double rpm = engine.rpm();
                double cylinder = engine.cylinders.isEmpty() ? DriveTorque.torque(engine.stallTorque,
                    engine.power * 60 / (2 * Math.PI), rpm, engine.governor, engine.governorRange) : 0;
                for (var source : engine.cylinders) cylinder += source.torque(rpm);
                engine.omega += cylinder * dt / engine.inertia;
                engine.shiftTime = Math.max(0, engine.shiftTime - dt);
                if (engine.ratio != 0) {
                    double radius = WHEEL_RADIUS * engine.ratio;
                    double relative = engine.omega - speed / radius;
                    double inverseInertia = 1 / engine.inertia + 1 / (mass * radius * radius);
                    double engagement = Math.clamp((.2 - engine.shiftTime) / .2, 0, 1);
                    // Installed cylinder size sets clutch strength even without steam.
                    double limit = 65_536 * engagement * dt;
                    double impulse = Math.clamp(relative / inverseInertia, -limit, limit);
                    double afterRelative = relative - impulse * inverseInertia;
                    engine.clutchHeat += Math.max(0, .5 * (relative * relative - afterRelative * afterRelative) / inverseInertia);
                    engine.omega -= impulse / engine.inertia;
                    speed += impulse / (mass * radius);
                }
                engine.turns += engine.omega * dt / (2 * Math.PI);
            }
            distance += (before + speed) * .5 * dt;
        }
        return new Motion(speed, distance);
    }

    public static double stoppingDistance(double speed, double downhillGrade) {
        double deceleration = BRAKE_ACCELERATION + ROLLING_ACCELERATION - GRAVITY * Math.max(0, downhillGrade);
        return deceleration > 0 ? speed * speed / (2 * deceleration) : Double.POSITIVE_INFINITY;
    }
}
