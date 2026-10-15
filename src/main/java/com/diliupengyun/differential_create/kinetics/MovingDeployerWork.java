package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.mixin.DeployerMovementAccessor;
import com.diliupengyun.differential_create.mixin.DeployerPlayerAccessor;
import com.diliupengyun.differential_create.mixin.DeployerHandlerInvoker;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.apache.commons.lang3.tuple.Pair;

/** Paid moving hand cycles. Native interaction callbacks execute at real contact;
 * native stall/tick timers are never a source of motion or breaking work. */
public final class MovingDeployerWork {
    public static final String TAG = "DCMovingHand";
    public static final double STROKE = 500, CART_INERTIA = .05, CART_CAPACITY = 256;
    // A quick use press has a quarter-block total stroke, like other continuous
    // actors. Mining keeps the full swing. Both halves still pay actual work.
    public static final double USE_STROKE = ContraptionActorWork.FEED_DISTANCE * 512 / 2;
    public static final double CART_CHARGE_TORQUE = 128, CART_SHARED_CHARGE_TORQUE = 256;
    private final MovementContext context;
    public boolean firing;
    private boolean restored;
    public MovingDeployerWork(MovementContext context) { this.context = context; }
    public static boolean isHand(MovementContext ctx) {
        return MovementBehaviour.REGISTRY.get(ctx.state) instanceof DeployerMovementBehaviour;
    }
    public static boolean supported(MovementContext ctx) {
        return isHand(ctx) && ActorDrive.of(ctx) != null;
    }
    private CompoundTag data() {
        if (!context.data.contains(TAG)) context.data.put(TAG, new CompoundTag());
        return context.data.getCompound(TAG);
    }
    private DeployerMovementBehaviour behaviour() { return (DeployerMovementBehaviour) MovementBehaviour.REGISTRY.get(context.state); }
    public DeployerFakePlayer player() { return ((DeployerMovementAccessor) behaviour()).differentialcreate$player(context); }
    private DeployerPlayerAccessor progress() { return (DeployerPlayerAccessor) player(); }
    private boolean punch() { return context.blockEntityData != null && context.blockEntityData.getString("Mode").equals("PUNCH"); }
    public int phase() { return data().getInt("Phase"); }
    public boolean pending() { return phase() != 0; }
    public boolean holding() {
        if (!pending() || context.disabled) return false;
        if (data().getBoolean("Hold")) return true;
        if (phase() != 1) return false;
        var drive = ActorDrive.of(context);
        if (!(drive instanceof ContraptionPhysics.Drive shaft)) return false;
        double speed = Math.abs(NetworkDynamics.preciseSpeed(shaft.shaft()));
        double time = speed <= 1e-8 ? Double.POSITIVE_INFINITY : Math.max(0, stroke() - data().getDouble("Travel")) / speed;
        return needsContactBrake(drive, time);
    }
    private double stroke() { return pending() ? data().getDouble("Stroke") : punch() ? STROKE : USE_STROKE; }
    public BlockPos target() { return BlockPos.of(data().getLong("Target")); }
    public double extension() {
        double travel = Math.clamp(data().getDouble("Travel"), 0, stroke());
        return phase() == 1 ? .5 * travel / stroke() : phase() == 2 ? .5 * (data().getDouble("Return") - travel) / stroke() : 0;
    }
    public String failure() { return data().getString("Failure"); }
    private BlockPos pose() {
        var drive = ActorDrive.of(context); if (drive == null) return null;
        var path = drive.path(context); double speed = drive.speed();
        double sign = Math.signum(speed == 0 ? drive.preferredSpeed() : speed); if (sign == 0) sign = 1;
        if (path != null) {
            context.position = ContraptionMotion.vector(path.inside(drive.travel(), sign));
            context.motion = ContraptionMotion.vector(path.derivative(drive.travel())).scale(speed);
            context.rotation = v -> drive.stabilized() || !drive.rotation() ? drive.entity().applyRotation(v, 1)
                : ContraptionMotion.rotate(drive.entity().applyRotation(v, 1), drive.angle(), drive.axis());
        } else {
            var point = drive.entity().toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour().getActiveAreaOffset(context)), 1);
            context.position = drive instanceof ContraptionPhysics.Drive shaft ? ContraptionMotion.pendingPosition(shaft, point) : point;
            context.rotation = v -> drive instanceof ContraptionPhysics.Drive shaft
                ? ContraptionMotion.pendingDirection(shaft, drive.entity().applyRotation(v, 1)) : drive.entity().applyRotation(v, 1);
            context.motion = Vec3.ZERO;
        }
        context.relativeMotion = drive.entity().reverseRotation(context.motion, 1);
        return BlockPos.containing(context.position);
    }
    public void visit(BlockPos ignored) { prepare(); }
    public void prepare() {
        if (context.world.isClientSide || !supported(context)) return;
        MovingHandSync.track(context);
        context.stall = false; context.data.remove("Timer");
        var cell = pose(); if (cell == null || !context.world.isLoaded(cell)) return;
        var d = data();
        String mode = punch() ? "PUNCH" : "USE";
        if (d.contains("Mode") && !mode.equals(d.getString("Mode"))) {
            if (pending()) cancel("changed_mode");
            d.putBoolean("Visited", false);
        }
        d.putString("Mode", mode);
        // Use mode feeds continuously during extension and retraction.
        if (!punch()) d.putBoolean("Hold", false);
        if (pending()) ActorTargets.watch(ContraptionActorWork.of(context), target());
        if (context.data.getBoolean("DCActorInvalidated")) {
            context.data.remove("DCActorInvalidated");
            cancel("changed_target"); d.putBoolean("Visited", false);
        }
        if (!restored) {
            restored = true;
            if (pending() && valid(cell) && d.getFloat("Breaking") > 0)
                progress().differentialcreate$breaking(Pair.of(target(), d.getFloat("Breaking")));
        }
        if (pending()) {
            if (phase() == 1 && (context.disabled || !valid(cell))) cancel("lost_target");
            else if (phase() == 2 && !valid(cell)) { clearBreaking(); d.putBoolean("Hold", false); releaseCarrier(); }
            return;
        }
        if (context.disabled) return;
        if (d.getBoolean("Visited") && d.getLong("Cell") == cell.asLong()) {
            if (d.getString("FinishedState").equals(context.world.getBlockState(cell).toString())) return;
        }
        ((DeployerMovementAccessor) behaviour()).differentialcreate$grab(context);
        var held = player().getMainHandItem();
        boolean entity = !context.world.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(cell)).isEmpty();
        if (!punch() && held.isEmpty() && context.world.getBlockState(cell).isAir() && !entity
            && !com.simibubi.create.AllItems.SCHEMATIC.isIn(context.getFilterFromBE().item())) return;
        if (punch()) {
            if (!entity && (context.world.getBlockState(cell).isAir()
                || context.world.getBlockState(cell).getDestroySpeed(context.world, cell) < 0)) return;
        } else if (!DeployerHandlerInvoker.differentialcreate$shouldActivate(held, context.world, cell, null)) return;
        if (!punch() && !entity && context.world.getBlockState(cell).isAir()
            && held.getItem() instanceof net.minecraft.world.item.BlockItem item
            && item.getBlock() instanceof net.minecraft.world.level.block.BushBlock
            && !item.getBlock().defaultBlockState().canSurvive(context.world, cell)) return;
        clearBreaking(); d.putLong("Target", cell.asLong());
        ActorTargets.watch(ContraptionActorWork.of(context), cell);
        d.putString("State", context.world.getBlockState(cell).toString());
        boolean mining = punch() && !held.isEmpty() && !context.world.getBlockState(cell).isAir()
            && context.world.getBlockState(cell).getDestroyProgress(player(), context.world, cell) > 0;
        d.putDouble("Stroke", mining ? STROKE : USE_STROKE);
        d.putInt("Phase", 1); d.putDouble("Travel", 0); d.putBoolean("Hold", mining);
        d.putBoolean("Completed", false); d.putString("Failure", "");
    }
    private boolean valid(BlockPos cell) {
        return cell.equals(target()) && context.world.isLoaded(target())
            && context.world.getBlockState(target()).toString().equals(data().getString("State"));
    }
    public double remaining() {
        prepare(); if (!pending()) return Double.POSITIVE_INFINITY;
        if (phase() == 1 && data().getBoolean("Hold")) {
            var drive = ActorDrive.of(context);
            if (drive instanceof ContraptionPhysics.Drive && Math.abs(drive.speed()) > ActorFeed.SETTLED)
                return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, (phase() == 2 ? data().getDouble("Return") : stroke()) - data().getDouble("Travel"));
    }
    public double torque() {
        if (!Double.isFinite(remaining())) return 0;
        double impact = BlockStressValues.getImpact(context.state.getBlock()); return impact > 0 ? impact : 2;
    }
    public void advance(double travel) {
        double remaining = remaining(); if (!Double.isFinite(remaining)) return;
        double paid = Math.min(Math.max(0, travel), remaining);
        var d = data(); d.putDouble("Travel", d.getDouble("Travel") + paid);
        if (paid > MachineWork.EPSILON && failure().equals("no_power")) d.putString("Failure", "");
        d.putDouble("Work", d.getDouble("Work") + torque() * paid / 1200);
        if (remaining <= travel + MachineWork.EPSILON) MachineWork.event(this::finishPhase);
    }
    private void finishPhase() {
        var d = data();
        if (phase() == 1) {
            var cell = pose(); if (cell == null || !valid(cell) || context.disabled) { cancel("lost_target"); return; }
            firing = true;
            try { behaviour().visitNewPosition(context, target()); }
            finally { firing = false; context.stall = false; context.data.remove("Timer"); }
            var breaking = progress().differentialcreate$breaking();
            d.putFloat("Breaking", breaking == null ? 0 : breaking.getRight());
            d.putString("State", context.world.getBlockState(target()).toString());
            d.putInt("Phase", 2); d.putDouble("Return", stroke()); d.putDouble("Travel", 0);
            d.putBoolean("Completed", true);
            d.putInt("Hits", d.getInt("Hits") + 1);
            if (!punch()) releaseCarrier();
        } else if (phase() == 2) {
            var cell = pose(); var breaking = progress().differentialcreate$breaking();
            if (d.getBoolean("Hold") && !context.disabled && punch() && breaking != null && cell != null && valid(cell)) {
                d.putInt("Phase", 1); d.putDouble("Travel", 0);
            } else {
                clearBreaking(); d.putBoolean("Visited", d.getBoolean("Completed")); d.putLong("Cell", target().asLong());
                d.putString("FinishedState", d.getString("State"));
                d.putInt("Phase", 0); d.putDouble("Travel", 0); d.putBoolean("Hold", false);
                releaseCarrier();
            }
        }
    }
    public void cancel(String reason) {
        if (!pending()) return;
        var d = data(); double amount = extension() * 2 * stroke();
        clearBreaking(); d.putBoolean("Hold", false); d.putString("Failure", reason);
        d.putBoolean("Completed", false);
        d.putInt("Phase", amount > MachineWork.EPSILON ? 2 : 0); d.putDouble("Return", amount); d.putDouble("Travel", 0);
        releaseCarrier();
    }
    private void releaseCarrier() {
        var drive = ActorDrive.of(context); if (drive == null) return;
        for (net.minecraft.world.entity.Entity rider = drive.entity(); rider != null; rider = rider.getVehicle()) {
            if (rider instanceof net.minecraft.world.entity.vehicle.AbstractMinecart cart) {
                // A known direction allows the next powered rail impulse to
                // restart after a real hand stop. Other parked hands still
                // resist that finite impulse through their own brakes.
                if (cart.getDeltaMovement().horizontalDistanceSqr() <= 1e-16) MinecartPhysics.state(cart).dockingStart = true;
                return;
            }
        }
    }
    private void clearBreaking() {
        ActorTargets.forget(ContraptionActorWork.of(context));
        var breaking = progress().differentialcreate$breaking();
        if (breaking != null) context.world.destroyBlockProgress(player().getId(), breaking.getLeft(), -1);
        progress().differentialcreate$breaking(null); data().putFloat("Breaking", 0);
    }
    public double cartEnergy() { return Math.max(0, data().getDouble("Energy")); }
    private double cartChargeTarget() {
        if (punch() && !player().getMainHandItem().isEmpty()) return CART_CAPACITY;
        // Ordinary use actions need a small working reserve, rather than a
        // full mining flywheel in every planter before the carrier can move.
        double impact = BlockStressValues.getImpact(context.state.getBlock());
        return Math.min(CART_CAPACITY, 4 * 2 * USE_STROKE * (impact > 0 ? impact : 2) / 1200);
    }
    public double cartChargeTorque() {
        prepare();
        if (context.disabled && !pending() || !pending() && !punch() && player().getMainHandItem().isEmpty()) return 0;
        return cartChargeTarget() - cartEnergy() > 1e-9 ? CART_CHARGE_TORQUE : 0;
    }
    /** One passive take-off serves the whole carrier. At low speed, charging
     * can consume at most half a tick's available momentum, after other work.
     * Pending actions receive the budget before idle reserve rotors. */
    public static java.util.Map<MovingDeployerWork, Double> cartCharges(java.util.List<MovementContext> contexts,
                                                                      double speed, double inertia, double otherTorque) {
        var active = new java.util.ArrayList<MovingDeployerWork>();
        var idle = new java.util.ArrayList<MovingDeployerWork>();
        for (var ctx : contexts) {
            var hand = ContraptionActorWork.of(ctx).hand();
            if (hand.cartChargeTorque() > 0) (hand.pending() ? active : idle).add(hand);
        }
        var charges = new java.util.IdentityHashMap<MovingDeployerWork, Double>();
        double budget = Math.min(CART_SHARED_CHARGE_TORQUE,
            Math.max(0, .5 * inertia * Math.abs(speed) / RotationMath.TICK_MINUTES - otherTorque));
        for (var group : java.util.List.of(active, idle)) {
            if (group.isEmpty() || budget <= 0) continue;
            double share = Math.min(CART_CHARGE_TORQUE, budget / group.size());
            for (var hand : group) charges.put(hand, share);
            budget = Math.max(0, budget - share * group.size());
        }
        return charges;
    }
    public double cartChargeBoundary(double torque) {
        return torque > 0 ? Math.max(0, cartChargeTarget() - cartEnergy()) * 1200 / torque : Double.POSITIVE_INFINITY;
    }
    public boolean cartHolding() {
        if (!pending() || context.disabled) return false;
        if (!data().getBoolean("Hold")) {
            if (phase() != 1) return false;
            double impact = BlockStressValues.getImpact(context.state.getBlock());
            double cost = Math.max(0, stroke() - data().getDouble("Travel")) * (impact > 0 ? impact : 2) / 1200;
            // A rail-powered hand cannot recharge at rest. Only brake for a
            // quick use when its remaining contact action is already funded.
            var drive = ActorDrive.of(context);
            double remaining = Math.max(0, stroke() - data().getDouble("Travel"));
            double speed = cartSpeed(), deceleration = (impact > 0 ? impact : 2) * RotationMath.TICK_MINUTES / workInertia();
            double endSpeed = Math.sqrt(Math.max(0, speed * speed - 2 * deceleration * remaining));
            double time = remaining == 0 ? 0 : speed == 0 ? Double.POSITIVE_INFINITY : 2 * remaining / (speed + endSpeed);
            return cartEnergy() + 1e-9 >= cost && drive != null && needsContactBrake(drive, time);
        }
        double cycles = 0;
        if (punch() && context.world.isLoaded(target()) && !context.world.getBlockState(target()).isAir()) {
            double progress = context.world.getBlockState(target()).getDestroyProgress(player(), context.world, target()) * 16;
            if (!(progress > 0)) return false;
            cycles = Math.ceil(Math.max(0, 1 - data().getFloat("Breaking")) / progress);
        }
        double travel = phase() == 1 ? Math.max(0, stroke() - data().getDouble("Travel")) + stroke()
            + Math.max(0, cycles - 1) * 2 * stroke()
            : Math.max(0, data().getDouble("Return") - data().getDouble("Travel")) + cycles * 2 * stroke();
        return cartEnergy() + 1e-9 >= travel * Math.max(2, BlockStressValues.getImpact(context.state.getBlock())) / 1200;
    }
    private boolean needsContactBrake(ActorDrive drive, double requiredTicks) {
        var path = drive.path(context); if (path == null) return false;
        double speed = Math.abs(drive.speed());
        if (!Double.isFinite(requiredTicks)) return true;
        double direction = Math.signum(drive.speed() == 0 ? drive.preferredSpeed() : drive.speed());
        if (direction == 0) direction = 1;
        double contact = path.exit(drive.travel(), direction);
        double needed = requiredTicks * speed;
        // Leave one common-clock substep to engage the finite brake before
        // contact is lost. Retraction never requests this positioning brake.
        // A long stopping distance alone is not a reason to brake: a moving
        // heavy planter may finish well before contact ends without stopping.
        // Once braking is needed, the real output inertia determines whether
        // the finite brake can preserve contact; overspeed can still lose it.
        return needed + speed / 8 >= contact;
    }
    private double workInertia() {
        // The quick-use gearing reflects a lighter effective rotor while
        // preserving the saved energy exactly when changing modes.
        double length = pending() ? stroke() : punch() && !player().getMainHandItem().isEmpty() ? STROKE : USE_STROKE;
        return CART_INERTIA * Math.pow(length / STROKE, 2);
    }
    public double cartSpeed() { return Math.sqrt(2 * cartEnergy() / workInertia()); }
    public void charge(double work) { data().putDouble("Energy", Math.min(CART_CAPACITY, cartEnergy() + Math.max(0, work))); }
    /** A finite static brake balances a motor impulse at rest; moving carts
     * dissipate the same torque through their accepted contact displacement. */
    public static Vec3 balanceDrive(net.minecraft.world.entity.vehicle.AbstractMinecart cart, Vec3 before, Vec3 proposed, double inertia) {
        if (before.horizontalDistanceSqr() > 1e-16) return proposed;
        double capacity = 0;
        for (var entity : MinecartPhysics.structures(cart)) for (var actor : entity.getContraption().getActors()) {
            var ctx = actor.getRight();
            if (ctx != null && supported(ctx)) {
                var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare();
                if (hand.cartHolding()) capacity += ActorFeed.TORQUE;
            }
        }
        double momentum = inertia * proposed.subtract(before).horizontalDistance() * MinecartMotion.RPM_PER_SPEED;
        if (momentum == 0 || capacity == 0) return proposed;
        double resisted = Math.min(momentum, capacity * RotationMath.TICK_MINUTES);
        return before.add(proposed.subtract(before).scale(1 - resisted / momentum));
    }
    public void cartTick() {
        if (context.world.isClientSide || context.world.tickRateManager().isFrozen()) return;
        var d = data(); long tick = context.world.getGameTime(); if (d.getLong("Tick") == tick) return; d.putLong("Tick", tick);
        double time = RotationMath.TICK_MINUTES;
        for (int i = 0; i < 128 && time > 1e-14; i++) {
            prepare(); double budget = remaining(); if (!Double.isFinite(budget)) break;
            double initial = cartSpeed(); if (initial <= 1e-8) { d.putString("Failure", "no_power"); break; }
            double inertia = workInertia();
            var result = TorsionSolver.advance(java.util.List.of(new TorsionSolver.Body(inertia, 0, 0, torque(), 0)),
                java.util.List.of(), new double[]{initial}, new double[0], time, PhysicsTuning.TORSION_MAX_STEP);
            double travel = result.travel()[0] / RotationMath.TICK_MINUTES;
            if (travel > budget) {
                result = TorsionSolver.prepare(java.util.List.of(new TorsionSolver.Body(inertia, 0, 0, torque(), 0)),
                    java.util.List.of(), new double[]{initial}, new double[0]).advanceToEvent(time, PhysicsTuning.TORSION_MAX_STEP,
                    java.util.List.of(new TorsionSolver.EventLimit(0, budget * RotationMath.TICK_MINUTES, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)));
                travel = result.travel()[0] / RotationMath.TICK_MINUTES;
            }
            d.putDouble("Energy", .5 * inertia * result.speed()[0] * result.speed()[0]);
            d.putDouble("Angle", d.getDouble("Angle") + result.angle()[0]);
            advance(travel); time -= result.duration(); if (travel == 0) break;
        }
    }
}
