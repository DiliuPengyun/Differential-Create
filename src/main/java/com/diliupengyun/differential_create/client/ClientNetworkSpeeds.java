package com.diliupengyun.differential_create.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.diliupengyun.differential_create.kinetics.NetworkPhysics;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.NetworkStatePayload;
import com.diliupengyun.differential_create.kinetics.NetworkStructurePayload;
import com.diliupengyun.differential_create.kinetics.RotorStatePayload;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.diliupengyun.differential_create.mixin.MechanicalBearingAccessor;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side mirror of local rotor states. The server aligns assembly
 * phases across shaft/gear contacts while keeping clutch halves independent.
 * Structure and aggregate state packets provide a fallback until the local
 * rotor packet arrives; they cannot replace the angles of slipping rotors.
 * <p>
 * Only runs on the client; lookups degrade gracefully to "no data" so
 * block entities never synced yet fall back to their vanilla field.
 */
public final class ClientNetworkSpeeds {

	private static final Map<ResourceKey<Level>, Map<Long, Entry>> networks = new HashMap<>();
	private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> memberToNetwork = new HashMap<>();
	private record Phase(double previous, double current, RotationFrame frame, Rotor rotor) {}
	private static final Map<ResourceKey<Level>, Map<BlockPos, Phase>> retiredPhases = new HashMap<>();

	private ClientNetworkSpeeds() {}

	/** Extrapolated state of one network: shared ω plus the ratio table. */
	private static final class Entry {
		final Map<BlockPos, Rotor> rotors = new HashMap<>();
		double kineticEnergy = Double.NaN;
		double elasticEnergy = Double.NaN;
		LoadFactor.Reading loadFactor = LoadFactor.Reading.UNKNOWN;
		final Map<BlockPos, Float> ratios = new HashMap<>();
		float speed;
		NetworkPhysics.Balance balance = new NetworkPhysics.Balance(0, 0, 0, 0, 0);
		boolean hasState;
		double previousTheta;
		RotationFrame renderFrame;
		boolean rotationDirty;
		/**
		 * Accumulated rotation angle of the network, degrees. Advanced with
		 * the analytical shaft angle alongside the speed so member angles r_i × θ stay
		 * continuous through ramps (the shader animates offset + t × speed,
		 * which is discontinuous for a varying speed — the visuals re-anchor
		 * their offset to this θ instead).
		 */
		double theta;

		/** Advances ω by one client tick with the server's exact steps. */
		void advance(int renderTick) {
			previousTheta = theta;
			var motion = com.diliupengyun.differential_create.kinetics.RotationMath.motion(speed, balance.t0(), balance.k(), balance.l(), balance.j(), balance.fanDrag(), NetworkPhysics.TICK_MINUTES);
			speed = (float) motion.speed();
			// One calibrated turn is 360 degrees; signed motion includes a reversal.
			theta += motion.angle() * 360;
			setFrame(new RotationFrame(renderTick, previousTheta, theta - previousTheta));
		}

		void setFrame(RotationFrame frame) {
			// Retain identical coefficients at steady speed. New/rebuilt visuals
			// then receive exactly the same phase as the existing instances.
			if (renderFrame == null || !renderFrame.sameMotion(frame)) {
				renderFrame = frame;
				rotationDirty = true;
			}
		}

		void freeze(int renderTick) {
			if (renderFrame == null) {
				setFrame(new RotationFrame(renderTick, theta, 0));
			} else if (renderFrame.degreesPerTick() != 0) {
				previousTheta = theta = renderFrame.angleAt(ClientKineticAngles.renderTick());
				setFrame(new RotationFrame(renderTick, theta, 0));
			}
		}
	}

	private static final class Rotor {
		float speed, torque, steamVent;
		double elasticEnergy = Double.NaN;
		final RotorPoseAnimation animation = new RotorPoseAnimation();
		BearingAnimation bearing;
	}

	public static void applyRotors(ResourceKey<Level> dimension, RotorStatePayload payload) {
		Entry entry = networks.computeIfAbsent(dimension, $ -> new HashMap<>())
			.computeIfAbsent(payload.networkId(), $ -> new Entry());
		entry.kineticEnergy = payload.energy();
		entry.elasticEnergy = payload.elasticEnergy();
		entry.loadFactor = payload.loadFactor();
		for (var update : payload.rotors()) {
			Rotor rotor = entry.rotors.computeIfAbsent(update.pos(), $ -> new Rotor());
			rotor.speed = update.speed(); rotor.torque = update.torque();
			rotor.steamVent = update.steamVent();
			rotor.elasticEnergy = update.elasticEnergy();
			rotor.animation.receive(update.angle(), update.output() == null ? null : update.output().angle());
			if (update.bearing() == null) rotor.bearing = null;
			else {
				if (rotor.bearing == null) rotor.bearing = new BearingAnimation();
				rotor.bearing.receive(update.bearing().entityId(), update.bearing().angle());
			}
		}
	}

	public static void applyStructure(ResourceKey<Level> dimension, long networkId,
		List<NetworkStructurePayload.Entry> entries) {
		Map<Long, Entry> levelNetworks = networks.computeIfAbsent(dimension, $ -> new HashMap<>());
		Entry old = levelNetworks.get(networkId);
		Map<BlockPos, Phase> phases = retiredPhases.computeIfAbsent(dimension, $ -> new HashMap<>());
		if (old != null)
			old.ratios.forEach((pos, ratio) -> phases.put(pos, new Phase(old.previousTheta * ratio, old.theta * ratio,
				old.renderFrame == null ? null : old.renderFrame.scaled(ratio), old.rotors.get(pos))));
		Map<BlockPos, Long> members = memberToNetwork.get(dimension);
		if (members != null)
			members.values()
				.removeIf(id -> id == networkId);
		if (entries.isEmpty()) {
			// Network dissolved
			levelNetworks.remove(networkId);
			return;
		}
		Entry entry = networks.computeIfAbsent(dimension, $ -> new HashMap<>())
			.computeIfAbsent(networkId, $ -> new Entry());
		// A rebuild changes network identity or basis without rewinding the
		// surviving shaft. Dissolve/install packets are sent in the same batch.
		for (var member : entries) {
			Phase phase = phases.get(member.pos());
			if (phase != null && member.ratio() != 0) {
				entry.previousTheta = phase.previous / member.ratio();
				entry.theta = phase.current / member.ratio();
				entry.renderFrame = phase.frame == null ? null : phase.frame.scaled(1.0 / member.ratio());
				break;
			}
		}
		entry.rotationDirty = true;
		entry.loadFactor = LoadFactor.Reading.UNKNOWN;
		entry.elasticEnergy = Double.NaN;
		entry.ratios.clear();
		entry.rotors.keySet().retainAll(entries.stream().map(NetworkStructurePayload.Entry::pos).toList());
		Map<BlockPos, Long> memberMap = memberToNetwork.computeIfAbsent(dimension, $ -> new HashMap<>());
		for (NetworkStructurePayload.Entry structureEntry : entries) {
			Phase phase = phases.get(structureEntry.pos());
			if (phase != null && phase.rotor != null) entry.rotors.putIfAbsent(structureEntry.pos(), phase.rotor);
			entry.ratios.put(structureEntry.pos(), structureEntry.ratio());
			memberMap.put(structureEntry.pos(), networkId);
		}
		// Preserve animation across a rebuild, but wait for the new contact energies.
		entry.rotors.values().forEach(rotor -> rotor.elasticEnergy = Double.NaN);
	}

	public static void applyState(ResourceKey<Level> dimension, NetworkStatePayload payload) {
		Entry entry = networks.computeIfAbsent(dimension, $ -> new HashMap<>())
			.computeIfAbsent(payload.networkId(), $ -> new Entry());
		entry.speed = payload.speed();
		entry.balance = new NetworkPhysics.Balance(payload.torque(), payload.damping(), payload.load(), payload.inertia(), payload.drag());
		entry.hasState = true;
	}

	/** Plays local server angle segments; aggregate extrapolation is only an unsynced fallback. */
	public static void onClientTick() {
		retiredPhases.clear();
		Minecraft minecraft = Minecraft.getInstance();
		Level level = minecraft.level;
		if (level == null) {
			if (!networks.isEmpty()) {
				networks.clear();
				memberToNetwork.clear();
			}
			ClientClutchAngles.onClientTick();
			return;
		}
		// Single-player pause freezes the server, so the extrapolation and
		// angle accumulation must freeze too (multiplayer has no pause)
		if (minecraft.isPaused())
			return;
		// Drop data of other dimensions when switching worlds
		ResourceKey<Level> dimension = level.dimension();
		networks.keySet()
			.retainAll(Set.of(dimension));
		memberToNetwork.keySet()
			.retainAll(Set.of(dimension));
		Map<Long, Entry> levelNetworks = networks.get(dimension);
		if (levelNetworks != null) {
			int renderTick = minecraft.levelRenderer.getTicks();
			double partialRenderTick = ClientKineticAngles.renderTick();
			levelNetworks.forEach((id, entry) -> {
				if (!entry.hasState) return;
				entry.rotors.forEach((pos, rotor) -> {
					boolean frozen = level.tickRateManager().isFrozen();
					rotor.animation.advance(renderTick, partialRenderTick, frozen);
					if (rotor.bearing != null) rotor.bearing.advance(renderTick, partialRenderTick, frozen);
				});
				if (!entry.rotors.isEmpty()) entry.rotationDirty = true;
				if (level.tickRateManager().isFrozen())
					entry.freeze(renderTick);
				else
					entry.advance(renderTick);
				if (entry.rotationDirty) {
					// Refresh the entire network together, including the final small
					// speed change and topology-only changes. Per-member thresholds
					// would leave different shader rates/phases on connected shafts.
					for (BlockPos pos : entry.ratios.keySet())
						if (level.getBlockEntity(pos) instanceof KineticBlockEntity be
							&& java.util.Objects.equals(id, be.network)) {
							if (be instanceof MechanicalBearingBlockEntity bearing && bearing.getMovedContraption() != null) {
								RotationFrame frame = bearingRotationOf(bearing, bearing.getMovedContraption().getId());
								if (frame != null) {
									bearing.setAngle(BearingAnimation.angleAt(frame, 1));
									((MechanicalBearingAccessor) bearing).differentialcreate$applyRotation();
								}
							}
							VisualizationHelper.queueUpdate(be);
						}
					entry.rotationDirty = false;
				}
			});
		}
		ClientClutchAngles.onClientTick();
	}

	/**
	 * Physical speed of a member, falling back to the extrapolated network
	 * reference, or null when nothing was synced for this block entity yet — the
	 * caller falls back to the vanilla field then.
	 */
	public static Float speedOf(KineticBlockEntity kbe) {
		Entry entry = entryOf(kbe);
		if (entry == null)
			return null;
		Rotor rotor = entry.rotors.get(kbe.getBlockPos());
		if (rotor != null) return rotor.speed;
		Float ratio = entry.ratios.get(kbe.getBlockPos());
		return ratio == null ? null : ratio * entry.speed;
	}

	/** Server-measured bypass flow; unavailable data must not invent an exhaust plume. */
	public static Float steamVentOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		Rotor rotor = entry == null ? null : entry.rotors.get(be.getBlockPos());
		return rotor == null ? null : rotor.steamVent;
	}

	/**
	 * Accumulated assembly-aligned rotation angle of a member in degrees,
	 * or null when unsynced. Drives the continuous-animation offset
	 * compensation in the render hooks.
	 */
	public static Double thetaOf(KineticBlockEntity kbe) {
		RotationFrame frame = rotationOf(kbe);
		return frame == null ? null : frame.angleAt(ClientKineticAngles.renderTick());
	}

	public static RotationFrame rotationOf(KineticBlockEntity kbe) {
		Entry entry = entryOf(kbe);
		if (entry != null) {
			Rotor rotor = entry.rotors.get(kbe.getBlockPos());
			if (rotor != null && rotor.animation.memberFrame() != null) return rotor.animation.memberFrame();
		}
		if (entry == null || entry.renderFrame == null)
			return null;
		Float ratio = entry.ratios.get(kbe.getBlockPos());
		return ratio == null ? null : entry.renderFrame.scaled(ratio);
	}

	/** Output and adjoining shaft frames originate in the same rotor snapshot. */
	public static RotationFrame outputRotationOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		Rotor rotor = entry == null ? null : entry.rotors.get(be.getBlockPos());
		return rotor == null ? null : rotor.animation.outputFrame();
	}

	/** Only a snapshot for this entity may override its assembly-relative pose. */
	public static RotationFrame bearingRotationOf(MechanicalBearingBlockEntity bearing, int entityId) {
		Entry entry = entryOf(bearing);
		Rotor rotor = entry == null ? null : entry.rotors.get(bearing.getBlockPos());
		return rotor == null || rotor.bearing == null ? null : rotor.bearing.frame(entityId);
	}

	private static Entry entryOf(KineticBlockEntity kbe) {
		ResourceKey<Level> dimension = kbe.getLevel()
			.dimension();
		Map<BlockPos, Long> members = memberToNetwork.get(dimension);
		if (members == null)
			return null;
		Long networkId = members.get(kbe.getBlockPos());
		if (networkId == null)
			return null;
		if (!java.util.Objects.equals(networkId, kbe.network)) return null;
		Map<Long, Entry> levelNetworks = networks.get(dimension);
		Entry entry = levelNetworks == null ? null : levelNetworks.get(networkId);
		return entry == null || !entry.hasState ? null : entry;
	}

	public static Double energyOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		if (entry != null && Double.isFinite(entry.kineticEnergy)) return entry.kineticEnergy;
		return entry == null ? null : 0.5 * entry.balance.j() * entry.speed * entry.speed;
	}

	public static Double elasticEnergyOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		return entry == null || !Double.isFinite(entry.elasticEnergy) ? null : entry.elasticEnergy;
	}

	public static Double localElasticEnergyOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		Rotor rotor = entry == null ? null : entry.rotors.get(be.getBlockPos());
		return rotor == null || !Double.isFinite(rotor.elasticEnergy) ? null : rotor.elasticEnergy;
	}

	public static Float transmittedTorqueOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		Rotor rotor = entry == null ? null : entry.rotors.get(be.getBlockPos());
		return rotor == null ? null : rotor.torque;
	}

	public static LoadFactor.Reading loadFactorOf(KineticBlockEntity be) {
		Entry entry = entryOf(be);
		return entry == null ? LoadFactor.Reading.UNKNOWN : entry.loadFactor;
	}

}
