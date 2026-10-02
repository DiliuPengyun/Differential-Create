package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.diliupengyun.differential_create.mixin.RotationPropagatorInvoker;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Rebuilds only affected loaded components, atomically, after topology events. */
public final class NetworkTopology {
	private static final Map<Level, Set<KineticBlockEntity>> pending = new LinkedHashMap<>();
	private static final Comparator<KineticBlockEntity> ORDER = Comparator
		.comparing((KineticBlockEntity be) -> !be.isSource()).thenComparing(KineticBlockEntity::getBlockPos);
	private static boolean installing;
	private static boolean probeClutches;

	private NetworkTopology() {}

	public static boolean probingClutches() { return probeClutches; }

	public static void mark(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (installing || level == null || level.isClientSide)
			return;
		Set<KineticBlockEntity> seeds = pending.computeIfAbsent(level, $ -> new LinkedHashSet<>());
		seeds.add(be);
		if (be.hasNetwork()) {
			KineticNetwork net = be.getOrCreateNetwork();
			seeds.addAll(net.members.keySet());
			seeds.addAll(((INetworkDynamics) net).differentialcreate$getRatios().keySet());
		}
		// Removal callbacks happen before the world replaces the old block.
		for (BlockPos pos : locations(be))
			if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof KineticBlockEntity neighbour)
				seeds.add(neighbour);
	}

	public static void flush() {
		for (int pass = 0; pass < 32 && !pending.isEmpty(); pass++) {
			Map<Level, Set<KineticBlockEntity>> changes = new LinkedHashMap<>(pending);
			if (changes.keySet().stream().allMatch(level -> level.tickRateManager().isFrozen())) break;
			pending.clear();
			changes.forEach((level, seeds) -> {
				if (!level.tickRateManager().isFrozen())
					rebuild(level, seeds);
				else
					pending.computeIfAbsent(level, $ -> new LinkedHashSet<>()).addAll(seeds);
			});
		}
	}

	public static void clear() { pending.clear(); }

	private static List<BlockPos> locations(KineticBlockEntity be) {
		List<BlockPos> positions = new ArrayList<>();
		for (Direction direction : Direction.values())
			positions.add(be.getBlockPos().relative(direction));
		if (be.getBlockState().getBlock() instanceof IRotate rotate)
			return be.addPropagationLocations(rotate, be.getBlockState(), positions);
		return positions;
	}

	private static boolean alive(Level level, KineticBlockEntity be) {
		return !be.isRemoved() && level.isLoaded(be.getBlockPos()) && level.getBlockEntity(be.getBlockPos()) == be;
	}

	/** Uses Create's geometric/custom connections even when both shafts are at rest. */
	public static float connection(KineticBlockEntity from, KineticBlockEntity to) {
		if (RotationPropagatorInvoker.differentialcreate$isControllerEdge(from.getBlockState(), to.getBlockState(),
			to.getBlockPos().subtract(from.getBlockPos())))
			return ((SpeedControllerBlockEntity) to).targetSpeed.getValue() / 16f;
		if (RotationPropagatorInvoker.differentialcreate$isControllerEdge(to.getBlockState(), from.getBlockState(),
			from.getBlockPos().subtract(to.getBlockPos()))) {
			float ratio = ((SpeedControllerBlockEntity) from).targetSpeed.getValue() / 16f;
			return ratio == 0 ? 0 : 1 / ratio;
		}
		return RotationPropagatorInvoker.differentialcreate$rotationModifier(from, to);
	}

	private static void orient(KineticBlockEntity be, KineticBlockEntity neighbour) {
		if (be instanceof DirectionalShaftHalvesBlockEntity && !be.hasSource())
			be.source = neighbour.getBlockPos();
	}

	/** Only the output face crosses the clutch's friction surface. */
	private static ClutchBlockEntity separatingClutch(KineticBlockEntity a, KineticBlockEntity b) {
		if (a instanceof ClutchBlockEntity clutch && ClutchPhysics.output(clutch) == b) return clutch;
		if (b instanceof ClutchBlockEntity clutch && ClutchPhysics.output(clutch) == a) return clutch;
		return null;
	}

	private record Edge(KineticBlockEntity a, KineticBlockEntity b, float ratio, ClutchBlockEntity clutch) {}

	private static void rebuild(Level level, Set<KineticBlockEntity> seeds) {
		Set<KineticBlockEntity> nodes = new LinkedHashSet<>();
		Set<KineticNetwork> oldNetworks = new HashSet<>();
		ArrayDeque<KineticBlockEntity> queue = new ArrayDeque<>(seeds.stream().sorted(ORDER).toList());
		// NBT restoration can replace a member's id before it rejoins. Include
		// its prior registry membership so an orphan cannot keep a stale load cache.
		for (KineticNetwork network : TorquePropagatorAccessor.differentialcreate$getNetworks().getOrDefault(level, Map.of()).values())
			if (seeds.stream().anyMatch(network.members::containsKey)) {
				oldNetworks.add(network);
				queue.addAll(network.members.keySet());
			}
		probeClutches = true;
		try {
			while (!queue.isEmpty()) {
				KineticBlockEntity be = queue.removeFirst();
				KineticNetwork existing = be.hasNetwork() ? TorquePropagatorAccessor.differentialcreate$getNetworks()
					.getOrDefault(level, Map.of()).get(be.network) : null;
				if (existing != null && oldNetworks.add(existing)) queue.addAll(existing.members.keySet());
				if (!alive(level, be) || !nodes.add(be))
					continue;
				if (be.hasNetwork()) {
					KineticNetwork net = be.getOrCreateNetwork();
					NetworkDynamics.restoreMember(be, net);
					if (oldNetworks.add(net))
						queue.addAll(net.members.keySet());
				}
				for (BlockPos pos : locations(be)) {
					if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof KineticBlockEntity next)
						|| !alive(level, next))
						continue;
					// Establish the reference face before querying a gearbox or split shaft.
					BlockPos oldSource = next.source;
					orient(next, be);
					if (connection(be, next) != 0 || connection(next, be) != 0) {
						orient(be, next);
						queue.add(next);
					} else {
						next.source = oldSource;
					}
				}
			}
		} finally {
			probeClutches = false;
		}
		if (nodes.isEmpty() && oldNetworks.isEmpty())
			return;

		Map<KineticBlockEntity, KineticNetwork> old = new HashMap<>();
		Map<KineticBlockEntity, Group> groups = new HashMap<>();
		List<Edge> edges = new ArrayList<>();
		for (KineticBlockEntity be : nodes.stream().sorted(ORDER).toList()) {
			KineticNetwork net = be.hasNetwork() ? be.getOrCreateNetwork() : null;
			old.put(be, net);
			double local = net == null ? 0 : NetworkDynamics.localSpeed(be, net);
			ElasticKinetics.state(be).physicalSpeed = local;
            groups.put(be, new Group(be, local));
			for (BlockPos pos : locations(be)) {
				if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof KineticBlockEntity next)
					|| !nodes.contains(next) || be.getBlockPos().compareTo(next.getBlockPos()) >= 0)
					continue;
				float ratio = connection(be, next);
				if (Float.isFinite(ratio) && ratio != 0)
					edges.add(new Edge(be, next, ratio, separatingClutch(be, next)));
			}
		}
		// Network membership and nominal ratios are metadata. Never project physical rotor speeds.
		for (Edge edge : edges) {
			Group a = groups.get(edge.a), b = groups.get(edge.b);
			if (a == b) continue;
			double q = a.ratios.get(edge.a) * edge.ratio / b.ratios.get(edge.b);
			if (ORDER.compare(a.anchor, b.anchor) > 0) {
				Group swap = a; a = b; b = swap; q = 1 / q;
			}
			// Reference speed is only a legacy summary; member velocities remain independent.
			a.speed = RotationMath.mergedSpeed(a.inertia, a.speed, b.inertia, b.speed, q);
			a.inertia += b.inertia * q * q;
			for (var member : b.ratios.entrySet()) {
				a.ratios.put(member.getKey(), (float) (member.getValue() * q));
				groups.put(member.getKey(), a);
			}
		}
		for (KineticBlockEntity be : nodes) {
			Set<BlockPos> retained = new HashSet<>();
			for (Edge edge : edges) if (edge.a == be) retained.add(edge.b.getBlockPos());
			ElasticKinetics.state(be).elasticConnections.keySet().removeIf(pos -> level.isLoaded(pos) && !retained.contains(pos));
		}
		install(level, nodes, oldNetworks, new LinkedHashSet<>(groups.values()));
        for (Edge edge : edges) {
            var data = ((INetworkDynamics) edge.a.getOrCreateNetwork()).differentialcreate$data();
            data.connections.add(new ElasticKinetics.Connection(edge.a, edge.b, edge.ratio, edge.clutch));
        }
        for (Group group : new LinkedHashSet<>(groups.values()))
            ElasticKinetics.alignAngles(group.anchor.getOrCreateNetwork());
	}

	private static void install(Level level, Set<KineticBlockEntity> nodes, Set<KineticNetwork> oldNetworks,
		Set<Group> groups) {
		Map<Long, KineticNetwork> registry = TorquePropagatorAccessor.differentialcreate$getNetworks()
			.computeIfAbsent(level, $ -> new HashMap<>());
		Map<KineticBlockEntity, Float> previous = new HashMap<>();
		nodes.forEach(be -> previous.put(be, be.getSpeed()));
		// Retain a saved reference frame across partial chunk loads. A removed
		// anchor is reselected; an unloaded anchor keeps its ID and basis.
		Set<Long> retained = new HashSet<>();
		for (Group group : groups) {
			for (KineticNetwork old : oldNetworks.stream().sorted(Comparator.comparing(net -> net.id)).toList()) {
				INetworkDynamics oldDynamics = (INetworkDynamics) old;
				BlockPos anchor = oldDynamics.differentialcreate$data().anchor;
				if (anchor == null || retained.contains(old.id)) continue;
				if (level.isLoaded(anchor) && (!(level.getBlockEntity(anchor) instanceof KineticBlockEntity be)
					|| !group.ratios.containsKey(be))) continue;
				for (var entry : group.ratios.entrySet()) {
					Float oldRatio = oldDynamics.differentialcreate$getRatios().get(entry.getKey());
					if (oldRatio == null || oldRatio == 0) continue;
					float scale = oldRatio / entry.getValue();
					group.ratios.replaceAll((be, ratio) -> ratio * scale);
					group.speed /= scale;
					group.savedAnchor = anchor;
					group.savedId = old.id;
					retained.add(old.id);
					break;
				}
				if (group.savedId != null) break;
			}
		}
		installing = true;
		try {
			for (KineticNetwork old : oldNetworks) {
				registry.remove(old.id, old);
				old.members.clear();
				old.sources.clear();
				((INetworkDynamics) old).differentialcreate$getRatios().clear();
				ClientSpeedSync.networkDissolved(level, old.id);
			}
			for (Group group : groups) {
				KineticNetwork network = new KineticNetwork();
				long id = group.savedId == null ? group.anchor.getBlockPos().asLong() : group.savedId;
				while (registry.containsKey(id)) id++;
				network.id = id;
				network.initialized = true;
				registry.put(id, network);
				INetworkDynamics dynamics = (INetworkDynamics) network;
				dynamics.differentialcreate$data().initialized = true;
				dynamics.differentialcreate$data().anchor = group.savedAnchor;
				dynamics.differentialcreate$data().jammed = group.jammed;
				dynamics.differentialcreate$setSpeed(group.speed);
				dynamics.differentialcreate$setLastNonZeroTarget(16);
				dynamics.differentialcreate$getRatios().putAll(group.ratios);
				for (var entry : group.ratios.entrySet()) {
					KineticBlockEntity be = entry.getKey();
					be.network = id;
					be.setSpeed(16 * entry.getValue());
					be.updateSpeed = false;
					be.networkDirty = false;
					((IMemberDynamics) be).differentialcreate$member().sourceCommand = be.getGeneratedSpeed();
					network.members.put(be, be.calculateStressApplied());
					if (MachineActivity.manages(be)) dynamics.differentialcreate$data().activityMembers.add(be);
					if (be instanceof ClutchBlockEntity clutch) dynamics.differentialcreate$data().clutches.add(clutch);
					if (be.isSource()) network.sources.put(be, be.calculateAddedStressCapacity());
				}
				// Rebuild a source tree for sequence contexts and Create's rendering.
				Set<KineticBlockEntity> visited = new HashSet<>();
				ArrayDeque<KineticBlockEntity> frontier = new ArrayDeque<>();
				frontier.add(group.anchor);
				visited.add(group.anchor);
				if (!(group.anchor instanceof DirectionalShaftHalvesBlockEntity)) group.anchor.source = null;
				while (!frontier.isEmpty()) {
					KineticBlockEntity from = frontier.removeFirst();
					for (BlockPos pos : locations(from)) {
						BlockEntity block = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
						if (!(block instanceof KineticBlockEntity to) || !group.ratios.containsKey(to)
							|| connection(from, to) == 0 || !visited.add(to)) continue;
						if (!(to instanceof DirectionalShaftHalvesBlockEntity)) to.source = from.getBlockPos();
						if (!(to instanceof SequencedGearshiftBlockEntity))
							to.sequenceContext = from.sequenceContext;
						frontier.add(to);
					}
				}
				network.updateNetwork();
				for (KineticBlockEntity be : group.ratios.keySet()) {
					be.onSpeedChanged(previous.get(be));
					be.sendData();
				}
				ClientSpeedSync.markStructureDirty(network, level);
			}
		} finally {
			installing = false;
		}
	}

	private static final class Group {
		final KineticBlockEntity anchor;
		final Map<KineticBlockEntity, Float> ratios = new LinkedHashMap<>();
		double inertia, speed;
		boolean jammed;
		BlockPos savedAnchor;
		Long savedId;
		Group(KineticBlockEntity be, double speed) {
			anchor = be;
			savedAnchor = be.getBlockPos();
			ratios.put(be, 1f);
			inertia = InertiaRegistry.getInertia(be);
			this.speed = speed;
		}
	}
}
