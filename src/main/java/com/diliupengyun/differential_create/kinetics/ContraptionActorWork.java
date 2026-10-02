package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Work counters live in actor NBT, and advance only through the driving shaft's integrated travel. */
public final class ContraptionActorWork implements ShaftWork {
	private static final String JOB = "DCActorJob", TRAVEL = "DCActorTravel", TARGET = "DCActorTarget";
	private static final String FEED = "DCActorFeed", CELL = "DCActorCell";
	public static final double CYCLE_TRAVEL = 1000;
	/** A quarter-block cutting stroke keeps the work within the tool's active voxel. */
	public static final double FEED_DISTANCE = .25;
	private final MovementContext context;
	private boolean firing;
	private boolean motionManaged;
	private ActorContactPath path;
	// Observations are transient: a loaded actor must inspect its current contact again.
	private BlockPos inspectedCell;
	private BlockState inspectedState;
	private double direction = 1;
	private double sampledJob = Double.POSITIVE_INFINITY;
	private double positiveLimit = Double.POSITIVE_INFINITY, negativeLimit = Double.POSITIVE_INFINITY;
	public ContraptionActorWork(MovementContext context) { this.context = context; }
	public static ContraptionActorWork of(MovementContext context) { return ((IMovementWork) context).differentialcreate$actorWork(); }
	public static boolean supported(MovementContext context) {
		return !context.world.isClientSide && ContraptionPhysics.drive(context) != null;
	}
	public boolean pending() { return context.data.contains(JOB); }
	public boolean firing() { return firing; }
	public boolean continuous() { return !(behaviour() instanceof com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour); }
	public boolean motionManaged() { return motionManaged; }
	public void manageMotion() {
		if (supported(context) && (behaviour() instanceof BlockBreakingMovementBehaviour
			|| behaviour() instanceof com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour)) {
			motionManaged = true; context.stall = false;
		}
	}
	/** A physical stop retains a partly cut target; teardown still uses the native stop callback. */
	public static void stopOnSpeedChange(com.simibubi.create.content.contraptions.Contraption contraption,
		net.minecraft.world.level.Level level) {
		contraption.forEachActor(level, (actor, ctx) -> {
			if (!of(ctx).motionManaged()) { actor.stopMoving(ctx); ctx.position = null; ctx.rotation = v -> v; }
			ctx.motion = ctx.relativeMotion = Vec3.ZERO;
		});
	}
	private BlockPos contactCell() {
		return path == null ? (context.position == null ? null : BlockPos.containing(context.position))
			: BlockPos.containing(ContraptionMotion.vector(path.inside(ContraptionMotion.travel(ContraptionPhysics.drive(context)), direction)));
	}
	private double pointRatio() {
		var drive = ContraptionPhysics.drive(context);
		if (path == null) return drive == null ? 0 : drive.workRatio(context);
		double distance = ContraptionMotion.vector(path.derivative(ContraptionMotion.travel(drive))).length();
		// A blade on the rotation axis still sweeps its own finite area. Stabilized
		// children retain their orientation and therefore have no such self rotation.
		if (drive.rotation() && !drive.stabilized()) {
			double blade = ContraptionMotion.angularRate(drive) * Math.PI / 180 / Math.sqrt(6);
			distance = Math.hypot(distance, blade);
		}
		return distance * 512;
	}
	private double stroke(double cost) {
		if (!continuous()) return cost;
		double ratio = pointRatio();
		double feed = ratio > 0 ? FEED_DISTANCE * 512 / ratio : Double.POSITIVE_INFINITY;
		if (path != null) feed = Math.min(feed, .9 * path.exit(ContraptionMotion.travel(ContraptionPhysics.drive(context)), direction));
		return Math.max(1e-6, feed);
	}
	private void prepareMotion(ContraptionPhysics.Drive drive) {
		path = motionManaged ? ContraptionMotion.path(drive, context) : null;
		if (path == null) return;
		if ((pending() || breakingPos() != null) && !context.data.contains("DCActorContact") && context.position != null)
			context.data.putLong("DCActorContact", BlockPos.containing(context.position).asLong());
		double speed = NetworkDynamics.preciseSpeed(drive.shaft());
		direction = Math.signum(speed == 0 ? drive.shaft().getTheoreticalSpeed() : speed);
		if (direction == 0) direction = 1;
		double t = ContraptionMotion.travel(drive);
		context.position = ContraptionMotion.vector(path.at(t));
		context.motion = ContraptionMotion.vector(path.derivative(t)).scale(speed);
		context.rotation = v -> drive.stabilized() || !drive.rotation() ? drive.entity().applyRotation(v, 1)
			: ContraptionMotion.rotate(drive.entity().applyRotation(v, 1), ContraptionMotion.angle(drive), drive.axis());
		context.relativeMotion = drive.entity().reverseRotation(context.motion, 1);
		if (!drive.stabilized() && drive.rotation()) context.relativeMotion = ContraptionMotion.rotate(context.relativeMotion, -ContraptionMotion.angle(drive), drive.axis());
		BlockPos cell = contactCell();
		if (context.disabled || !behaviour().isActive(context)) { behaviour().cancelStall(context); cancel(); return; }
		if (context.data.contains("DCActorContact") && context.data.getLong("DCActorContact") != cell.asLong()) {
			behaviour().cancelStall(context); cancel();
		}
		boolean entered = !context.data.contains(CELL) || context.data.getLong(CELL) != cell.asLong();
		if (entered) {
			context.data.putLong(CELL, cell.asLong());
		}
		refreshContact(cell, entered);
		if (entered) context.firstMovement = false;
		context.stall = false;
	}
	private void refreshContact(BlockPos cell, boolean entered) {
		if (!context.world.isLoaded(cell)) { inspectedCell = null; inspectedState = null; return; }
		MovementBehaviour actor = behaviour();
		boolean cutting = actor instanceof com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour
			|| actor instanceof com.simibubi.create.content.kinetics.saw.SawMovementBehaviour;
		if (!entered && (!cutting || pending() || breakingPos() != null)) return;
		BlockState state = context.world.getBlockState(cell);
		if (!entered && cell.equals(inspectedCell) && state.equals(inspectedState)) return;
		inspectedCell = cell; inspectedState = state;
		// Preserve entry callbacks, but retry a same-cell visit only for a new breakable
		// target. Air must not repeat native entity damage on every physics substep.
		if (entered || ((BlockBreakingMovementBehaviour) actor).canBreak(context.world, cell, state))
			actor.visitNewPosition(context, cell);
	}
	public static boolean queue(MovementContext context, String kind, BlockPos pos, double cost) {
		if (!supported(context) || context.disabled || of(context).firing) return false;
		var work = of(context);
		if (!work.pending()) {
			var job = new CompoundTag();
			job.putString("Kind", kind); job.putLong("Pos", pos.asLong());
			var target = kind.equals("plough") ? pos.below() : pos;
			job.putLong("TargetPos", target.asLong());
			job.putString("Target", context.world.getBlockState(target).toString());
			job.putDouble("Cost", Math.max(1, cost));
			job.putDouble("Feed", work.stroke(Math.max(1, cost)));
			context.data.put(JOB, job);
			var cell = work.contactCell();
			if (cell != null) context.data.putLong("DCActorContact", cell.asLong());
		}
		context.stall = !work.continuous();
		return true;
	}
	/** A roller reserves its material, then waits for physical work before placing it. */
	public static boolean queueFill(MovementContext context, BlockPos pos, BlockState state, ItemStack material) {
		if (!queue(context, "fill", pos, CYCLE_TRAVEL)) return false;
		var job = context.data.getCompound(JOB);
		job.put("State", NbtUtils.writeBlockState(state));
		job.put("Material", material.save(context.world.registryAccess()));
		return true;
	}
	private MovementBehaviour behaviour() { return MovementBehaviour.REGISTRY.get(context.state); }
	private BlockPos breakingPos() {
		return NbtUtils.readBlockPos(context.data, "BreakingPos").orElse(null);
	}
	private double required() {
		if (context.disabled) {
			behaviour().cancelStall(context); cancel(); context.stall = false;
			return Double.POSITIVE_INFINITY;
		}
		if (pending()) {
			var job = context.data.getCompound(JOB);
			var pos = BlockPos.of(job.getLong(job.contains("TargetPos") ? "TargetPos" : "Pos"));
			if (!context.world.isLoaded(pos)) return Double.POSITIVE_INFINITY;
			if (!context.world.getBlockState(pos).toString().equals(job.getString("Target"))) {
				cancel(); return Double.POSITIVE_INFINITY;
			}
			return job.getDouble("Cost");
		}
		BlockPos pos = breakingPos();
		if (pos == null || !(behaviour() instanceof BlockBreakingMovementBehaviour breaker)) return Double.POSITIVE_INFINITY;
		if (!context.world.isLoaded(pos)) return Double.POSITIVE_INFINITY;
		var state = context.world.getBlockState(pos);
		if (!breaker.canBreak(context.world, pos, state)) {
			breaker.cancelStall(context); clearBreaking(); return Double.POSITIVE_INFINITY;
		}
		String target = pos.asLong() + ":" + state;
		double cost = Math.max(.1, state.getDestroySpeed(context.world, pos)) * CYCLE_TRAVEL;
		if (!context.data.getString(TARGET).equals(target)) {
			boolean legacy = !context.data.contains(TARGET);
			context.data.putString(TARGET, target);
			context.data.remove(FEED);
			context.data.putDouble(TRAVEL, legacy ? context.data.getInt("Progress") * cost / 10 : 0);
			if (!legacy) { context.data.remove("Progress"); context.data.remove("TicksUntilNextProgress"); }
		}
		if (!context.data.contains(FEED)) {
			context.data.putDouble(FEED, stroke(cost));
			var cell = contactCell();
			if (cell != null) context.data.putLong("DCActorContact", cell.asLong());
		}
		context.stall = !continuous();
		return cost;
	}
	private double progress() { return pending() ? context.data.getCompound(JOB).getDouble("Travel") : context.data.getDouble(TRAVEL); }
	private double workRatio(double cost, ContraptionPhysics.Drive drive) {
		if (!continuous()) return drive.workRatio(context);
		double feed = pending() ? context.data.getCompound(JOB).getDouble("Feed") : context.data.getDouble(FEED);
		if (!(feed > 0)) {
			feed = stroke(cost);
			if (pending()) context.data.getCompound(JOB).putDouble("Feed", feed); else context.data.putDouble(FEED, feed);
		}
		return cost / feed;
	}
	@Override public double differentialcreate$remainingTravel() {
		var drive = ContraptionPhysics.drive(context);
		if (drive == null) return Double.POSITIVE_INFINITY;
		prepareMotion(drive);
		if (motionManaged && ContraptionMotion.active() && path == null) return Double.POSITIVE_INFINITY;
		double required = required(), ratio = Double.isFinite(required) ? workRatio(required, drive) : 0;
		double job = Double.isFinite(required) && ratio > 0 ? Math.max(0, required - progress()) / ratio : Double.POSITIVE_INFINITY;
		if (path != null) {
			double sampledTravel = ContraptionMotion.travel(drive); sampledJob = job;
			// Both directions are fixed throughout this event search, including trials that reverse.
			positiveLimit = Math.min(ContraptionMotion.allowance(drive, 1), path.exit(sampledTravel, 1));
			negativeLimit = Math.min(ContraptionMotion.allowance(drive, -1), path.exit(sampledTravel, -1));
		}
		return path == null ? job : Math.min(job, direction > 0 ? positiveLimit : negativeLimit);
	}
	@Override public Boundary differentialcreate$boundary(double remaining) {
		return path == null ? ShaftWork.super.differentialcreate$boundary(remaining)
			: new Boundary(sampledJob, positiveLimit, negativeLimit);
	}
	@Override public int differentialcreate$wakeDirection() {
		if (!motionManaged || path != null || !ContraptionMotion.active() || context.disabled || !behaviour().isActive(context)) return 0;
		var drive = ContraptionPhysics.drive(context);
		return drive == null ? 0 : ContraptionMotion.wakeDirection(drive);
	}
	@Override public double differentialcreate$phaseLimit(double travel, double signedTravel, double remaining) {
		if (path == null) return remaining;
		double net = Math.abs(signedTravel);
		double limit = signedTravel > 0 ? positiveLimit : negativeLimit;
		double geometric = net > 0 ? limit * travel / net
			: Double.POSITIVE_INFINITY;
		return Math.min(sampledJob, geometric);
	}
	public double torque(ContraptionPhysics.Drive drive) {
		differentialcreate$remainingTravel();
		double required = required();
		if (!Double.isFinite(required) || motionManaged && ContraptionMotion.active() && path == null) return 0;
		double rated = BlockStressValues.getImpact(context.state.getBlock());
		if (rated <= 0) rated = context.state.is(com.simibubi.create.AllBlocks.MECHANICAL_ROLLER.get()) ? 8 : 2;
		return rated * workRatio(required, drive);
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		var drive = ContraptionPhysics.drive(context);
		if (drive == null) return;
		double required = required();
		if (!Double.isFinite(required)) return;
		double ratio = workRatio(required, drive);
		double progress = Math.min(required, progress() + Math.max(0, travel) * ratio);
		// Match the scheduler's tolerance in shaft travel, not scaled cutting work.
		boolean complete = ratio > 0 && (required - progress) / ratio <= MachineWork.EPSILON;
		if (complete) progress = required;
		if (pending()) context.data.getCompound(JOB).putDouble("Travel", progress);
		else {
			context.data.putDouble(TRAVEL, progress);
			BlockPos pos = breakingPos();
			int stage = Math.min(9, (int) (10 * progress / required));
			if (pos != null && stage != context.data.getInt("Progress")) {
				context.data.putInt("Progress", stage);
				context.world.destroyBlockProgress(context.data.getInt("BreakerId"), pos, stage);
			}
		}
		if (complete) MachineWork.event(this::finish);
	}
	private void finish() {
		if (!Double.isFinite(required())) return;
		MovementBehaviour behaviour = behaviour();
		if (pending()) {
			var job = context.data.getCompound(JOB).copy();
			context.data.remove(JOB); context.stall = false;
			var pos = BlockPos.of(job.getLong("Pos"));
			if (job.getString("Kind").equals("fill")) {
				var state = NbtUtils.readBlockState(context.world.holderLookup(Registries.BLOCK), job.getCompound("State"));
				context.world.setBlockAndUpdate(pos, state);
				// Resume the original paving profile: each additional placed block needs its own work.
				if (context.position != null && behaviour instanceof IContraptionPaver paver)
					paver.differentialcreate$resumePaving(context, BlockPos.containing(context.position));
			} else {
				firing = true;
				try { behaviour.visitNewPosition(context, pos); }
				finally { firing = false; }
			}
		} else if (breakingPos() != null && behaviour instanceof IContraptionBreaker breaker) {
			var pos = breakingPos();
			clearBreaking();
			breaker.differentialcreate$finishBreaking(context, pos);
		}
	}
	public void clearBreaking() {
		context.data.remove(TRAVEL); context.data.remove(TARGET); context.data.remove(FEED); context.data.remove("DCActorContact");
		inspectedCell = null; inspectedState = null;
	}
	public void cancel() {
		if (pending()) {
			var job = context.data.getCompound(JOB);
			if (job.contains("Material")) behaviour().collectOrDropItem(context,
				ItemStack.parseOptional(context.world.registryAccess(), job.getCompound("Material")));
			context.data.remove(JOB);
		}
		clearBreaking();
		context.stall = !continuous() && context.data.contains("BreakingPos");
	}
}
