package com.diliupengyun.differential_create.kinetics;

/** Backdrivable index transmission. The two rotors retain their own inertia.
 * A massless variable-ratio transmission drives a real elastic deflection z:
 * z' = ratio * inputSpeed - outputSpeed, T = k*z. Its paired shaft torques
 * (-ratio*T, T) therefore account for every change in k*z*z/2, even when the
 * control changes ratio. Neither a time target nor a ratio change resets z.
 * All coordinates use the solver's calibrated turns and minutes. */
public final class ClockworkTransmission {
    public static final double STIFFNESS = 1_000_000;
    public static final double TORQUE = 1024;
    public static final double LOCK_TORQUE = PhysicsTuning.ANDESITE_STRENGTH;
    public static final double INTERVAL = RotationMath.TICK_MINUTES / 64;
    public static final double POSITION_EPSILON = 1e-6;
    public static final double SPEED_EPSILON = 2e-9;
    private static final double VELOCITY_GAIN = 4800;
    private static final double POSITION_GAIN = 1200;
    private static final double ELASTIC_GAIN = 48000;

    private ClockworkTransmission() {}

    public static double energy(double twist) { return .5 * STIFFNESS * twist * twist; }

    /** Persisted mechanical state. A new time indication cannot move the
     * destination of a stroke already in flight. The tiny capture loss records
     * the event locator's residual velocity; positions are never snapped. */
    public static final class State {
        public double twist, destination, captureLoss;
        public boolean initialized, moving, landing;
        public double gear;
        public boolean pinned;

        public void prepare(double position, double error, double speed, double input,
                            double inertia, double resistance, double gravity, boolean blocked) {
            if (!initialized) {
                destination = position; initialized = true;
                moving = speed != 0;
                if (moving) destination += error;
            }
            boolean supported = Math.abs(gravity + STIFFNESS * twist) <= LOCK_TORQUE;
            if (!moving && !supported) { moving = true; destination = position + error; }
            if (!moving && !blocked && Math.abs(error) > POSITION_EPSILON * 2 && Math.abs(input) > SPEED_EPSILON) {
                moving = true; landing = false; destination = position + error;
            }
            pinned = blocked || !moving && supported;
            if (pinned) { gear = 0; return; }
            double remaining = destination - position;
            if (Math.abs(remaining) <= POSITION_EPSILON) landing = true;
            else if (Math.abs(remaining) > POSITION_EPSILON * 2) landing = false;
            gear = ratio(remaining, speed, input, inertia, resistance, gravity, twist, landing);
        }

        public double accept(double position, double speed, double inertia, double gravity, double nextTwist) {
            // Overload slip is accounted for by TorsionSolver.checkEnergy at
            // each accepted integration step, not inferred from a peak torque.
            twist = nextTwist;
            if (moving && landing && Math.abs(destination - position) <= POSITION_EPSILON * 2
                && Math.abs(speed) <= SPEED_EPSILON && Math.abs(gravity + STIFFNESS * twist) <= LOCK_TORQUE) {
                captureLoss += .5 * inertia * speed * speed;
                moving = landing = false;
                return 0;
            }
            return speed;
        }

        public TorsionSolver.EventLimit boundary(int output, double speed) {
            return landing && speed != 0 ? new TorsionSolver.EventLimit(output, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, -(int) Math.signum(speed)) : null;
        }

        public TorsionSolver.Link link(int input, int output) {
            return new TorsionSolver.Link(input, output, gear, STIFFNESS, 0, Double.POSITIVE_INFINITY, TORQUE);
        }

        public void reset() {
            twist = destination = captureLoss = gear = 0;
            initialized = moving = landing = pinned = false;
        }
    }

    /** No casing brake. Negative output work can return to the input or remain
     * in the elastic contact; the solver accounts for overload slip separately. */
    public static double ratio(double error, double speed, double input, double inertia,
                               double resistance, double externalTorque, double twist, boolean landing) {
        if (!(inertia > 0) || !Double.isFinite(inertia) || !Double.isFinite(error) || !Double.isFinite(speed)
            || !Double.isFinite(input) || !Double.isFinite(twist) || !Double.isFinite(externalTorque)
            || !(resistance >= 0) || !Double.isFinite(resistance)) throw new IllegalArgumentException("Invalid clock index plant");
        double toward = Math.signum(error);
        double authority = Math.max(0, TORQUE + resistance - toward * externalTorque) / inertia;
        double reaction = authority * (INTERVAL + 1 / VELOCITY_GAIN + 1 / ELASTIC_GAIN);
        double distance = 1.4 * authority * Math.abs(error);
        double envelope = distance == 0 ? 0 : distance / (Math.sqrt(reaction * reaction + distance) + reaction);
        double desired = landing ? 0 : toward * Math.min(Math.abs(input), Math.min(envelope, POSITION_GAIN * Math.abs(error)));
        double request = Math.clamp(inertia * VELOCITY_GAIN * (desired - speed)
            + Math.signum(desired) * resistance - externalTorque, -TORQUE, TORQUE);
        if (landing && speed != 0) request = Math.clamp(-Math.copySign(Math.max(1, inertia * VELOCITY_GAIN * Math.abs(speed)), speed)
            - externalTorque, -TORQUE, TORQUE);
        double winding = speed + ELASTIC_GAIN * (request / STIFFNESS - twist);
        // At zero input velocity the gear still exerts a reaction: stored energy
        // and gravity may backdrive the shaft. Never replace this with a brake.
        double reference = Math.abs(input) < .01 ? Math.copySign(.01, input == 0 ? 1 : input) : input;
        return Math.clamp(winding / reference, -1, 1);
    }
}
