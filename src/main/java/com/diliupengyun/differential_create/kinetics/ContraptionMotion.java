package com.diliupengyun.differential_create.kinetics;

import java.util.Map;
import java.util.WeakHashMap;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
	private static final Map<AbstractContraptionEntity, Output> outputs = new WeakHashMap<>();
	private static boolean sampling;
	private static boolean advancing;
	private ContraptionMotion() {}
	private static final class Output {
		final ContraptionPhysics.Drive drive;
		final Vec3 origin, translation;
		final double angularRate, angularLimit, lowerTravel, upperTravel;
		double angle, signedTravel;
		boolean ready, consumed;
		Output(ContraptionPhysics.Drive drive) {
			this.drive = drive; origin = drive.root().getAnchorVec().add(.5, .5, .5);
			double speed = NetworkDynamics.preciseSpeed(drive.shaft());
			double angular = .3 * drive.ratio(), limit = Double.POSITIVE_INFINITY, distanceLimit = Double.POSITIVE_INFINITY;
			Vec3 linear = Vec3.ZERO;
			if (drive.shaft() instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing) {
				double sequence = ((com.diliupengyun.differential_create.mixin.MechanicalBearingAccessor) bearing).differentialcreate$angleLimit();
				if (sequence >= 0) limit = sequence;
			} else if (drive.shaft() instanceof ClockworkBearingBlockEntity clock) {
				double nativeSpeed = drive.ratio() == .5 ? clock.getHourArmSpeed() : clock.getMinuteArmSpeed();
				angular = speed == 0 ? 0 : nativeSpeed / speed;
				limit = Math.abs(nativeSpeed);
			} else if (drive.shaft() instanceof LinearActuatorBlockEntity actuator) {
				distanceLimit = actuator instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity ? 1.99 : .49;
				double sequence = ((LinearActuatorAccessor) actuator).differentialcreate$offsetLimit();
				if (sequence >= 0) distanceLimit = Math.min(distanceLimit, sequence);
				if (!((LinearActuatorAccessor) actuator).differentialcreate$waiting()) {
					if (speed != 0) linear = drive.root().getDeltaMovement().scale(1 / speed);
					if (!(actuator instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity)) {
						linear = actuator.getMotionVector();
						// Native travel can already be clamped to zero at an endpoint.
						// Retain the drive direction so offset/sequence limits still
						// distinguish blocked feed from a stationary cutting stroke.
						if (speed != 0 && linear.lengthSqr() != 0) linear = linear.scale(1 / speed);
						else if (actuator instanceof MechanicalPistonBlockEntity) {
							var facing = actuator.getBlockState().getValue(BlockStateProperties.FACING);
							linear = Vec3.atLowerCornerOf(facing.getNormal()).scale(-facing.getAxisDirection().getStep()
								* (facing.getAxis() == Direction.Axis.Z ? -1 : 1) / 512.0);
						} else linear = new Vec3(0, -1.0 / 512, 0);
					}
				}
			} else if (drive.shaft() instanceof GantryShaftBlockEntity gantry) {
				distanceLimit = .49;
				if (drive.root() instanceof com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity carriage && carriage.sequencedOffsetLimit >= 0)
					distanceLimit = Math.min(distanceLimit, carriage.sequencedOffsetLimit);
				var facing = gantry.getBlockState().getValue(GantryShaftBlock.FACING);
				double coefficient = speed == 0 ? -1.0 / 512 : gantry.getPinionMovementSpeed() / speed;
				linear = Vec3.atLowerCornerOf(facing.getNormal()).scale(coefficient);
			}
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
			// A dwelling actor can pause the native controller while its shaft
			// keeps turning to pay for the action. That shaft travel is not feed.
			if (stalled(drive.root())) return;
			signedTravel = Math.max(lowerTravel, Math.min(upperTravel, signedTravel + signed));
			angle = Math.max(-angularLimit, Math.min(angularLimit, signedTravel * angularRate));
		}
	}
	private static boolean stalled(AbstractContraptionEntity root) {
		return root != null && (root.isStalled() || root.getContraption() != null && root.getContraption().stalled);
	}
	public static boolean active() { return advancing; }
	public static boolean managed(AbstractContraptionEntity entity) {
		return entity != null && (outputs.containsKey(entity) || entity.getContraption() != null && entity.getContraption().getActors().stream()
			.anyMatch(a -> a.getRight() != null && ContraptionActorWork.of(a.getRight()).motionManaged()));
	}
	public static void begin(Level level) {
		advancing = true; sampling = true;
		try {
			outputs.keySet().removeIf(AbstractContraptionEntity::isRemoved);
			var captured = new java.util.HashSet<AbstractContraptionEntity>();
			for (var network : MachiningClock.networks(level)) for (var shaft : network.members.keySet()) {
				var drives = ContraptionPhysics.drives(shaft);
				for (var drive : drives) {
					if (drive.stabilized() || !SequencerKinetics.controlled(shaft) && drives.stream().noneMatch(d -> d.root() == drive.root()
						&& d.entity().getContraption().getActors().stream().anyMatch(a -> a.getRight() != null
							&& ContraptionActorWork.of(a.getRight()).motionManaged()))) continue;
					outputs.put(drive.root(), new Output(drive));
					captured.add(drive.root());
				}
			}
			outputs.entrySet().removeIf(e -> e.getKey().level() == level && e.getValue().drive != null && !captured.contains(e.getKey()));
		} finally { sampling = false; }
	}
	public static void advance(ElasticKinetics.Trial trial) {
		for (var output : outputs.values()) if (!output.ready && !output.drive.shaft().isRemoved())
			output.advance(trial.signedTravel(output.drive.shaft()));
	}
	public static void end(Level level) {
		for (var output : outputs.values()) if (output.drive != null && output.drive.shaft().getLevel() == level) output.ready = true;
		advancing = false;
	}
	public static void consume(AbstractContraptionEntity root) { var o = outputs.get(root); if (o != null) o.consumed = true; }
	public static boolean restored(AbstractContraptionEntity root) {
		var o = outputs.get(root); return o != null && o.drive == null && o.ready && !o.consumed;
	}
	public static void forget(AbstractContraptionEntity root) { outputs.remove(root); }
	public static void clear() { outputs.clear(); advancing = sampling = false; }
	public static void save(AbstractContraptionEntity root, CompoundTag tag) {
		var o = outputs.get(root);
		if (o == null || !o.ready || o.consumed) return;
		var pending = new CompoundTag(); var v = linear(root);
		pending.putDouble("Angle", angular(root)); pending.putDouble("X", v.x); pending.putDouble("Y", v.y); pending.putDouble("Z", v.z);
		tag.put("DCOutputMotion", pending);
	}
	public static void load(AbstractContraptionEntity root, CompoundTag tag) {
		if (tag.contains("DCOutputMotion")) outputs.put(root, new Output(tag.getCompound("DCOutputMotion")));
	}
	/** Null lets native controllers keep their existing movement when no swept tools are present. */
	public static Double angular(AbstractContraptionEntity root) {
		var o = outputs.get(root); return sampling || o == null || !o.ready || o.consumed ? null : stalled(root) ? 0 : o.angle;
	}
	/** Snapshots are queued after integration, just before end() marks the output ready. */
	public static Double syncAngular(AbstractContraptionEntity root) {
		var o = outputs.get(root); return sampling || o == null || o.consumed ? null : stalled(root) ? 0 : o.angle;
	}
	public static Vec3 linear(AbstractContraptionEntity root) {
		var o = outputs.get(root); return sampling || o == null || !o.ready || o.consumed ? null
			: stalled(root) ? Vec3.ZERO : o.translation.scale(Math.max(o.lowerTravel, Math.min(o.upperTravel, o.signedTravel)));
	}
	public static ActorContactPath path(ContraptionPhysics.Drive drive, MovementContext context) {
		var o = outputs.get(drive.root());
		if (!advancing || o == null || o.ready || drive.root().isStalled() || drive.root().getContraption().stalled
			|| drive.entity().isStalled() || drive.entity().getContraption().stalled
			|| allowance(drive) <= MOTION_EPSILON) return null;
		if (drive.shaft() instanceof LinearActuatorBlockEntity actuator && ((LinearActuatorAccessor) actuator).differentialcreate$waiting()) return null;
		var behaviour = com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(context.state);
		Vec3 point = drive.entity().toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1);
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
	public static double travel(ContraptionPhysics.Drive drive) { return outputs.get(drive.root()).signedTravel; }
	public static double angle(ContraptionPhysics.Drive drive) { return outputs.get(drive.root()).angle; }
	public static double angularRate(ContraptionPhysics.Drive drive) { return outputs.get(drive.root()).angularRate; }
	public static double allowance(ContraptionPhysics.Drive drive) {
		double speed = NetworkDynamics.preciseSpeed(drive.shaft());
		double direction = Math.signum(speed == 0 ? drive.shaft().getTheoreticalSpeed() : speed);
		return allowance(drive, direction == 0 ? 1 : direction);
	}
	/** A blocked feed must refresh its tool load as soon as the shaft turns back inward. */
	public static int wakeDirection(ContraptionPhysics.Drive drive) {
		var output = outputs.get(drive.root());
		if (!advancing || sampling || output == null || output.ready || stalled(drive.root()) || stalled(drive.entity())) return 0;
		if (drive.shaft() instanceof LinearActuatorBlockEntity actuator && ((LinearActuatorAccessor) actuator).differentialcreate$waiting()) return 0;
		double speed = NetworkDynamics.preciseSpeed(drive.shaft());
		double direction = Math.signum(speed == 0 ? drive.shaft().getTheoreticalSpeed() : speed);
		if (direction == 0) direction = 1;
		return allowance(drive, direction) <= MOTION_EPSILON && allowance(drive, -direction) > MOTION_EPSILON
			? (int) -direction : 0;
	}
	public static double allowance(ContraptionPhysics.Drive drive, double direction) {
		var o = outputs.get(drive.root());
		return direction > 0 ? o.upperTravel - o.signedTravel : o.signedTravel - o.lowerTravel;
	}
	public static Vec3 rotate(Vec3 p, double degrees, Direction.Axis axis) { return VecHelper.rotate(p, (float) degrees, axis); }
	private static ActorContactPath.Point point(Vec3 p) { return new ActorContactPath.Point(p.x, p.y, p.z); }
	public static Vec3 vector(ActorContactPath.Point p) { return new Vec3(p.x(), p.y(), p.z()); }
}
