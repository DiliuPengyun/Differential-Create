package com.diliupengyun.differential_create.kinetics;

import java.util.List;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.nbt.CompoundTag;

/** Built-in tool take-off and passive feed clutch. The output owns the body's mass;
 * the input pays hand work. Neither parking nor release assigns a rotor velocity. */
public final class ActorFeed {
    public static final double TORQUE = 1024, DAMPING = 4096, SETTLED = .05;
    public static final float INPUT_INERTIA = .01f;
    public double speed, angle, inertia, brakeHeat;
    public boolean active, initialized;
    public double brake, coupling;

    public static ActorFeed of(KineticBlockEntity be) { return ElasticKinetics.state(be).actorFeed; }
    public static boolean enabled(KineticBlockEntity be) {
        if (!ContraptionPhysics.manages(be)) return false;
        var feed = of(be);
        var drives = ContraptionPhysics.drives(be);
        boolean present = drives.stream().anyMatch(d -> d.entity().getContraption().getActors().stream()
            .anyMatch(a -> a.getRight() != null && MovingDeployerWork.isHand(a.getRight())));
        if (!present) {
            if (drives.isEmpty() && feed.active && ElasticKinetics.state(be).pendingStructureCount > 0) return true;
            feed.active = false; feed.initialized = false; return false;
        }
        feed.active = true;
        double rotors = .01 * drives.stream().map(ContraptionPhysics.Drive::root).distinct().count();
        double next = rotors + drives.stream().mapToDouble(ContraptionPhysics.Drive::inertia).sum();
        var saved = ElasticKinetics.state(be);
        if (drives.size() < saved.pendingStructureCount) {
            // The parent can tick before its stabilized children reload. Keep
            // the saved output mass until the complete body is present.
            if (feed.initialized) next = Math.max(next, feed.inertia);
            else if (Float.isFinite(saved.pendingStructureInertia))
                next = Math.max(next, saved.pendingStructureInertia - InertiaRegistry.getBaseInertia(be) - INPUT_INERTIA);
        }
        if (!feed.initialized) {
            // An already-moving assembly transfers its existing share of
            // inertia; a newly attached body starts at rest without minting energy.
            var member = ElasticKinetics.state(be);
            double body = next - rotors, previous = InertiaRegistry.getBaseInertia(be) + body;
            feed.speed = Double.isFinite(member.lastInertia) && member.lastInertia >= previous - 32 * Math.ulp(previous)
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
        feed.coupling = hold ? 0 : TORQUE;
        feed.brake = hold ? TORQUE : 0;
        // Native geometric endpoints constrain the output, not the spinning
        // input. A stopped feed at its endpoint must still finish tool work.
        for (var drive : ContraptionPhysics.drives(be)) if (!drive.stabilized() && drive.motionActive()) {
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
    }
    public void read(CompoundTag tag) {
        active = tag.getBoolean("Active"); initialized = tag.getBoolean("Initialized");
        speed = finite(tag.getDouble("Speed")); angle = finite(tag.getDouble("Angle"));
        inertia = Math.max(0, finite(tag.getDouble("Inertia"))); brakeHeat = Math.max(0, finite(tag.getDouble("BrakeHeat")));
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
}
