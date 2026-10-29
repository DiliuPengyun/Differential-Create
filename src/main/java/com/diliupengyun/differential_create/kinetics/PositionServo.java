package com.diliupengyun.differential_create.kinetics;

/** A position command operates a passive reversing clutch and a finite casing
 * brake. It never assigns position or velocity. Coordinates are turns and RPM. */
public final class PositionServo {
    public static final double TORQUE = 1024;
    public static final double DAMPING = 4096;
    public static final double CONTROL_INTERVAL = RotationMath.TICK_MINUTES / 64;
    // Native actuator offsets are floats. Below this scale the final position
    // step can round away; the finite brake must hold instead of hunting forever.
    public static final double POSITION_EPSILON = 1e-6;
    public static final double SPEED_EPSILON = .01;
    private static final double VELOCITY_GAIN = 720; // 83 ms response; critically damped with POSITION_GAIN
    private static final double POSITION_GAIN = 180;

    public record Command(double gear, double coupling, double brake, double velocity) {}
    private PositionServo() {}

    public static double offsetTolerance(double position) { return Math.max(1e-4, 2d * Math.ulp((float) position)); }
    public static double offsetError(double target, double position, double metresPerTurn) {
        double error = target - position;
        return Math.abs(error) <= 2d * Math.max(Math.ulp((float) target), Math.ulp((float) position)) ? 0 : error / metresPerTurn;
    }

    public static Command plan(double error, double speed, double input, double inertia,
                               double resistance, double externalTorque, boolean park) {
        return plan(error, speed, input, inertia, resistance, externalTorque, park, TORQUE);
    }

    public static Command plan(double error, double speed, double input, double inertia,
                               double resistance, double externalTorque, boolean park, double rating) {
        if (!(inertia > 0) || !Double.isFinite(inertia) || !(rating > 0) || !Double.isFinite(rating)
            || !Double.isFinite(error) || !Double.isFinite(speed) || !Double.isFinite(input)
            || !Double.isFinite(resistance) || resistance < 0 || !Double.isFinite(externalTorque))
            throw new IllegalArgumentException("Invalid position servo plant");
        double toward = Math.signum(error);
        double authority = rating + resistance - toward * externalTorque;
        double deceleration = Math.max(0, authority / inertia) * .7;
        double reaction = deceleration * (CONTROL_INTERVAL + 1 / VELOCITY_GAIN);
        // Stable sqrt(x*x+y)-x, including the final few floating-point ulps.
        double distance = 2 * deceleration * Math.abs(error);
        double envelope = distance == 0 ? 0 : distance / (Math.sqrt(reaction * reaction + distance) + reaction);
        boolean holding = park || Math.abs(error) <= POSITION_EPSILON || Math.abs(input) <= 1e-9;
        double desired = holding ? 0 : toward * Math.min(Math.abs(input), Math.min(envelope, POSITION_GAIN * Math.abs(error)));
        double request = Math.clamp(inertia * VELOCITY_GAIN * (desired - speed)
            + Math.signum(desired) * resistance - externalTorque, -rating, rating);
        double gear = Math.copySign(1, input == 0 ? 1 : input) * Math.copySign(1, request == 0 ? error : request);
        double relative = gear * input - speed;
        double coupling = !holding && authority > 0 && request * speed >= 0 && request * relative > 0
            ? Math.abs(request) : 0;
        double brake = holding || authority <= 0 ? rating : request * speed < 0 ? Math.abs(request) : 0;
        return new Command(gear, coupling, brake, desired);
    }
}
