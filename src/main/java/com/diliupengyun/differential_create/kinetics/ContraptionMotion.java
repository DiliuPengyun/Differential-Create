package com.diliupengyun.differential_create.kinetics;

import java.util.Map;
import java.util.WeakHashMap;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;

/** Actor sweeps and the next native controller tick consume the same integrated output travel. */
public final class ContraptionMotion {
	private static final double MOTION_EPSILON = 1e-8;
	// Integrated-server entities and their client replicas are loaded/removed on
	// different threads. Neither their pending travel nor their sampling flags
	// may share a mutable table with the other logical side.
	private static final class Frame {
		final Map<AbstractContraptionEntity, Output> outputs = new WeakHashMap<>();
		boolean sampling, advancing;
	}
	private static final ThreadLocal<Frame> frames = ThreadLocal.withInitial(Frame::new);
	private ContraptionMotion() {}
	private static final class Output {
		final ContraptionPhysics.Drive drive;
		final Vec3 origin, translation;
		final double angularRate, angularLimit, lowerTravel, upperTravel;
		double angle, signedTravel;
		boolean ready, consumed;
		Output(ContraptionPhysics.Drive drive) {
			this.drive = drive; origin = drive.root().getAnchorVec().add(.5, .5, .5);
			double angular = .3 * drive.ratio(), limit = Double.POSITIVE_INFINITY, distanceLimit = Double.POSITIVE_INFINITY;
			Vec3 linear = Vec3.ZERO;
			if (drive.shaft() instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing) {
				double sequence = ((com.diliupengyun.differential_create.mixin.MechanicalBearingAccessor) bearing).differentialcreate$angleLimit();
				if (sequence >= 0) limit = sequence;
			} else if (drive.shaft() instanceof LinearActuatorBlockEntity actuator) {
				// Native collision-safe speed is an output brake, not a new wall every tick.
				double sequence = ((LinearActuatorAccessor) actuator).differentialcreate$offsetLimit();
				if (sequence >= 0) distanceLimit = Math.min(distanceLimit, sequence);
				if (!((LinearActuatorAccessor) actuator).differentialcreate$waiting()) {
					if (actuator instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity) {
						// The controller changes clutch torque, never the sign or scale
						// of an already-moving cabin's physical coordinate.
						linear = new Vec3(0, -1.0 / 256, 0);
					}
					if (actuator instanceof MechanicalPistonBlockEntity) {
						var facing = actuator.getBlockState().getValue(BlockStateProperties.FACING);
						linear = Vec3.atLowerCornerOf(facing.getNormal()).scale(-facing.getAxisDirection().getStep()
							* (facing.getAxis() == Direction.Axis.Z ? -1 : 1) / 512.0);
					} else if (!(actuator instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity))
						linear = new Vec3(0, -1.0 / 512, 0);
				}
			} else if (drive.shaft() instanceof GantryShaftBlockEntity gantry) {
				if (drive.root() instanceof com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity carriage && carriage.sequencedOffsetLimit >= 0)
					distanceLimit = Math.min(distanceLimit, carriage.sequencedOffsetLimit);
				var facing = gantry.getBlockState().getValue(GantryShaftBlock.FACING);
				double coefficient = -1.0 / 512;
				linear = Vec3.atLowerCornerOf(facing.getNormal()).scale(coefficient);
			}
			// Create intentionally holds a new gantry for its first two entity ticks.
			// Integration runs after the entity tick and supplies the next one: do
			// not release gravitational work until that next tick can consume it.
			if (awaitingGantryStart(drive.root())) linear = Vec3.ZERO;
			angularRate = angular; angularLimit = limit; translation = linear;
			double bound = drive.rotation() ? (angular != 0 ? limit / Math.abs(angular) : Double.POSITIVE_INFINITY)
				: linear.length() > 0 ? distanceLimit / linear.length() : Double.POSITIVE_INFINITY;
			double low = -bound, high = bound;
			if (drive.shaft() instanceof LinearActuatorBlockEntity actuator) {
				double coefficient = linear.dot(((LinearActuatorAccessor) actuator).differentialcreate$toMotionVector(1));
				if (coefficient != 0) {
					double a = -actuator.offset / coefficient;
					double b = (((LinearActuatorAccessor) actuator).differentialcreate$extensionRange() - actuator.offset) / coefficient;
					low = Math.max(low, Math.min(a, b)); high = Math.min(high, Math.max(a, b));
				}
				int contact = LinearActuatorContact.stop(drive.root(), linear);
				if (contact < 0) low = Math.max(low, 0);
				if (contact > 0) high = Math.min(high, 0);
			}
			lowerTravel = low; upperTravel = high;
		}
		Output(CompoundTag tag) {
			drive = null; origin = Vec3.ZERO;
			translation = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
			angle = tag.getDouble("Angle"); signedTravel = 1;
			angularRate = 0; angularLimit = upperTravel = Double.POSITIVE_INFINITY; lowerTravel = Double.NEGATIVE_INFINITY; ready = true;
		}
		void advance(double signed) {
			// Preserve native pauses from behaviours outside the paid feed model.
			if (stalled(drive.root())) return;
			signedTravel = Math.max(lowerTravel, Math.min(upperTravel, signedTravel + signed));
			angle = Math.max(-angularLimit, Math.min(angularLimit, signedTravel * angularRate));
		}
	}
	private static boolean stalled(AbstractContraptionEntity root) {
		return root != null && (root.isStalled() || root.getContraption() != null && root.getContraption().stalled);
	}
	private static boolean awaitingGantryStart(AbstractContraptionEntity root) {
		return root instanceof com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity && root.tickCount < 2;
	}
	public static boolean active() { return frames.get().advancing; }
	public record Geometry(Vec3 translation, double angularRate, double angle) {}
	public static Geometry geometry(ContraptionPhysics.Drive drive) {
		var output = frames.get().outputs.get(drive.root());
		if (output != null && (frames.get().advancing || output.ready && !output.consumed))
			return new Geometry(output.translation, output.angularRate, output.angle);
		boolean previous = frames.get().sampling;
		try {
			frames.get().sampling = true; output = new Output(drive);
			return new Geometry(output.translation, output.angularRate, 0);
		} finally { frames.get().sampling = previous; }
	}
	public static boolean samplingGeometry() { return frames.get().sampling; }
	public static boolean managed(AbstractContraptionEntity entity) {
		return entity != null && (frames.get().outputs.containsKey(entity) || entity.getContraption() != null && entity.getContraption().getActors().stream()
			.anyMatch(a -> a.getRight() != null && (ContraptionActorWork.of(a.getRight()).motionManaged()
				|| com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(a.getRight()))));
	}
	/** A retained endpoint is one-sided support, not Create's wait for another RPM command. */
	public static void releaseEndpointWait(LinearActuatorBlockEntity actuator) {
		var access = (LinearActuatorAccessor) actuator;
		if (!access.differentialcreate$waiting() || !actuator.running || actuator.getLevel() == null
			|| actuator.getLevel().isClientSide || actuator.movedContraption == null || actuator.movedContraption.isRemoved()
			|| actuator instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity
			|| access.differentialcreate$offsetLimit() == 0) return;
		if ((actuator.offset <= 0 || actuator.offset >= access.differentialcreate$extensionRange()) && ActorFeed.enabled(actuator)) {
			access.differentialcreate$setWaiting(false);
			actuator.sendData();
		}
	}
	public static void begin(Level level) {
		frames.get().advancing = true; frames.get().sampling = true;
		try {
			frames.get().outputs.keySet().removeIf(AbstractContraptionEntity::isRemoved);
			var captured = new java.util.HashSet<AbstractContraptionEntity>();
			for (var network : MachiningClock.networks(level)) for (var shaft : network.members.keySet()) {
				var drives = ContraptionPhysics.drives(shaft);
				for (var drive : drives) {
					if (drive.stabilized()) continue;
					// Also release endpoint waits restored from saves made before this fix.
					if (shaft instanceof LinearActuatorBlockEntity actuator) releaseEndpointWait(actuator);
					LinearActuatorContact.refresh(drive.root());
					frames.get().outputs.put(drive.root(), new Output(drive));
					captured.add(drive.root());
				}
			}
			frames.get().outputs.entrySet().removeIf(e -> e.getKey().level() == level && e.getValue().drive != null && !captured.contains(e.getKey()));
		} finally { frames.get().sampling = false; }
	}
	public static void advance(ElasticKinetics.Trial trial) {
		for (var output : frames.get().outputs.values()) if (!output.ready && !output.drive.shaft().isRemoved())
			output.advance(trial.structureTravel(output.drive));
	}
	/** Geometric limits belong to every structure, including ones without tools. */
	public static void boundaries(java.util.List<ElasticKinetics.WorkEvent> limits) {
		for (var output : frames.get().outputs.values()) if (!output.ready && output.drive != null && !output.drive.shaft().isRemoved()) {
			double positive = output.upperTravel - output.signedTravel, negative = output.signedTravel - output.lowerTravel;
			// Already supported directions are handled by the unilateral reaction.
			positive = positive <= MOTION_EPSILON ? Double.POSITIVE_INFINITY : positive;
			negative = negative <= MOTION_EPSILON ? Double.POSITIVE_INFINITY : negative;
			if (Double.isFinite(positive) || Double.isFinite(negative))
				limits.add(new ElasticKinetics.WorkEvent(output.drive.shaft(),
					new ShaftWork.Boundary(Double.POSITIVE_INFINITY, positive, negative), 0, ActorFeed.enabled(output.drive.shaft())).withDrive(output.drive));
		}
	}
	/** A native pause is a fixed geometric constraint; an endpoint supports only outward forces. */
	public static int stop(com.simibubi.create.content.kinetics.base.KineticBlockEntity shaft) {
		if (!ContraptionPhysics.manages(shaft) || ClockworkMotion.manages(shaft)) return 0;
		boolean lower = false, upper = false;
		for (var output : frames.get().outputs.values()) if (!output.ready && output.drive != null && output.drive.shaft() == shaft) {
			if (awaitingGantryStart(output.drive.root())) return 2;
			if (shaft instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity
				&& output.translation.lengthSqr() == 0) return 2;
			if (stalled(output.drive.root()) || shaft instanceof LinearActuatorBlockEntity actuator
				&& ((LinearActuatorAccessor) actuator).differentialcreate$waiting())
				return ActorFeed.enabled(shaft) ? 2 : 0;
			lower |= output.signedTravel - output.lowerTravel <= MOTION_EPSILON;
			upper |= output.upperTravel - output.signedTravel <= MOTION_EPSILON;
		}
		return lower && upper ? 2 : lower ? -1 : upper ? 1 : 0;
	}
	public static double speedLimit(com.simibubi.create.content.kinetics.base.KineticBlockEntity shaft) {
		if (!(shaft instanceof LinearActuatorBlockEntity) && !(shaft instanceof GantryShaftBlockEntity)) return Double.POSITIVE_INFINITY;
		return shaft instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity ? 1.99 * 256 : .49 * 512;
	}
	/** Resolve an inelastic endpoint impact once, before the resting contact solve. */
	public static void resolveStops(java.util.List<com.simibubi.create.content.kinetics.KineticNetwork> networks) {
		for (var network : networks) {
			if (network.members.keySet().stream().anyMatch(ContraptionPhysics::incomplete)) continue;
			for (var shaft : network.members.keySet()) {
				boolean feed = ActorFeed.enabled(shaft);
				double speed = feed ? ActorFeed.of(shaft).speed : NetworkDynamics.preciseSpeed(shaft);
				double limit = feed ? speedLimit(shaft) : Double.POSITIVE_INFINITY;
				if (Math.abs(speed) > limit) {
					ElasticKinetics.state(shaft).structureImpactLoss += .5 * ActorFeed.of(shaft).inertia * (speed * speed - limit * limit);
					speed = Math.copySign(limit, speed); ActorFeed.of(shaft).speed = speed;
				}
				int stop = stop(shaft); if (stop == 0) continue;
				if (speed == 0 || stop != 2 && stop * speed <= 0) continue;
				double inertia = feed ? ActorFeed.of(shaft).inertia : InertiaRegistry.getInertia(shaft);
				ElasticKinetics.state(shaft).structureImpactLoss += .5 * inertia * speed * speed;
				if (feed) ActorFeed.of(shaft).speed = 0;
				else ElasticKinetics.state(shaft).physicalSpeed = 0;
			}
		}
	}
	public static void end(Level level) {
		for (var output : frames.get().outputs.values()) if (output.drive != null && output.drive.shaft().getLevel() == level) output.ready = true;
		frames.get().advancing = false;
	}
	public static void consume(AbstractContraptionEntity root) { var o = frames.get().outputs.get(root); if (o != null) o.consumed = true; }
	public static boolean restored(AbstractContraptionEntity root) {
		var o = frames.get().outputs.get(root); return o != null && o.drive == null && o.ready && !o.consumed;
	}
	public static void forget(AbstractContraptionEntity root) { frames.get().outputs.remove(root); LinearActuatorContact.forget(root); }
	public static void clear() { frames.get().outputs.clear(); LinearActuatorContact.clear(); frames.get().advancing = frames.get().sampling = false; }
	public static void save(AbstractContraptionEntity root, CompoundTag tag) {
		LinearActuatorContact.save(root, tag);
		var o = frames.get().outputs.get(root);
		if (o == null || !o.ready || o.consumed) return;
		var pending = new CompoundTag(); var v = linear(root);
		pending.putDouble("Angle", angular(root)); pending.putDouble("X", v.x); pending.putDouble("Y", v.y); pending.putDouble("Z", v.z);
		tag.put("DCOutputMotion", pending);
	}
	public static void load(AbstractContraptionEntity root, CompoundTag tag) {
		LinearActuatorContact.load(root, tag);
		if (tag.contains("DCOutputMotion")) frames.get().outputs.put(root, new Output(tag.getCompound("DCOutputMotion")));
	}
	/** Null keeps native motion only when no physical output has been captured. */
	public static Double angular(AbstractContraptionEntity root) {
		var o = frames.get().outputs.get(root); return frames.get().sampling || o == null || !o.ready || o.consumed ? null : stalled(root) ? 0 : o.angle;
	}
	/** Snapshots are queued after integration, just before end() marks the output ready. */
	public static Double syncAngular(AbstractContraptionEntity root) {
		var o = frames.get().outputs.get(root); return frames.get().sampling || o == null || o.consumed ? null : stalled(root) ? 0 : o.angle;
	}
	public static Vec3 linear(AbstractContraptionEntity root) {
		var o = frames.get().outputs.get(root); return frames.get().sampling || o == null || !o.ready || o.consumed ? null
			: stalled(root) ? Vec3.ZERO : o.translation.scale(Math.max(o.lowerTravel, Math.min(o.upperTravel, o.signedTravel)));
	}
	/** Entity actors tick before their block controller consumes this tick's accepted displacement. */
	public static Vec3 pendingPosition(ContraptionPhysics.Drive drive, Vec3 current) {
		Double angle = angular(drive.root());
		if (angle == null) return current;
		if (!drive.rotation()) return current.add(linear(drive.root()));
		if (drive.stabilized()) {
			Vec3 orbit = drive.root().applyRotation(Vec3.atLowerCornerOf(drive.mount()), 1);
			return current.add(rotate(orbit, angle, drive.axis()).subtract(orbit));
		}
		Vec3 centre = drive.root().getAnchorVec().add(.5, .5, .5);
		return centre.add(rotate(current.subtract(centre), angle, drive.axis()));
	}
	public static Vec3 pendingDirection(ContraptionPhysics.Drive drive, Vec3 current) {
		Double angle = angular(drive.root());
		return angle == null || !drive.rotation() || drive.stabilized() ? current : rotate(current, angle, drive.axis());
	}
	public static ActorContactPath path(ContraptionPhysics.Drive drive, MovementContext context) {
		var o = frames.get().outputs.get(drive.root());
		if (!frames.get().advancing || o == null || o.ready || drive.root().isStalled() || drive.root().getContraption().stalled
			|| drive.entity().isStalled() || drive.entity().getContraption().stalled
			|| allowance(drive) <= MOTION_EPSILON) return null;
		if (drive.shaft() instanceof LinearActuatorBlockEntity actuator && ((LinearActuatorAccessor) actuator).differentialcreate$waiting()) return null;
		var behaviour = com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(context.state);
		Vec3 point = drive.entity().toGlobalVector(Vec3.atCenterOf(context.localPos).add(
			com.diliupengyun.differential_create.kinetics.portable.PortableDocking.isPortable(context) ? Vec3.ZERO : behaviour.getActiveAreaOffset(context)), 1);
		if (!drive.rotation()) return ActorContactPath.linear(point(point), point(o.translation));
		Vec3 orbit;
		Vec3 centre;
		if (drive.stabilized()) {
			orbit = drive.root().applyRotation(Vec3.atLowerCornerOf(drive.mount()), 1);
			centre = point.subtract(orbit);
		} else { orbit = point.subtract(o.origin); centre = o.origin; }
		double axial = drive.axis().choose(orbit.x, orbit.y, orbit.z);
		Vec3 along = new Vec3(drive.axis() == Direction.Axis.X ? axial : 0,
			drive.axis() == Direction.Axis.Y ? axial : 0, drive.axis() == Direction.Axis.Z ? axial : 0);
		Vec3 radial = orbit.subtract(along), tangent = rotate(radial, 90, drive.axis());
		return ActorContactPath.circular(point(centre.add(along)), point(radial), point(tangent), o.angularRate * Math.PI / 180);
	}
	public static ActorContactPath directionPath(ContraptionPhysics.Drive drive, Vec3 local) {
		Vec3 direction = drive.entity().applyRotation(local, 1);
		var output = frames.get().outputs.get(drive.root());
		if (output == null || !frames.get().advancing || output.ready || !drive.rotation() || drive.stabilized())
			return ActorContactPath.linear(point(direction), point(Vec3.ZERO));
		double axial = drive.axis().choose(direction.x, direction.y, direction.z);
		Vec3 along = new Vec3(drive.axis() == Direction.Axis.X ? axial : 0, drive.axis() == Direction.Axis.Y ? axial : 0, drive.axis() == Direction.Axis.Z ? axial : 0);
		Vec3 radial = direction.subtract(along);
		return ActorContactPath.circular(point(along), point(radial), point(rotate(radial, 90, drive.axis())), output.angularRate * Math.PI / 180);
	}
	public static double travel(ContraptionPhysics.Drive drive) { return frames.get().outputs.get(drive.root()).signedTravel; }
	public static double pendingTravel(ContraptionPhysics.Drive drive) {
		var output = frames.get().outputs.get(drive.root());
		return output == null || output.consumed ? 0 : output.signedTravel;
	}
	public static double angle(ContraptionPhysics.Drive drive) { return frames.get().outputs.get(drive.root()).angle; }
	public static double angularRate(ContraptionPhysics.Drive drive) { return frames.get().outputs.get(drive.root()).angularRate; }
	public static double allowance(ContraptionPhysics.Drive drive) {
		double speed = drive.speed();
		double direction = Math.signum(speed == 0 ? drive.preferredSpeed() : speed);
		return allowance(drive, direction == 0 ? 1 : direction);
	}
	/** A blocked feed must refresh its tool load as soon as the shaft turns back inward. */
	public static int wakeDirection(ContraptionPhysics.Drive drive) {
		var output = frames.get().outputs.get(drive.root());
		if (!frames.get().advancing || frames.get().sampling || output == null || output.ready || stalled(drive.root()) || stalled(drive.entity())) return 0;
		if (drive.shaft() instanceof LinearActuatorBlockEntity actuator && ((LinearActuatorAccessor) actuator).differentialcreate$waiting()) return 0;
		double speed = drive.speed();
		double direction = Math.signum(speed == 0 ? drive.preferredSpeed() : speed);
		if (direction == 0) direction = 1;
		return allowance(drive, direction) <= MOTION_EPSILON && allowance(drive, -direction) > MOTION_EPSILON
			? (int) -direction : 0;
	}
	public static double allowance(ContraptionPhysics.Drive drive, double direction) {
		var o = frames.get().outputs.get(drive.root());
		return direction > 0 ? o.upperTravel - o.signedTravel : o.signedTravel - o.lowerTravel;
	}
	public static Vec3 rotate(Vec3 p, double degrees, Direction.Axis axis) { return VecHelper.rotate(p, (float) degrees, axis); }
	private static ActorContactPath.Point point(Vec3 p) { return new ActorContactPath.Point(p.x, p.y, p.z); }
	public static Vec3 vector(ActorContactPath.Point p) { return new Vec3(p.x(), p.y(), p.z()); }
}
