package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity.CartMovementMode;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.bearing.StabilizedContraption;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/** Native rail displacement, orientation and continuous tools share paid feed. Native
 * actor ticks observe the result; they cannot repeat the cutting work. */
public final class MinecartActorMotion {
    private static Frame active;
    public static boolean preciseStep(net.minecraft.world.entity.Entity entity) { return active != null && active.cart == entity; }
    private MinecartActorMotion() {}
    private static boolean supported(MovementContext context) {
        var behaviour = MovementBehaviour.REGISTRY.get(context.state);
        return behaviour instanceof BlockBreakingMovementBehaviour || behaviour instanceof HarvesterMovementBehaviour || MovingDeployerWork.isHand(context)
            || com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context);
    }
    private static AbstractContraptionEntity root(AbstractContraptionEntity entity) {
        while (entity.getVehicle() instanceof AbstractContraptionEntity parent) entity = parent;
        return entity;
    }
    public static ActorDrive drive(MovementContext context) {
        if (context.world.isClientSide || !supported(context) || context.contraption.entity == null) return null;
        if (active != null) {
            var drive = active.drives.get(context.contraption.entity);
            if (drive != null) return drive;
        }
        var entity = context.contraption.entity; var root = root(entity);
        if (entity.isRemoved() || root.isRemoved() || !(root.getContraption() instanceof MountedContraption)
            || !(root.getVehicle() instanceof AbstractMinecart cart)) return null;
        return new Drive(cart, entity, root, null);
    }
    private record Drive(AbstractMinecart cart, AbstractContraptionEntity entity,
                         AbstractContraptionEntity root, Frame frame) implements ActorDrive {
        @Override public Direction.Axis axis() { return frame != null && frame.turn != null && !frame.turn.pitch ? Direction.Axis.Y : Direction.Axis.Z; }
        @Override public boolean rotation() { return frame != null && frame.turn != null; }
        @Override public boolean stabilized() { return entity != root; }
        @Override public double workRatio(MovementContext context) { return frame == null ? 1 : frame.translation.length() * 512; }
        @Override public double speed() { return frame == null ? cart.getDeltaMovement().horizontalDistance() * 512 : frame.speed; }
        @Override public double preferredSpeed() { return 1; }
        @Override public boolean motionActive() { return frame != null; }
        @Override public double travel() { return frame == null ? 0 : frame.travel; }
        @Override public double angularRate() { return frame == null ? 0 : frame.angularRate; }
        @Override public double allowance(double direction) { return frame == null ? Double.POSITIVE_INFINITY : frame.maxTravel - frame.travel; }
        @Override public ActorContactPath path(MovementContext context) {
            return frame == null || frame.pausedActors || root.isStalled() || root.getContraption().stalled
                || entity.isStalled() || entity.getContraption().stalled ? null : frame.paths.get(context);
        }
        @Override public ActorContactPath directionPath(MovementContext context, Vec3 local) {
            return frame == null || !frame.normalPaths.containsKey(context) ? ActorDrive.super.directionPath(context, local) : frame.normalPaths.get(context);
        }
    }
    private static final class Frame {
        final AbstractMinecart cart;
        final Vec3 direction, translation;
        final double storedRatio;
        final boolean pausedActors;
        final Turn turn;
        final double angularRate, maxTravel;
        final Map<AbstractContraptionEntity, Drive> drives = new IdentityHashMap<>();
        final Map<MovementContext, ActorContactPath> paths = new IdentityHashMap<>();
        final Map<MovementContext, ActorContactPath> normalPaths = new IdentityHashMap<>();
        final List<MovementContext> ports = new ArrayList<>();
        final List<ContraptionActorWork> work = new ArrayList<>();
        final List<MovementContext> hands = new ArrayList<>();
        double speed, travel;
        Frame(AbstractMinecart cart, AbstractContraptionEntity root, Vec3 requested, boolean onRail) {
            this.cart = cart;
            turn = null; angularRate = 0; maxTravel = Double.POSITIVE_INFINITY;
            pausedActors = rotationPause(cart, root);
            double storedSpeed = (onRail ? cart.getDeltaMovement().horizontalDistance() : cart.getDeltaMovement().length()) * 512;
            speed = requested.length() * 512;
            // Feed is actual displacement, even above the native rail cap. Fold
            // the stored-coordinate energy back through this frame's speed ratio.
            storedRatio = speed > 0 ? storedSpeed / speed : 1;
            var state = MinecartPhysics.state(cart);
            direction = requested.lengthSqr() > 0 ? requested.normalize()
                : state.actorDirection.lengthSqr() > 0 ? state.actorDirection : MinecartPhysics.tangent(cart);
            state.actorDirection = direction;
            translation = direction.scale(1.0 / 512);
            cart.positionRider(root);
            for (var entity : MinecartPhysics.structures(cart)) {
                if (entity.getVehicle() instanceof AbstractContraptionEntity parent) parent.positionRider(entity);
                var drive = new Drive(cart, entity, root, this); drives.put(entity, drive);
                for (var actor : entity.getContraption().getActors()) {
                    var context = actor.getRight(); if (context == null || !supported(context)) continue;
                    var behaviour = MovementBehaviour.REGISTRY.get(context.state);
                    boolean portable = com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context);
                    var p = entity.toGlobalVector(Vec3.atCenterOf(context.localPos).add(portable ? Vec3.ZERO : behaviour.getActiveAreaOffset(context)), 1);
                    paths.put(context, ActorContactPath.linear(new ActorContactPath.Point(p.x, p.y, p.z),
                        new ActorContactPath.Point(translation.x, translation.y, translation.z)));
                    if (portable) {
                        ports.add(context);
                        var facing = com.diliupengyun.differential_create.kinetics.portable.PortableDocking.adapter(context).facing(context.state);
                        if (facing != null) normalPaths.put(context, ActorContactPath.linear(point(entity.applyRotation(Vec3.atLowerCornerOf(facing.getNormal()), 1)), point(Vec3.ZERO)));
                    } else if (MovingDeployerWork.isHand(context)) hands.add(context);
                    else work.add(ContraptionActorWork.of(context));
                }
            }
        }
        Frame(AbstractMinecart cart, Turn turn, double speed) {
            this.cart = cart;
            this.turn = turn; this.speed = speed; maxTravel = speed;
            direction = translation = Vec3.ZERO; storedRatio = 1; pausedActors = false;
            angularRate = turn.amount / speed;
            var starts = turn.sample(0);
            var quarter = turn.sample(90);
            var opposite = turn.sample(180);
            var startNormals = turn.sampleNormals(0); var quarterNormals = turn.sampleNormals(90); var oppositeNormals = turn.sampleNormals(180);
            turn.pose(0);
            for (var entity : turn.structures) {
                drives.put(entity, new Drive(cart, entity, turn.root, this));
                for (var actor : entity.getContraption().getActors()) {
                    var context = actor.getRight(); if (context == null || !supported(context)) continue;
                    var start = starts.get(context); var centre = start.add(opposite.get(context)).scale(.5);
                    paths.put(context, ActorContactPath.circular(point(centre), point(start.subtract(centre)),
                        point(quarter.get(context).subtract(centre)), angularRate * Math.PI / 180));
                    if (com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context)) {
                        ports.add(context);
                        var normal = startNormals.get(context);
                        if (normal != null) {
                            var centreNormal = normal.add(oppositeNormals.get(context)).scale(.5);
                            normalPaths.put(context, ActorContactPath.circular(point(centreNormal), point(normal.subtract(centreNormal)),
                                point(quarterNormals.get(context).subtract(centreNormal)), angularRate * Math.PI / 180));
                        }
                    } else if (MovingDeployerWork.isHand(context)) hands.add(context);
                    else work.add(ContraptionActorWork.of(context));
                }
            }
        }
    }
    private static ActorContactPath.Point point(Vec3 vector) { return new ActorContactPath.Point(vector.x, vector.y, vector.z); }
    /** Native yaw and pitch are separate circular sweeps, at the final rail anchor.
     * Tool activation sees the intermediate pose, rather than the destination pose. */
    private static final class Turn {
        final OrientedContraptionEntity root;
        final List<AbstractContraptionEntity> structures;
        final boolean pitch;
        final float initial;
        final double amount;
        Turn(AbstractMinecart cart, OrientedContraptionEntity root, boolean pitch, double amount) {
            this.root = root; this.pitch = pitch; this.amount = amount;
            initial = pitch ? root.pitch : root.yaw;
            structures = MinecartPhysics.structures(cart);
        }
        void pose(double angle) {
            if (pitch) root.pitch = (float) (initial + angle); else root.yaw = (float) (initial + angle);
            for (var entity : structures) if (entity != root && entity.getVehicle() instanceof AbstractContraptionEntity parent) {
                parent.positionRider(entity);
                if (entity instanceof OrientedContraptionEntity child && parent instanceof OrientedContraptionEntity oriented
                    && entity.getContraption() instanceof StabilizedContraption stabilized && !stabilized.getFacing().getAxis().isVertical())
                    child.yaw = Mth.wrapDegrees(child.getInitialYaw() - oriented.getInitialYaw()) + oriented.yaw;
            }
        }
        Map<MovementContext, Vec3> sample(double angle) {
            pose(angle);
            var points = new IdentityHashMap<MovementContext, Vec3>();
            for (var entity : structures) for (var actor : entity.getContraption().getActors()) {
                var context = actor.getRight(); if (context == null || !supported(context)) continue;
                var behaviour = MovementBehaviour.REGISTRY.get(context.state);
                points.put(context, entity.toGlobalVector(Vec3.atCenterOf(context.localPos).add(
                    com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context) ? Vec3.ZERO : behaviour.getActiveAreaOffset(context)), 1));
            }
            return points;
        }
        Map<MovementContext, Vec3> sampleNormals(double angle) {
            pose(angle); var result = new IdentityHashMap<MovementContext, Vec3>();
            for (var entity : structures) for (var actor : entity.getContraption().getActors()) {
                var context = actor.getRight();
                if (context == null || !com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context)) continue;
                var facing = com.diliupengyun.differential_create.kinetics.portable.PortableDocking.adapter(context).facing(context.state);
                if (facing != null) result.put(context, entity.applyRotation(Vec3.atLowerCornerOf(facing.getNormal()), 1));
            }
            return result;
        }
    }
    /** Retain Create's empty-space turn. A tool contact must pay from the same
     * carts' kinetic energy as linear feed; exhaustion limits the accepted angle. */
    public static boolean turn(AbstractMinecart cart, OrientedContraptionEntity root, float previousYaw, float previousPitch, boolean rotating) {
        if (MinecartPhysics.mounted(cart) != root || !hasTools(cart, root)) return false;
        if (rotating && ((MountedContraption) root.getContraption()).rotationMode == CartMovementMode.ROTATE_PAUSED) return true;
        double yaw = Mth.wrapDegrees(root.yaw - previousYaw), pitch = Mth.wrapDegrees(root.pitch - previousPitch);
        if (Math.abs(yaw) + Math.abs(pitch) < 1e-7) return true;
        root.yaw = previousYaw; root.pitch = previousPitch;
        if (root.isStalled() || root.getContraption().stalled) return true;
        if (Math.abs(yaw) > 1e-7) turn(cart, new Turn(cart, root, false, yaw));
        if (Math.abs(pitch) > 1e-7) turn(cart, new Turn(cart, root, true, pitch));
        return true;
    }
    private record Support(AbstractMinecart cart, Vec3 velocity, double inertia, boolean movable) {
        double energy() { return movable ? .5 * inertia * velocity.lengthSqr() * 512 * 512 : 0; }
    }
    private static void turn(AbstractMinecart cart, Turn turn) {
        var supports = new ArrayList<Support>();
        double inertia = 0, energy = 0;
        for (var support : MinecartPhysics.supports(cart)) {
            double j = MinecartPhysics.refresh(support);
            boolean movable = !support.level().tickRateManager().isEntityFrozen(support)
                && !support.getData(AllAttachmentTypes.MINECART_CONTROLLER).isStalled();
            var entry = new Support(support, support.getDeltaMovement(), j, movable);
            supports.add(entry); inertia += entry.inertia; energy += entry.energy();
        }
        if (!(energy > 0)) return;
        double initialSpeed = Math.sqrt(2 * energy / inertia);
        var frame = new Frame(cart, turn, initialSpeed);
        if (active != null) throw new IllegalStateException("Nested minecart turning feed");
        active = frame;
        try {
            frame.work.forEach(ContraptionActorWork::manageMotion);
            double remaining = RotationMath.TICK_MINUTES;
            int events = 0;
            while (remaining > 1e-14 && frame.travel < frame.maxTravel - MachineWork.EPSILON && !turn.root.isRemoved()) {
                if (++events > 4096) throw new IllegalStateException("Minecart turning events did not converge");
                var phases = new ArrayList<ContraptionActorWork>();
                double limit = frame.maxTravel - frame.travel, torque = 0;
                boolean immediate = false;
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
                    var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare();
                    torque += hand.cartHolding() ? ActorFeed.TORQUE : 0;
                    var path = frame.paths.get(ctx); if (path != null) limit = Math.min(limit, path.exit(frame.travel, 1));
                }
                var charges = MovingDeployerWork.cartCharges(frame.hands, frame.speed, inertia, torque);
                for (var entry : charges.entrySet()) {
                    torque += entry.getValue(); limit = Math.min(limit, entry.getKey().cartChargeBoundary(entry.getValue()));
                }
                var cut = MinecartMotion.cut(frame.speed, inertia, torque, limit, remaining);
                if (cut.travel() == 0) break;
                frame.travel += cut.travel(); frame.speed = cut.speed();
                turn.pose(frame.travel * frame.angularRate);
                for (var support : supports) {
                    if (!support.movable) continue;
                    support.cart.setDeltaMovement(support.velocity.scale(frame.speed / initialSpeed));
                    MinecartPhysics.state(support.cart).actorWork += torque * cut.travel() / 1200 * support.energy() / energy;
                }
                com.diliupengyun.differential_create.kinetics.portable.PortableDocking.observe(docks, cut.travel());
                charges.forEach((hand, charge) -> hand.charge(charge * cut.travel() / 1200));
                MachineWork.batch(() -> phases.forEach(work -> work.differentialcreate$advanceTravel(cut.travel())));
                remaining = Math.max(0, remaining - cut.minutes());
            }
        } finally { active = null; }
    }
    /** Preserve Create's setting which suppresses actor visits until alignment.
     * Stored rail velocity already has the native tangent projection here. */
    private static boolean rotationPause(AbstractMinecart cart, AbstractContraptionEntity root) {
        if (!(root instanceof OrientedContraptionEntity oriented) || oriented.getCouplingId() != null
            || ((MountedContraption) root.getContraption()).rotationMode != CartMovementMode.ROTATE_PAUSED
            || root.isStalled()) return false;
        var motion = cart.getDeltaMovement().normalize();
        if (cart.isOnRails()) motion = new Vec3(motion.x, 0, motion.z);
        double target = motion.lengthSqr() > 0 ? AbstractContraptionEntity.yawFromVector(motion) : oriented.targetYaw;
        double approach = motion.length() * 12 / Math.max(1, root.getBoundingBox().getXsize() / 6);
        return Math.abs(Mth.wrapDegrees(target - oriented.yaw)) >= approach + 1;
    }
    private static boolean hasTools(AbstractMinecart cart, AbstractContraptionEntity root) {
        return MinecartPhysics.enabled(cart) && root != null && MinecartPhysics.structures(cart).stream()
            .flatMap(e -> e.getContraption().getActors().stream()).anyMatch(a -> a.getRight() != null && supported(a.getRight()));
    }
    public static void move(AbstractMinecart cart, BlockPos rail) {
        var root = MinecartPhysics.mounted(cart);
        if (!hasTools(cart, root)) {
            cart.moveMinecartOnRail(rail); return;
        }
        MinecartPhysics.refresh(cart);
        double factor = MinecartPhysics.occupied(cart) ? .75 : 1, max = cart.getMaxSpeedWithRail();
        var v = cart.getDeltaMovement();
        var requested = new Vec3(Math.clamp(factor * v.x, -max, max), 0, Math.clamp(factor * v.z, -max, max));
        feed(cart, root, requested, true, MoverType.SELF);
    }
    public static void offRail(AbstractMinecart cart, MoverType type, Vec3 requested) {
        var root = MinecartPhysics.mounted(cart);
        if (!hasTools(cart, root)) { cart.move(type, requested); return; }
        feed(cart, root, requested, false, type);
    }
    private static void feed(AbstractMinecart cart, AbstractContraptionEntity root, Vec3 requested, boolean onRail, MoverType type) {
        double inertia = MinecartPhysics.refresh(cart);
        if (active != null) throw new IllegalStateException("Nested minecart cutting feed");
        var frame = new Frame(cart, root, requested, onRail); active = frame;
        try {
            frame.work.forEach(ContraptionActorWork::manageMotion);
            if (root.isStalled() || root.getContraption().stalled) frame.speed = 0;
            var state = MinecartPhysics.state(cart); state.actorWork = 0;
            double j = inertia * frame.storedRatio * frame.storedRatio, remaining = RotationMath.TICK_MINUTES;
            int events = 0;
            while (remaining > 1e-14 && !root.isRemoved()) {
                if (++events > 4096) throw new IllegalStateException("Minecart cutting events did not converge");
                var phases = new ArrayList<ContraptionActorWork>();
                double limit = Double.POSITIVE_INFINITY, torque = 0;
                boolean immediate = false;
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
                    var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare();
                    torque += hand.cartHolding() ? ActorFeed.TORQUE : 0;
                    var path = frame.paths.get(ctx); if (path != null) limit = Math.min(limit, path.exit(frame.travel, 1));
                }
                var charges = MovingDeployerWork.cartCharges(frame.hands, frame.speed, j, torque);
                for (var entry : charges.entrySet()) {
                    torque += entry.getValue(); limit = Math.min(limit, entry.getKey().cartChargeBoundary(entry.getValue()));
                }
                var cut = MinecartMotion.cut(frame.speed, j, torque, limit, remaining);
                if (cut.travel() == 0) break;
                var movement = frame.translation.scale(cut.travel()); var before = cart.position();
                cart.setDeltaMovement(frame.direction.scale(cut.speed() * frame.storedRatio / 512)); cart.move(type, movement);
                var actual = cart.position().subtract(before);
                double accepted = actual.lengthSqr() == 0 ? 0 : actual.subtract(movement).lengthSqr() <= 1e-14 ? cut.travel()
                    : Math.min(cut.travel(), Math.max(0, actual.dot(frame.translation) / frame.translation.lengthSqr()));
                frame.travel += accepted; frame.speed = (onRail ? cart.getDeltaMovement().horizontalDistance() : cart.getDeltaMovement().length()) * 512 / frame.storedRatio;
                state.actorWork += torque * accepted / 1200;
                double chargedTravel = accepted;
                charges.forEach((hand, charge) -> hand.charge(charge * chargedTravel / 1200));
                com.diliupengyun.differential_create.kinetics.portable.PortableDocking.observe(docks, accepted);
                double paid = accepted;
                MachineWork.batch(() -> phases.forEach(work -> work.differentialcreate$advanceTravel(paid)));
                remaining = Math.max(0, remaining - cut.minutes());
                if (accepted + MachineWork.EPSILON < cut.travel()) break;
            }
        } finally {
            active = null;
            // Permission checks after rail feed must see the accepted anchor,
            // including before the contraption entity receives its own tick.
            for (var entity : MinecartPhysics.structures(cart)) {
                var parent = entity.getVehicle();
                if (parent != null) parent.positionRider(entity);
            }
        }
    }
    public static void tickHands(AbstractMinecart cart) {
        if (cart.level().isClientSide || !MinecartPhysics.enabled(cart)) return;
        for (var entity : MinecartPhysics.structures(cart)) for (var actor : entity.getContraption().getActors()) {
            var ctx = actor.getRight();
            if (ctx != null && MovingDeployerWork.isHand(ctx)) ContraptionActorWork.of(ctx).hand().cartTick();
        }
    }
}
