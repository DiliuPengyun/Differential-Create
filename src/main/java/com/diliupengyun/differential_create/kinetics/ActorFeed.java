package com.diliupengyun.differential_create.kinetics;

import java.util.List;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.nbt.CompoundTag;

/** Structural output and optional tool take-off. The output owns the body's mass;
 * the input pays hand work. Parking uses a bounded clutch and brake. */
public final class ActorFeed {
    public static final double TORQUE = 1024, DAMPING = 4096, SETTLED = .05;
    public static final float INPUT_INERTIA = .01f;
    public double speed, angle, inertia, brakeHeat, twist;
    public boolean active, initialized, toolTakeoff;
    public double brake, coupling;
    public double stiffness() { return toolTakeoff ? 0 : PhysicsTuning.TORSIONAL_STIFFNESS; }
    public double damping() { return toolTakeoff ? DAMPING : PhysicsTuning.TORSIONAL_DAMPING; }
    public double elasticEnergy() { return active ? .5 * stiffness() * twist * twist : 0; }

    public static ActorFeed of(KineticBlockEntity be) { return ElasticKinetics.state(be).actorFeed; }
    public static boolean enabled(KineticBlockEntity be) {
        if (!ContraptionPhysics.manages(be)) return false;
        var feed = of(be);
        var drives = ContraptionPhysics.drives(be);
        boolean present = drives.stream().anyMatch(d -> d.entity().getContraption().getActors().stream()
            .anyMatch(a -> a.getRight() != null && MovingDeployerWork.isHand(a.getRight())));
        boolean linear = be instanceof com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity
            || be instanceof com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
        if (!present && (!linear || drives.isEmpty())) {
            if (drives.isEmpty() && feed.active && ElasticKinetics.state(be).pendingStructureCount > 0) return true;
            feed.active = false; feed.initialized = false; feed.twist = 0; return false;
        }
        feed.toolTakeoff = present;
        feed.active = true;
        double rotors = present ? .01 * drives.stream().map(ContraptionPhysics.Drive::root).distinct().count() : 0;
        double next = rotors + drives.stream().mapToDouble(ContraptionPhysics.Drive::inertia).sum();
        var saved = ElasticKinetics.state(be);
        if (drives.size() < saved.pendingStructureCount) {
            // The parent can tick before its stabilized children reload. Keep
            // the saved output mass until the complete body is present.
            if (feed.initialized) next = Math.max(next, feed.inertia);
            else if (Float.isFinite(saved.pendingStructureInertia))
                next = Math.max(next, saved.pendingStructureInertia - InertiaRegistry.getBaseInertia(be) - (present ? INPUT_INERTIA : 0));
        }
        if (!feed.initialized) {
            // An already-moving assembly transfers its existing share of
            // inertia; a newly attached body starts at rest without minting energy.
            var member = ElasticKinetics.state(be);
            double body = next - rotors, previous = InertiaRegistry.getBaseInertia(be) + body;
            feed.speed = next > 0 && Double.isFinite(member.lastInertia) && member.lastInertia >= previous - 32 * Math.ulp(previous)
                ? NetworkDynamics.preciseSpeed(be) * body / next : 0;
            feed.initialized = true;
        } else if (next > feed.inertia && feed.inertia > 0) feed.speed *= feed.inertia / next;
        feed.inertia = next;
        return true;
    }
    public static double speed(KineticBlockEntity be) {
        return enabled(be) ? of(be).speed : NetworkDynamics.preciseSpeed(be);
    }
    public static void prepare(KineticBlockEntity be) {
        if (!enabled(be)) return;
        var feed = of(be); boolean hold = false;
        for (var drive : ContraptionPhysics.drives(be)) for (var pair : drive.entity().getContraption().getActors()) {
            MovementContext ctx = pair.getRight();
            if (ctx == null || !MovingDeployerWork.isHand(ctx)) continue;
            var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare(); hold |= hand.holding();
        }
        feed.coupling = hold ? 0 : feed.toolTakeoff ? TORQUE : StrengthRegistry.of(be);
        feed.brake = hold ? TORQUE : 0;
        if (ContraptionMotion.stop(be) == 2) feed.coupling = 0;
        // Native geometric endpoints constrain the output, not the spinning
        // input. A stopped feed at its endpoint must still finish tool work.
        for (var drive : ContraptionPhysics.drives(be)) if (!drive.stabilized() && drive.motionActive()) {
            if (drive.root().isStalled() || drive.root().getContraption().stalled
                || be instanceof com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity actuator
                    && ((com.diliupengyun.differential_create.mixin.LinearActuatorAccessor) actuator).differentialcreate$waiting()) {
                feed.coupling = 0; continue;
            }
            double sign = Math.signum(feed.speed == 0 ? drive.preferredSpeed() : feed.speed);
            if (sign != 0 && drive.allowance(sign) <= MachineWork.EPSILON) {
                feed.coupling = 0; feed.brake = TORQUE;
            }
        }
    }
    public static void boundaries(List<com.simibubi.create.content.kinetics.KineticNetwork> networks,
                                  List<ElasticKinetics.WorkEvent> limits) {
        for (var network : networks) for (var be : network.members.keySet()) if (enabled(be))
            for (var drive : ContraptionPhysics.drives(be)) for (var actor : drive.entity().getContraption().getActors()) {
                var ctx = actor.getRight(); if (ctx == null || !MovingDeployerWork.isHand(ctx)) continue;
                var path = drive.path(ctx); if (path == null) continue;
                limits.add(new ElasticKinetics.WorkEvent(be, new ShaftWork.Boundary(Double.POSITIVE_INFINITY,
                    Math.min(drive.allowance(1), path.exit(drive.travel(), 1)),
                    Math.min(drive.allowance(-1), path.exit(drive.travel(), -1))), 0, true));
            }
    }
    public void write(CompoundTag tag) {
        tag.putBoolean("Active", active); tag.putBoolean("Initialized", initialized);
        tag.putDouble("Speed", speed); tag.putDouble("Angle", angle); tag.putDouble("Inertia", inertia);
        tag.putDouble("BrakeHeat", brakeHeat);
        tag.putDouble("Twist", twist); tag.putBoolean("ToolTakeoff", toolTakeoff);
    }
    public void read(CompoundTag tag) {
        active = tag.getBoolean("Active"); initialized = tag.getBoolean("Initialized");
        speed = finite(tag.getDouble("Speed")); angle = finite(tag.getDouble("Angle"));
        inertia = Math.max(0, finite(tag.getDouble("Inertia"))); brakeHeat = Math.max(0, finite(tag.getDouble("BrakeHeat")));
        twist = finite(tag.getDouble("Twist")); toolTakeoff = !tag.contains("ToolTakeoff") || tag.getBoolean("ToolTakeoff");
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
}
