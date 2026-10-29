package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;

/** The rope and every attached section share one signed, shaft-driven stroke. */
public final class PonderPulleyMotion {
    private final PulleyBlockEntity pulley;
    private double target, position, previousAngle, floor = Double.POSITIVE_INFINITY;
    private boolean mounted;
    private PonderPulleyMotion leader;
    private double leaderOffset;

    PonderPulleyMotion(PulleyBlockEntity pulley) {
        this.pulley = pulley;
        target = position = pulley.offset;
        sampleAngle();
    }
    public void command(double distance, boolean immediate) {
        if (leader != null) return;
        floor = Double.POSITIVE_INFINITY;
        target = Math.clamp(position + distance, 0, ((LinearActuatorAccessor) pulley).differentialcreate$extensionRange());
        if (immediate) { position = target; pulley.animateOffset((float) position); }
        sampleAngle();
    }
    /** Native extra ropes are cosmetic followers, without another motor or payload mass. */
    void follow(PonderPulleyMotion primary) {
        if (leader == primary) return;
        leader = primary; leaderOffset = position - primary.position;
    }
    void tickFollower() {
        if (leader == null) return;
        position = leader.position + leaderOffset;
        target = leader.target + leaderOffset;
        pulley.animateOffset((float) position);
    }
    double couplingRatio() {
        if (!(pulley instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity)) return 1;
        // Elevator floor selection reverses its internal transmission, while
        // its powered input shaft keeps turning in the same direction.
        double input = PonderKinetics.of((PonderLevel) pulley.getLevel()).commandedSpeed(pulley);
        if (input == 0) input = pulley.getSpeed();
        return 2 * Math.copySign(1, target - position) * Math.copySign(1, input);
    }
    void limit(double maximum) { floor = Math.min(floor, maximum); }
    public boolean arrived() { return leader != null ? leader.arrived() : Math.abs(position - Math.min(target, floor)) < 1e-5; }
    PonderSimulation.TravelBounds bounds() {
        double remaining = (Math.min(target, floor) - position) * 512 / 1200;
        return new PonderSimulation.TravelBounds(Math.min(0, remaining), Math.max(0, remaining));
    }
    void sampleAngle() {
        var physics = PonderKinetics.of((PonderLevel) pulley.getLevel());
        Double angle = mountedAngle(physics);
        mounted = angle != null;
        if (angle == null) angle = physics.angle(pulley, 1);
        previousAngle = angle == null ? 0 : angle;
    }
    void tick() {
        if (leader != null) return;
        var physics = PonderKinetics.of((PonderLevel) pulley.getLevel());
        Double angle = mountedAngle(physics);
        boolean attached = angle != null;
        if (angle == null) angle = physics.angle(pulley, 1);
        if (angle == null) return;
        // Mounting replaces the unloaded shaft coordinate with the load coordinate.
        if (mounted != attached) { sampleAngle(); return; }
        double limit = pulley instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity ? 1.99 : .49;
        double delta = Math.clamp(Math.toRadians(angle - previousAngle) * ContraptionGeometry.LINEAR_RADIUS
            * (attached ? 1 : couplingRatio()), -limit, limit);
        previousAngle = angle;
        double end = Math.min(target, floor);
        // A new command may still encounter the old shaft direction during reversal.
        // Do not turn that coasting work into movement in the requested direction.
        if (delta * (end - position) > 0)
            position += Math.copySign(Math.min(Math.abs(end - position), Math.abs(delta)), delta);
        pulley.animateOffset((float) position);
    }
    private Double mountedAngle(PonderKinetics physics) {
        var attachment = physics.attachmentState(pulley.getBlockPos());
        return attachment != null && attachment.active() ? physics.outputAngle(pulley, 1) : null;
    }
}
