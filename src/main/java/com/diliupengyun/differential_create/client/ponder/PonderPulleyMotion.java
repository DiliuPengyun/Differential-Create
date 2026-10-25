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

    PonderPulleyMotion(PulleyBlockEntity pulley) {
        this.pulley = pulley;
        target = position = pulley.offset;
        sampleAngle();
    }
    public void command(double distance, boolean immediate) {
        floor = Double.POSITIVE_INFINITY;
        target = Math.clamp(position + distance, 0, ((LinearActuatorAccessor) pulley).differentialcreate$extensionRange());
        if (immediate) { position = target; pulley.animateOffset((float) position); }
        sampleAngle();
    }
    void limit(double maximum) { floor = Math.min(floor, maximum); }
    public boolean arrived() { return Math.abs(position - Math.min(target, floor)) < 1e-5; }
    private void sampleAngle() {
        var physics = PonderKinetics.of((PonderLevel) pulley.getLevel());
        Double angle = mountedAngle(physics);
        mounted = angle != null;
        if (angle == null) angle = physics.angle(pulley, 1);
        previousAngle = angle == null ? 0 : angle;
    }
    void tick() {
        var physics = PonderKinetics.of((PonderLevel) pulley.getLevel());
        Double angle = mountedAngle(physics);
        boolean attached = angle != null;
        if (angle == null) angle = physics.angle(pulley, 1);
        if (angle == null) return;
        // Mounting replaces the unloaded shaft coordinate with the load coordinate.
        if (mounted != attached) { sampleAngle(); return; }
        double delta = Math.clamp(Math.toRadians(angle - previousAngle) * ContraptionGeometry.LINEAR_RADIUS, -.49, .49);
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
