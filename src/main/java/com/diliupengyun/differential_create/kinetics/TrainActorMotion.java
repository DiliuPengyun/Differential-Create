package com.diliupengyun.differential_create.kinetics;

import java.util.*;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Continuous tools debit the same train/engaged-rotor kinetic energy used for
 * translation. Native actor ticks observe paid feed, never restore a pre-stall speed. */
public final class TrainActorMotion {
    private static final double JOULES_PER_WORK = TrainMechanics.TORQUE_SCALE * 2 * Math.PI;
    private static Frame active;
    private TrainActorMotion() {}
    private static boolean supported(MovementContext context) {
        var b = MovementBehaviour.REGISTRY.get(context.state);
        return b instanceof BlockBreakingMovementBehaviour || b instanceof HarvesterMovementBehaviour
            || MovingDeployerWork.isHand(context) || com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context);
    }
    private static AbstractContraptionEntity root(AbstractContraptionEntity entity) {
        while (entity.getVehicle() instanceof AbstractContraptionEntity parent) entity = parent;
        return entity;
    }
    public static ActorDrive drive(MovementContext context) {
        if (context.world.isClientSide || !supported(context) || context.contraption.entity == null) return null;
        var entity = context.contraption.entity; var root = root(entity);
        if (!(root instanceof CarriageContraptionEntity carriage) || carriage.getCarriage() == null || carriage.getCarriage().train == null) return null;
        return new Drive(entity, carriage, active != null && active.train == carriage.getCarriage().train ? active : null);
    }
    private record Drive(AbstractContraptionEntity entity, CarriageContraptionEntity root, Frame frame) implements ActorDrive {
        @Override public Direction.Axis axis() { return Direction.Axis.Z; }
        @Override public boolean rotation() { return false; }
        @Override public boolean stabilized() { return entity != root; }
        @Override public double workRatio(MovementContext context) { return 1; }
        @Override public double speed() { return frame == null ? Math.abs(root.getCarriage().train.speed) * 512 : frame.speed; }
        @Override public double preferredSpeed() { return 1; }
        @Override public ActorContactPath path(MovementContext context) { return frame == null ? null : frame.paths.get(context); }
        @Override public double travel() { return frame == null ? 0 : frame.travel; }
        @Override public double allowance(double direction) { return frame == null ? Double.POSITIVE_INFINITY : Math.max(0, frame.maxTravel - frame.travel); }
        @Override public boolean motionActive() { return frame != null; }
    }
    private static ActorContactPath.Point point(Vec3 v) { return new ActorContactPath.Point(v.x, v.y, v.z); }
    private static final class Frame {
        final Train train;
        final Map<MovementContext, ActorContactPath> paths = new IdentityHashMap<>();
        final List<ContraptionActorWork> work = new ArrayList<>();
        final List<MovementContext> ports = new ArrayList<>(), hands = new ArrayList<>();
        final double maxTravel;
        double speed, travel;
        Frame(Train train, double distance) {
            this.train = train; maxTravel = Math.abs(distance) * 512; speed = Math.abs(train.speed) * 512;
            for (var carriage : train.carriages) carriage.forEachPresentEntity(entity -> {
                if (entity.getContraption() == null) return;
                Vec3 direction;
                var a = carriage.getLeadingPoint(); var b = carriage.getTrailingPoint();
                if (train.graph != null && a.edge != null && b.edge != null && !a.edge.isInterDimensional() && !b.edge.isInterDimensional())
                    direction = a.getPosition(train.graph).subtract(b.getPosition(train.graph)).normalize();
                else direction = entity.applyRotation(new Vec3(0, 0, 1), 1);
                Vec3 translation = direction.scale(Math.signum(distance) / 512);
                for (var actor : entity.getContraption().getActors()) {
                    var ctx = actor.getRight(); if (ctx == null || !supported(ctx) || entity.getContraption().isHiddenInPortal(ctx.localPos)) continue;
                    var behaviour = MovementBehaviour.REGISTRY.get(ctx.state);
                    var start = entity.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(behaviour.getActiveAreaOffset(ctx)), 1);
                    paths.put(ctx, ActorContactPath.linear(point(start), point(translation)));
                    if (com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(ctx)) ports.add(ctx);
                    else if (MovingDeployerWork.isHand(ctx)) hands.add(ctx);
                    else work.add(ContraptionActorWork.of(ctx));
                }
            });
        }
    }
    public static double feed(Train train, double requestedDistance, List<TrainMechanics.Engine> engines) {
        var frame = new Frame(train, requestedDistance);
        if (frame.work.isEmpty() && frame.ports.isEmpty() && frame.hands.isEmpty()) return requestedDistance;
        if (active != null) throw new IllegalStateException("Nested train tool feed");
        active = frame;
        double before = train.speed * 20;
        double equivalentMass = TrainPhysics.state(train).mass;
        for (var e : engines) if (e.ratio != 0 && e.shiftTime == 0) equivalentMass += e.inertia / Math.pow(TrainMechanics.WHEEL_RADIUS * e.ratio, 2);
        double inertia = equivalentMass * Math.pow(20d / 512, 2) / JOULES_PER_WORK;
        try {
            frame.work.forEach(ContraptionActorWork::manageMotion);
            double remaining = RotationMath.TICK_MINUTES; int events = 0;
            while (remaining > 1e-14 && frame.travel < frame.maxTravel - MachineWork.EPSILON) {
                if (++events > 4096) throw new IllegalStateException("Train cutting events did not converge");
                var phases = new ArrayList<ContraptionActorWork>();
                double limit = frame.maxTravel - frame.travel, torque = 0; boolean immediate = false;
                for (var work : frame.work) {
                    double budget = work.differentialcreate$remainingTravel();
                    if (budget <= MachineWork.EPSILON) { work.differentialcreate$advanceTravel(0); immediate = true; }
                    else {
                        if (Double.isFinite(budget)) { phases.add(work); limit = Math.min(limit, budget); }
                        torque += work.torque(ActorDrive.of(work.context()));
                    }
                }
                if (immediate) continue;
                var docks = com.diliupengyun.differential_create.kinetics.portable.PortableDocking.prepareCart(frame.ports);
                for (var dock : docks) { limit = Math.min(limit, dock.boundary(1)); torque += com.diliupengyun.differential_create.kinetics.portable.PortableDocking.torque(dock.context()); }
                for (var ctx : frame.hands) {
                    var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare(); torque += hand.cartHolding() ? ActorFeed.TORQUE : 0;
                    var path = frame.paths.get(ctx); if (path != null) limit = Math.min(limit, path.exit(frame.travel, 1));
                }
                var charges = MovingDeployerWork.cartCharges(frame.hands, frame.speed, inertia, torque);
                for (var charge : charges.entrySet()) { torque += charge.getValue(); limit = Math.min(limit, charge.getKey().cartChargeBoundary(charge.getValue())); }
                var cut = MinecartMotion.cut(frame.speed, inertia, torque, limit, remaining);
                if (cut.travel() == 0) { frame.speed = cut.speed(); break; }
                frame.travel += cut.travel(); frame.speed = cut.speed();
                double paid = cut.travel();
                charges.forEach((hand, charge) -> hand.charge(charge * paid / 1200));
                com.diliupengyun.differential_create.kinetics.portable.PortableDocking.observe(docks, paid);
                MachineWork.batch(() -> phases.forEach(work -> work.differentialcreate$advanceTravel(paid)));
                remaining = Math.max(0, remaining - cut.minutes());
            }
            train.speed = Math.copySign(frame.speed / 512, train.speed);
            double delta = train.speed * 20 - before;
            for (var e : engines) if (e.ratio != 0 && e.shiftTime == 0) e.omega += delta / (TrainMechanics.WHEEL_RADIUS * e.ratio);
            for (var ctx : frame.hands) ContraptionActorWork.of(ctx).hand().cartTick();
            return Math.copySign(frame.travel / 512, requestedDistance);
        } finally { active = null; }
    }
}
