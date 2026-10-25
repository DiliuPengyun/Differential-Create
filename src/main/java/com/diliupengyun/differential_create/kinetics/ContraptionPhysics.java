package com.diliupengyun.differential_create.kinetics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.diliupengyun.differential_create.mixin.ContraptionAccessor;
import com.diliupengyun.differential_create.mixin.ControlledContraptionAccessor;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.StabilizedContraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.gantry.GantryContraption;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Shaft-driven moving structures share mass, air drag and actor work. Trains are left to Create. */
public final class ContraptionPhysics {
	private record CachedShape(int size, ContraptionGeometry.Shape shape) {}
	private record Owner(WeakReference<KineticBlockEntity> shaft, Direction.Axis axis, boolean rotation, double ratio) {}
	private static final Map<Contraption, CachedShape> shapes = java.util.Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<Contraption, Owner> owners = java.util.Collections.synchronizedMap(new WeakHashMap<>());

	public record Drive(KineticBlockEntity shaft, AbstractContraptionEntity entity, Direction.Axis axis,
		boolean rotation, double ratio, AbstractContraptionEntity root, BlockPos mount) implements ActorDrive {
		@Override public double speed() { return ActorFeed.speed(shaft); }
		@Override public double preferredSpeed() {
			double input = NetworkDynamics.preciseSpeed(shaft);
			return input != 0 ? input : shaft.getTheoreticalSpeed();
		}
		@Override public ActorContactPath path(MovementContext context) { return ContraptionMotion.path(this, context); }
		@Override public ActorContactPath directionPath(MovementContext context, Vec3 local) { return ContraptionMotion.directionPath(this, local); }
		@Override public double travel() { return ContraptionMotion.travel(this); }
		@Override public double angle() { return ContraptionMotion.angle(this); }
		@Override public double angularRate() { return ContraptionMotion.angularRate(this); }
		@Override public double allowance(double direction) { return ContraptionMotion.allowance(this, direction); }
		@Override public int wakeDirection() { return ContraptionMotion.wakeDirection(this); }
		@Override public boolean motionActive() { return ContraptionMotion.active(); }
		public boolean stabilized() { return entity != root; }
		private Vec3 translationInRoot() {
			Vec3 direction = new Vec3(axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0);
			if (!rotation) return direction.scale(ContraptionGeometry.LINEAR_RADIUS);
			return direction.cross(Vec3.atLowerCornerOf(mount));
		}
		public double inertia() {
			var shape = shape(entity.getContraption());
			if (stabilized()) {
				var v = translationInRoot();
				return ratio * ratio * shape.translatedInertia(v.x, v.y, v.z);
			}
			return ratio * ratio * (rotation ? shape.rotationInertia()[axis.ordinal()] : shape.linearInertia());
		}
		public double drag() {
			if (root.isStalled() || root.getContraption().stalled || entity.isStalled() || entity.getContraption().stalled) return 0;
			double motionRatio = ratio;
			double speed = Math.abs(ActorFeed.speed(shaft));
			if (shaft instanceof ClockworkBearingBlockEntity clock && speed > 0)
				motionRatio = Math.abs((ratio == .5 ? clock.getHourArmSpeed() : clock.getMinuteArmSpeed()) / (.3 * speed));
			if (shaft instanceof LinearActuatorBlockEntity linear) {
				if (((LinearActuatorAccessor) linear).differentialcreate$waiting()) return 0;
				if (ActorFeed.enabled(shaft)) {
					motionRatio = ratio;
				} else if (shaft instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity) {
					// Elevator's getter advances its arrival controller. Physics reads
					// must not advance that controller a second time.
					if (speed > 0) motionRatio = root.getDeltaMovement().length() * 512 / speed;
				} else if (speed > 0 && !ActorFeed.enabled(shaft)) motionRatio = Math.abs(linear.getMovementSpeed() * 512 / speed);
			}
			if (shaft instanceof GantryShaftBlockEntity && speed > 0 && !ActorFeed.enabled(shaft)) motionRatio = Math.min(1, .49 * 512 / speed);
			var shape = shape(entity.getContraption());
			if (stabilized()) {
				// Project the world velocity onto the child's own exposed face normals.
				var v = translationInRoot();
				if (rotation) v = root.applyRotation(v, 1);
				v = entity.reverseRotation(v, 1);
				return Math.pow(motionRatio, 3) * shape.translatedDrag(v.x, v.y, v.z);
			}
			return Math.pow(motionRatio, 3) * (rotation ? shape.rotationDrag()[axis.ordinal()] : shape.linearDrag(axis.ordinal()));
		}
		/** Fold local tool travel and work torque back through the same mechanical advantage. */
		public double workRatio(MovementContext context) {
			if (!rotation) return ratio;
			if (stabilized()) return ratio * translationInRoot().length() / ContraptionGeometry.LINEAR_RADIUS;
			var behaviour = com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(context.state);
			Vec3 p = Vec3.atLowerCornerOf(context.localPos)
				.add(behaviour == null ? Vec3.ZERO : behaviour.getActiveAreaOffset(context));
			double alongAxis = axis.choose(p.x, p.y, p.z);
			// A tool has its own finite size, even when its centre is on the rotation axis.
			double radius = Math.sqrt(Math.max(0, p.lengthSqr() - alongAxis * alongAxis) + 1.0 / 6);
			return ratio * radius / ContraptionGeometry.LINEAR_RADIUS;
		}
	}
	private ContraptionPhysics() {}

	public static boolean manages(KineticBlockEntity be) {
		return be instanceof MechanicalBearingBlockEntity || be instanceof ClockworkBearingBlockEntity
			|| be instanceof LinearActuatorBlockEntity || be instanceof GantryShaftBlockEntity;
	}
	public static List<Drive> drives(KineticBlockEntity be) {
		if (!manages(be)) return List.of();
		var result = new ArrayList<Drive>(2);
		if (be instanceof MechanicalBearingBlockEntity bearing)
			add(result, be, bearing.getMovedContraption(), be.getBlockState().getValue(BlockStateProperties.FACING).getAxis(), true, 1);
		else if (be instanceof ClockworkBearingAccessor clock) {
			var axis = be.getBlockState().getValue(BlockStateProperties.FACING).getAxis();
			add(result, be, clock.differentialcreate$hour(), axis, true, .5);
			add(result, be, clock.differentialcreate$minute(), axis, true, 1);
		} else if (be instanceof LinearActuatorBlockEntity linear && !((LinearActuatorAccessor) linear).differentialcreate$passive()) {
			var facing = be.getBlockState().hasProperty(BlockStateProperties.FACING)
				? be.getBlockState().getValue(BlockStateProperties.FACING) : Direction.DOWN;
			add(result, be, linear.movedContraption, facing.getAxis(), false,
				be instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity ? 2 : 1);
		} else if (be instanceof GantryShaftBlockEntity) {
			var state = ElasticKinetics.state(be);
			state.gantryAttachments.removeIf(e -> e.isRemoved());
			for (var entity : state.gantryAttachments)
				add(result, be, entity, be.getBlockState().getValue(GantryShaftBlock.FACING).getAxis(), false, 1);
		}
		return result;
	}
	private static void add(List<Drive> result, KineticBlockEntity shaft, AbstractContraptionEntity entity,
		Direction.Axis axis, boolean rotation, double ratio) {
		if (entity == null || entity.isRemoved() || entity.getContraption() == null) return;
		result.add(new Drive(shaft, entity, axis, rotation, ratio, entity, null));
		owners.put(entity.getContraption(), new Owner(new WeakReference<>(shaft), axis, rotation, ratio));
		// UUID lookup also finds freshly assembled children before Create mounts them on its first tick.
		for (var entry : ((ContraptionAccessor) entity.getContraption()).differentialcreate$stabilizedChildren().entrySet()) {
			var child = entity.getPassengers().stream().filter(e -> e.getUUID().equals(entry.getKey())).findFirst().orElse(null);
			if (child == null && entity.level() instanceof ServerLevel level) child = level.getEntity(entry.getKey());
			if (!(child instanceof OrientedContraptionEntity sub) || sub.isRemoved()
				|| !(sub.getContraption() instanceof StabilizedContraption)
				|| sub.getVehicle() != null && sub.getVehicle() != entity) continue;
			result.add(new Drive(shaft, sub, axis, rotation, ratio, entity, entry.getValue().getConnectedPos()));
			owners.put(sub.getContraption(), new Owner(new WeakReference<>(shaft), axis, rotation, ratio));
		}
	}
	/** Establish the shaft owner before native actors visit a world block, including after reload. */
	public static void bindActors(AbstractContraptionEntity entity) {
		if (entity.level().isClientSide) return;
		var root = entity;
		while (root.getContraption() instanceof StabilizedContraption && root.getVehicle() instanceof AbstractContraptionEntity parent
			&& parent.getContraption().getBearingPosOf(root.getUUID()) != null) root = parent;
		if (root instanceof ControlledContraptionAccessor controlled && controlled.differentialcreate$controller() instanceof KineticBlockEntity shaft)
			checkAttachments(shaft);
		else if (root instanceof GantryContraptionEntity gantry) {
			var shaft = gantryShaft(gantry);
			if (shaft != null) checkAttachments(shaft);
		}
	}
	public static Drive drive(MovementContext context) {
		Owner owner = owners.get(context.contraption);
		if (owner == null && context.contraption.entity != null) {
			bindActors(context.contraption.entity);
			owner = owners.get(context.contraption);
		}
		if (owner == null) return null;
		var shaft = owner.shaft.get();
		var entity = context.contraption.entity;
		if (shaft == null || shaft.isRemoved() || entity == null || entity.isRemoved()) return null;
		// Disassembly/end-of-travel must not leave a cached actor connected to the shaft.
		for (var drive : drives(shaft)) if (drive.entity == entity) return drive;
		return null;
	}
	public static ContraptionGeometry.Shape shape(Contraption contraption) {
		var blocks = contraption.getBlocks();
		var cached = shapes.get(contraption);
		if (cached != null && cached.size == blocks.size()) return cached.shape;
		var cubes = new HashSet<ContraptionGeometry.Cell>();
		blocks.forEach((p, info) -> { if (!info.state().isAir()) cubes.add(new ContraptionGeometry.Cell(p.getX(), p.getY(), p.getZ())); });
		var shape = ContraptionGeometry.measure(cubes);
		shapes.put(contraption, new CachedShape(blocks.size(), shape));
		return shape;
	}
	public static void invalidate(Contraption contraption) {
		shapes.remove(contraption);
		var owner = owners.get(contraption);
		if (owner != null) invalidate(owner.shaft.get());
	}
	private static void invalidate(KineticBlockEntity be) {
		if (be != null && be.hasNetwork()) ((INetworkDynamics) be.getOrCreateNetwork()).differentialcreate$data().invalidate();
	}
	public static double inertia(KineticBlockEntity be) { return manages(be) ? drives(be).stream().mapToDouble(Drive::inertia).sum() : 0; }
	public static double airDrag(KineticBlockEntity be) {
		if (!manages(be)) return 0;
		var state = ElasticKinetics.state(be);
		if (be.getLevel() != null && be.getLevel().isClientSide) return state.structureDrag;
		var drives = drives(be);
		if (drives.size() < expectedCount(be, drives) && Double.isFinite(state.pendingStructureDrag)) return state.pendingStructureDrag;
		return drives.stream().mapToDouble(Drive::drag).sum();
	}
	private static int expectedCount(KineticBlockEntity be, List<Drive> drives) {
		int count = 0;
		for (var drive : drives) if (!drive.stabilized())
			count += 1 + ((ContraptionAccessor) drive.entity.getContraption()).differentialcreate$stabilizedChildren().size();
		return awaitingEntity(be) ? Math.max(count, ElasticKinetics.state(be).pendingStructureCount) : count;
	}
	public static boolean incomplete(KineticBlockEntity be) {
		if (!manages(be)) return false;
		var drives = drives(be);
		return drives.size() < expectedCount(be, drives);
	}
	public static double workTorque(KineticBlockEntity be) {
		var state = ElasticKinetics.state(be);
		if (be.getLevel() != null && be.getLevel().isClientSide) return state.structureLoad;
		double torque = 0;
		for (var drive : drives(be)) for (var pair : drive.entity.getContraption().getActors()) {
			var context = pair.getRight();
			if (context != null && (!ActorFeed.enabled(be) || MovingDeployerWork.isHand(context)))
				torque += ContraptionActorWork.of(context).torque(drive)
					+ com.diliupengyun.differential_create.kinetics.portable.PortableDocking.torque(context);
		}
		return torque;
	}
	public static double feedLoad(KineticBlockEntity be) {
		double torque = 0;
		for (var drive : drives(be)) for (var pair : drive.entity().getContraption().getActors()) {
			var ctx = pair.getRight();
			if (ctx != null && !MovingDeployerWork.isHand(ctx)) torque += ContraptionActorWork.of(ctx).torque(drive)
				+ com.diliupengyun.differential_create.kinetics.portable.PortableDocking.torque(ctx);
		}
		return torque;
	}
	public static boolean awaitingEntity(KineticBlockEntity be) {
		return be instanceof MechanicalBearingBlockEntity bearing && bearing.isRunning()
			|| be instanceof ClockworkBearingBlockEntity clock && clock.isRunning()
			|| be instanceof LinearActuatorBlockEntity actuator && actuator.running
			|| be instanceof GantryShaftBlockEntity && !ElasticKinetics.state(be).gantryAttachments.isEmpty();
	}
	public static void checkAttachments(KineticBlockEntity be) {
		if (!manages(be)) return;
		var drives = drives(be);
		var state = ElasticKinetics.state(be);
		var attached = new HashSet<java.util.UUID>();
		for (var drive : drives) attached.add(drive.entity.getUUID());
		boolean changed = !attached.equals(state.structureAttachments);
		state.structureAttachments = attached;
		if (!drives.isEmpty() && drives.size() >= expectedCount(be, drives) || !awaitingEntity(be)) {
			changed |= Float.isFinite(state.pendingStructureInertia);
			state.pendingStructureInertia = Float.NaN;
			state.pendingStructureDrag = Double.NaN;
			state.pendingStructureCount = 0;
		}
		if (changed) invalidate(be);
	}
	public static GantryShaftBlockEntity gantryShaft(GantryContraptionEntity entity) {
		if (!(entity.getContraption() instanceof GantryContraption cart)) return null;
		var p = BlockPos.containing(entity.getAnchorVec().add(.5, .5, .5)).relative(cart.getFacing().getOpposite());
		return entity.level().getBlockEntity(p) instanceof GantryShaftBlockEntity shaft ? shaft : null;
	}
	/** The body still belongs to its last owner if the support unloads before the entity saves. */
	public static double gantrySpeedForSave(GantryContraptionEntity entity, double fallback) {
		var owner = owners.get(entity.getContraption());
		var shaft = owner == null ? null : owner.shaft.get();
		if (shaft != null) {
			double speed = ActorFeed.enabled(shaft) ? ActorFeed.of(shaft).speed : ElasticKinetics.state(shaft).physicalSpeed;
			if (Double.isFinite(speed)) return speed;
		}
		return fallback;
	}
	/** Transfer the carriage's momentum when its pinion reaches the next world shaft. */
	public static void bindGantry(GantryContraptionEntity entity, double restoredSpeed) {
		if (entity.level().isClientSide || entity.getContraption() == null) return;
		var next = gantryShaft(entity);
		var owner = owners.get(entity.getContraption());
		var previous = owner == null ? null : owner.shaft.get();
		if (previous == next) return;
		double carriedSpeed = previous == null ? Double.isFinite(restoredSpeed) ? restoredSpeed : 0
			: gantrySpeedForSave(entity, Double.isFinite(restoredSpeed) ? restoredSpeed : 0);
		if (previous != null) {
			ElasticKinetics.state(previous).gantryAttachments.remove(entity);
			invalidate(previous);
		}
		if (next == null) { owners.remove(entity.getContraption()); return; }
		double oldInertia = InertiaRegistry.getInertia(next), oldSpeed = NetworkDynamics.preciseSpeed(next);
		boolean separate = ActorFeed.enabled(next);
		double outputInertia = separate ? ActorFeed.of(next).inertia : 0;
		double outputSpeed = separate ? ActorFeed.of(next).speed : 0;
		ElasticKinetics.state(next).gantryAttachments.add(entity);
		checkAttachments(next);
		double newInertia = InertiaRegistry.getInertia(next);
		var state = ElasticKinetics.state(next);
		if (ActorFeed.enabled(next)) {
			var feed = ActorFeed.of(next);
			feed.speed = (outputInertia * outputSpeed + Math.max(0, feed.inertia - outputInertia) * carriedSpeed) / feed.inertia;
		} else if (newInertia > 0) state.physicalSpeed = (oldInertia * oldSpeed + (newInertia - oldInertia) * carriedSpeed) / newInertia;
		state.lastInertia = newInertia;
		if (previous != null) ElasticKinetics.state(previous).lastInertia = InertiaRegistry.getInertia(previous);
		invalidate(next);
	}
	public static void detach(AbstractContraptionEntity entity) {
		var owner = owners.remove(entity.getContraption());
		if (owner == null) return;
		var be = owner.shaft.get();
		if (be == null) return;
		ElasticKinetics.state(be).gantryAttachments.remove(entity);
		invalidate(be);
	}
	/** The child may unload before its controller writes NBT; retain the complete pre-unload summary. */
	public static void unloading(AbstractContraptionEntity entity) {
		var owner = owners.get(entity.getContraption());
		if (owner == null) { bindActors(entity); owner = owners.get(entity.getContraption()); }
		var shaft = owner == null ? null : owner.shaft.get();
		if (shaft == null || shaft.isRemoved()) return;
		var state = ElasticKinetics.state(shaft);
		double inertia = InertiaRegistry.getInertia(shaft) + (ActorFeed.enabled(shaft) ? ActorFeed.of(shaft).inertia : 0), drag = airDrag(shaft);
		int count = expectedCount(shaft, drives(shaft));
		state.pendingStructureInertia = (float) inertia;
		state.pendingStructureDrag = drag;
		state.pendingStructureCount = count;
	}
	/** A dismantled child must not remain as a missing UUID in its parent's saved attachment list. */
	public static void disassembling(AbstractContraptionEntity entity) {
		// Immediate teardown can precede the first entity tick that normally mounts children.
		if (!(entity.getContraption() instanceof StabilizedContraption)) {
			bindActors(entity);
			var owner = owners.get(entity.getContraption());
			var shaft = owner == null ? null : owner.shaft.get();
			if (shaft != null) for (var drive : drives(shaft))
				if (drive.root == entity && drive.stabilized() && drive.entity.getVehicle() == null) drive.entity.startRiding(entity);
		}
		if (!(entity.getContraption() instanceof StabilizedContraption)) return;
		var owner = owners.get(entity.getContraption());
		var shaft = owner == null ? null : owner.shaft.get();
		if (shaft == null) { bindActors(entity); owner = owners.get(entity.getContraption()); shaft = owner == null ? null : owner.shaft.get(); }
		if (shaft == null) return;
		for (var drive : drives(shaft)) if (drive.entity == entity && drive.stabilized()) {
			((ContraptionAccessor) drive.root.getContraption()).differentialcreate$stabilizedChildren().remove(entity.getUUID());
			var state = ElasticKinetics.state(shaft);
			state.pendingStructureCount = Math.max(0, state.pendingStructureCount - 1);
			if (Float.isFinite(state.pendingStructureInertia)) state.pendingStructureInertia = (float) Math.max(0, state.pendingStructureInertia - drive.inertia());
			if (Double.isFinite(state.pendingStructureDrag)) state.pendingStructureDrag = Math.max(0, state.pendingStructureDrag - drive.drag());
			invalidate(shaft);
			break;
		}
	}
	public static void write(KineticBlockEntity be, CompoundTag tag) {
		if (!manages(be)) return;
		tag.putFloat("DCStructureInertia", (float) (InertiaRegistry.getInertia(be) + (ActorFeed.enabled(be) ? ActorFeed.of(be).inertia : 0)));
		var feed = new CompoundTag(); ActorFeed.of(be).write(feed); tag.put("DCActorFeedDrive", feed);
		tag.putInt("DCStructureCount", expectedCount(be, drives(be)));
		tag.putDouble("DCStructureDrag", airDrag(be));
		tag.putDouble("DCStructureLoad", workTorque(be));
	}
	public static float readSavedInertia(CompoundTag tag, String key) {
		float inertia = tag.getFloat(key);
		if (!tag.contains(key) || !Float.isFinite(inertia) || inertia < 0) return Float.NaN;
		return inertia;
	}
	public static void read(KineticBlockEntity be, CompoundTag tag, boolean packet) {
		if (!manages(be)) return;
		var state = ElasticKinetics.state(be);
		state.pendingStructureInertia = readSavedInertia(tag, "DCStructureInertia");
		if (tag.contains("DCActorFeedDrive")) state.actorFeed.read(tag.getCompound("DCActorFeedDrive"));
		state.pendingStructureCount = Math.max(0, tag.getInt("DCStructureCount"));
		double drag = tag.getDouble("DCStructureDrag"), load = tag.getDouble("DCStructureLoad");
		state.structureDrag = Double.isFinite(drag) && drag >= 0 ? drag : 0;
		state.structureLoad = Double.isFinite(load) && load >= 0 ? load : 0;
		state.pendingStructureDrag = tag.contains("DCStructureDrag") ? state.structureDrag : Double.NaN;
	}
}
