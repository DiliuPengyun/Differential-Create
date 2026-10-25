package com.diliupengyun.differential_create.tests;

/** Independent rigid-body reference for settled clutches below the governor.
 * Newton's law includes reflected rotor inertia; RK4 integrates that one ODE,
 * without using the production impulse solver, torque curve or source helpers. */
public final class TrainDynamicsOracle {
    private TrainDynamicsOracle() {}

    public record Setup(double mass, double drag, double grade, int engines, double radius,
                        double torque, double totalPower, double brake) {
        public double effectiveMass() { return mass + engines * 32 / (radius * radius); }

        double acceleration(double speed) {
            double force = totalPower == 0 ? 0
                : Math.copySign(Math.min(engines * torque / Math.abs(radius), totalPower / Math.abs(speed)), radius);
            double resistance = mass * 9.81 * grade + mass * (.008 + 8 * brake) * Math.signum(speed)
                + drag * speed * Math.abs(speed);
            return (force - resistance) / effectiveMass();
        }
    }

    public record Motion(double speed, double distance) {}

    public static Motion advance(Setup setup, double speed, double seconds) {
        double distance = 0, dt = seconds / 256;
        for (int i = 0; i < 256; i++) {
            double k1 = setup.acceleration(speed);
            double v2 = speed + k1 * dt / 2, k2 = setup.acceleration(v2);
            double v3 = speed + k2 * dt / 2, k3 = setup.acceleration(v3);
            double v4 = speed + k3 * dt, k4 = setup.acceleration(v4);
            distance += dt * (speed + 2 * v2 + 2 * v3 + v4) / 6;
            speed += dt * (k1 + 2 * k2 + 2 * k3 + k4) / 6;
        }
        return new Motion(speed, distance);
    }
}
