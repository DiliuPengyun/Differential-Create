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
        return 1;
    }
    private boolean elevator() { return pulley instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity; }
    double errorTurns() { return (target - position) * 256 / 1200; }
    void limit(double maximum) { floor = Math.min(floor, maximum); }
    public boolean arrived() {
        if (leader != null) return leader.arrived();
        return Math.abs(position - Math.min(target, floor)) < (elevator() ? 1e-4 : 1e-5)
            && (!elevator() || !mounted || Math.abs(PonderKinetics.of((PonderLevel) pulley.getLevel()).outputSpeed(pulley.getBlockPos())) < .01);
    }
    PonderSimulation.TravelBounds bounds() {
        if (elevator()) return new PonderSimulation.TravelBounds(-position * 256 / 1200,
            (Math.min(floor, ((LinearActuatorAccessor) pulley).differentialcreate$extensionRange()) - position) * 256 / 1200);
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
            * (elevator() ? 2 : attached ? 1 : couplingRatio()), -limit, limit);
        previousAngle = angle;
        double end = Math.min(target, floor);
        // A new command may still encounter the old shaft direction during reversal.
        // Do not turn that coasting work into movement in the requested direction.
        // Before payload assembly only the unloaded rope deployment is shown.
        // Its storyboard stroke must not keep extending while the input spins.
        if (elevator() && attached) position = Math.clamp(position + delta, 0, Math.min(floor, ((LinearActuatorAccessor) pulley).differentialcreate$extensionRange()));
        else if (delta * (end - position) > 0)
            position += Math.copySign(Math.min(Math.abs(end - position), Math.abs(delta)), delta);
        pulley.animateOffset((float) position);
    }
    private Double mountedAngle(PonderKinetics physics) {
        var attachment = physics.attachmentState(pulley.getBlockPos());
        return attachment != null && attachment.active() ? physics.outputAngle(pulley, 1) : null;
    }
}
