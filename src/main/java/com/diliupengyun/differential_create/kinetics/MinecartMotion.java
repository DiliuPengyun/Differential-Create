package com.diliupengyun.differential_create.kinetics;

/** Native cart velocity is blocks/tick; the reference coordinate uses the same
 * 512 conversion as a piston. Inertia is su/RPM^3, power su and time minutes. */
public final class MinecartMotion {
    public static final double BASE_INERTIA = .01;
    public static final double RPM_PER_SPEED = 512;
    public static final double BLOCKS_PER_TURN = 1200d / RPM_PER_SPEED;
    public static final double RAIL_ACCELERATION = .06;
    public static final double CONTROL_ACCELERATION = .4;
    // Vanilla projects its stored rail velocity to at most two blocks/tick.
    // This rating preserves its complete operating range, including the stored
    // velocity above the rail's actual displacement cap.
    public static final double RAIL_POWER = torque(RAIL_ACCELERATION) * (2 + RAIL_ACCELERATION / 2) * RPM_PER_SPEED;
    public static final double CONTROL_POWER = torque(CONTROL_ACCELERATION) * (2 + CONTROL_ACCELERATION / 2) * RPM_PER_SPEED;
    public static final double FURNACE_ACCELERATION = 2;
    public static final double FURNACE_POWER = (energy(BASE_INERTIA, 2.6) - energy(BASE_INERTIA, 2)) / RotationMath.TICK_MINUTES;
    private MinecartMotion() {}

    public static double torque(double acceleration) {
        return BASE_INERTIA * RPM_PER_SPEED * acceleration / RotationMath.TICK_MINUTES;
    }
    public static double energy(double inertia, double speed) {
        return .5 * inertia * Math.pow(speed * RPM_PER_SPEED, 2);
    }
    public static double attachedSpeed(double speed, double oldInertia, double newInertia) {
        return newInertia > oldInertia ? speed * oldInertia / newInertia : speed;
    }
    /** Exact continuation of v' = factor*v + impulse at the reference mass.
     * Keeping the reference branch also preserves Minecraft's float constants. */
    public static double affine(double speed, double factor, double impulse, double inertia) {
        if (inertia == BASE_INERTIA) return factor * speed + impulse;
        double decay = Math.pow(factor, BASE_INERTIA / inertia);
        double equilibrium = impulse / (1 - factor);
        return decay * speed + (1 - decay) * equilibrium;
    }
    public static double coast(double speed, double factor, double inertia, double drag) {
        if (drag == 0) return inertia == BASE_INERTIA ? speed * factor
            : speed * Math.pow(factor, BASE_INERTIA / inertia);
        double damping = -BASE_INERTIA * Math.log(factor) / RotationMath.TICK_MINUTES;
        return RotationMath.advance(speed * RPM_PER_SPEED, 0, damping, 0, inertia, drag,
            RotationMath.TICK_MINUTES) / RPM_PER_SPEED;
    }
    /** A finite impulse, with positive work bounded by the source's tick budget. */
    public static double impulse(double speed, double delta, double inertia, double power) {
        double next = speed + delta * BASE_INERTIA / inertia;
        double allowedEnergy = energy(inertia, speed) + power * RotationMath.TICK_MINUTES;
        if (energy(inertia, next) > allowedEnergy)
            next = Math.copySign(Math.sqrt(2 * allowedEnergy / inertia) / RPM_PER_SPEED, next);
        return next;
    }
    public static double target(double speed, double target, double inertia) {
        return impulse(speed, Math.clamp(target - speed, -CONTROL_ACCELERATION, CONTROL_ACCELERATION),
            inertia, CONTROL_POWER);
    }

    public record Cutting(double speed, double travel, double minutes) {}
    /** Positive RPM and RPM-tick feed, stopping at either a contact event or
     * exhausted kinetic energy. Tool work is torque * travel / 1200. */
    public static Cutting cut(double speed, double inertia, double torque, double limit, double minutes) {
        var motion = RotationMath.motion(speed, 0, 0, torque, inertia, 0, minutes);
        double travel = motion.travel() * 1200;
        if (travel <= limit) return new Cutting(motion.speed(), travel, minutes);
        travel = Math.max(0, limit);
        double next = Math.sqrt(Math.max(0, speed * speed - 2 * torque * travel / (1200 * inertia)));
        double duration = speed + next > 0 ? 2 * travel / (1200 * (speed + next)) : minutes;
        return new Cutting(next, travel, duration);
    }

    public record Coupled(double first, double second, double extension, double loss) {}
    /** Implicit midpoint spring/damper. Zero mobility is a genuinely fixed end,
     * rather than a fake heavy rotor. The two moving ends receive opposite impulses. */
    public static Coupled couple(double first, double second, double jFirst, double jSecond,
                                 double projectionFirst, double projectionSecond, double extension,
                                 boolean moveFirst, boolean moveSecond) {
        double a = moveFirst ? 1 / jFirst : 0, b = moveSecond ? 1 / jSecond : 0;
        double mobility = projectionFirst * projectionFirst * a + projectionSecond * projectionSecond * b;
        if (mobility == 0) return new Coupled(first, second, extension, 0);
        double h = RotationMath.TICK_MINUTES;
        double stiffness = BASE_INERTIA / (h * h), damping = 2 * BASE_INERTIA / h;
        double x = extension / BLOCKS_PER_TURN;
        double relative = RPM_PER_SPEED * (projectionSecond * (moveSecond ? second : 0)
            - projectionFirst * (moveFirst ? first : 0));
        double nextRelative = ((1 - .5 * h * mobility * damping - .25 * h * h * mobility * stiffness) * relative
            - h * mobility * stiffness * x) / (1 + .5 * h * mobility * damping + .25 * h * h * mobility * stiffness);
        double average = .5 * (relative + nextRelative), nextX = x + h * average;
        double impulse = h * (stiffness * .5 * (x + nextX) + damping * average);
        return new Coupled(first + projectionFirst * impulse * a / RPM_PER_SPEED,
            second - projectionSecond * impulse * b / RPM_PER_SPEED,
            nextX * BLOCKS_PER_TURN, h * damping * average * average);
    }
}
