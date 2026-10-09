package com.diliupengyun.differential_create.kinetics;

import java.util.List;

/** Position servo and backwards velocity planning, independent of Minecraft.
 * Positions are calibrated turns, speeds RPM, torque su/RPM and time minutes.
 * Commands operate a passive geared coupling and a casing brake, never rotor state. */
public final class SequencerMotion {
    public enum Kind { ANGLE, DISTANCE, DELAY, AWAIT, END }
    public record Segment(Kind kind, int value, int ratio) {
        public boolean moving() { return kind == Kind.ANGLE || kind == Kind.DISTANCE; }
        public double turns() { return kind == Kind.ANGLE ? value / 360d : kind == Kind.DISTANCE ? value * 512d / 1200 : 0; }
    }
    public record Plant(double inertia, double resistance, double externalDrive, double inputSpeed, double velocity, double settlingSpeed) {
        public Plant(double inertia, double resistance, double externalDrive, double inputSpeed) {
            this(inertia, resistance, externalDrive, inputSpeed, Double.NaN, Double.NaN);
        }
        public Plant {
            if (!(inertia > 0) || !Double.isFinite(inertia) || !(resistance >= 0)
                || !Double.isFinite(resistance) || !Double.isFinite(externalDrive) || !Double.isFinite(inputSpeed)
                || Double.isInfinite(velocity) || Double.isInfinite(settlingSpeed))
                throw new IllegalArgumentException("Invalid sequencer plant");
        }
    }
    public record Command(double gear, double coupling, double brake, double exitSpeed, double velocity,
                          double deceleration, boolean holding) {}
    // Mechanical ratings, not numerical regularisation. A separate output spindle
    // retains its own kinetic energy even with no shaft or machine attached.
    public static final double OUTPUT_INERTIA = .01;
    public static final double TORQUE = 1024;
    public static final double COUPLING_DAMPING = 4096;
    public static final double OUTPUT_DAMPING = 8192;
    public static final double POSITION_EPSILON = 1d / 18000; // 0.02 degrees
    public static final double SPEED_EPSILON = .05;
    public static final double CONTROL_INTERVAL = RotationMath.TICK_MINUTES / 64;
    private static final double VELOCITY_GAIN = 2400; // minute^-1, 25 ms response
    private static final double POSITION_GAIN = 1200;

    public double angle, speed, start, target, brakeHeat;
    // Assembly zero is independent of the instruction's calibrated position.
    // Reconnecting shafts may align their models without moving a motion target.
    public double phaseOffset;
    public double requestedTurns;
    public double settlingSpeed;
    public double settleTime;
    public int direction = 1, inputDirection = 1, index = -1;
    public boolean initialized;
    public double boundarySpeed, boundaryAngle;
    public int transitions;
    public Command command = new Command(1, 0, TORQUE, 0, 0, 0, true);

    /** A normal transition keeps the planned boundary; a new program starts here. */
    public void begin(List<Segment> program, int next, double inputSpeed, boolean continuation) {
        if (continuation) { boundarySpeed = speed; boundaryAngle = angle; transitions++; }
        if (!continuation) {
            target = angle;
            inputDirection = inputSpeed == 0 ? 0 : inputSpeed > 0 ? 1 : -1;
        }
        start = target;
        requestedTurns = 0;
        index = next >= 0 && next < program.size() && program.get(next).kind != Kind.END ? next : -1;
        if (index >= 0 && program.get(index).moving()) {
            Segment segment = program.get(index);
            requestedTurns = segment.turns();
            direction = (inputDirection == 0 ? 1 : inputDirection) * Integer.signum(segment.ratio);
            target = inputDirection == 0 ? start : start + direction * segment.turns();
        }
        initialized = true;
        settleTime = 0;
        command = new Command(1, 0, TORQUE, 0, 0, 0, true);
    }

    public double progress() { return inputDirection == 0 ? 0 : direction * (angle - start); }
    public double shaftAngle() { return angle + phaseOffset; }
    public boolean parked() { return Math.abs(target - angle) <= POSITION_EPSILON && Math.abs(speed) <= SPEED_EPSILON && settlingSpeed <= SPEED_EPSILON; }
    public boolean settled() { return parked() && settleTime >= RotationMath.TICK_MINUTES; }
    public void observe(double duration) { settleTime = parked() ? settleTime + duration : 0; }
    public boolean complete() {
        if (inputDirection == 0) return requestedTurns == 0 && settled();
        return command.exitSpeed > SPEED_EPSILON
            ? direction * (target - angle) <= POSITION_EPSILON
            : settled();
    }

    /** Recompute from the actual load and input shaft on every accepted capture.
     * The backwards pass can see a short final move beyond a long first move. */
    public Command plan(List<Segment> program, Plant plant, double horizon) {
        boolean moving = index >= 0 && index < program.size() && program.get(index).moving();
        settlingSpeed = Double.isFinite(plant.settlingSpeed) ? Math.abs(plant.settlingSpeed) : Math.abs(speed);
        if (moving && inputDirection == 0) {
            if (Math.abs(plant.inputSpeed) <= 1e-9) {
                command = new Command(1, 0, TORQUE, 0, 0, 0, true);
                return command;
            }
            inputDirection = plant.inputSpeed > 0 ? 1 : -1;
            direction = inputDirection * Integer.signum(program.get(index).ratio);
            target = start + direction * program.get(index).turns();
        }
        double error = target - angle;
        int toward = error == 0 ? direction : error > 0 ? 1 : -1;
        double aiding = toward * plant.externalDrive;
        double authority = TORQUE + plant.resistance - aiding;
        double deceleration = Math.max(0, authority / plant.inertia) * .7;
        double exit = moving ? exitSpeed(program, index, Math.abs(plant.inputSpeed), deceleration) : 0;
        boolean holding = Math.abs(error) <= POSITION_EPSILON && exit <= SPEED_EPSILON;
        double magnitude = moving ? Math.abs(program.get(index).ratio) : 1;
        double cap = magnitude * Math.abs(plant.inputSpeed);
        double feedback = Double.isFinite(plant.velocity) ? plant.velocity : speed;
        // Account for feedback response and the next capture's reaction distance.
        double reaction = deceleration * (horizon + 1 / VELOCITY_GAIN);
        double envelope = Math.sqrt(Math.pow(exit + reaction, 2) + 2 * deceleration * Math.abs(error)) - reaction;
        double desired = holding ? 0 : toward * Math.min(cap, Math.min(envelope, exit + POSITION_GAIN * Math.abs(error)));
        // Bulk momentum rejects elastic speed oscillation, and local feedback
        // damps the spindle relative to that load rather than amplifying it.
        double request = plant.inertia * VELOCITY_GAIN * (desired - feedback)
            + Math.signum(desired) * plant.resistance - plant.externalDrive
            - OUTPUT_INERTIA * VELOCITY_GAIN * (speed - feedback);
        request = Math.clamp(request, -TORQUE, TORQUE);
        double gear = magnitude * (plant.inputSpeed == 0 ? inputDirection : Math.signum(plant.inputSpeed))
            * (request == 0 ? toward : Math.signum(request));
        double relative = gear * plant.inputSpeed - speed;
        double coupling = !holding && request * speed >= 0 && request * relative > 0 && Math.abs(plant.inputSpeed) > 1e-9 ? Math.abs(request) : 0;
        double brake = holding || authority <= 0 ? TORQUE
            : request * speed < 0 && coupling == 0 ? Math.abs(request) : 0;
        if (authority <= 0) coupling = 0;
        command = new Command(gear, coupling, brake, exit, desired, deceleration, holding);
        return command;
    }

    public static double exitSpeed(List<Segment> program, int at, double inputSpeed, double deceleration) {
        Segment first = program.get(at);
        int end = at;
        while (end + 1 < program.size() && program.get(end + 1).moving()
            && Integer.signum(program.get(end + 1).ratio) == Integer.signum(first.ratio)) end++;
        double entry = 0;
        double reaction = deceleration * (CONTROL_INTERVAL + 1 / VELOCITY_GAIN);
        for (int i = end; i > at; i--) {
            Segment next = program.get(i);
            entry = Math.min(Math.abs(next.ratio) * inputSpeed,
                Math.sqrt(Math.pow(entry + reaction, 2) + 2 * deceleration * next.turns()) - reaction);
        }
        return Math.min(Math.abs(first.ratio) * inputSpeed, entry);
    }
}
