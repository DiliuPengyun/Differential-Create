package com.diliupengyun.differential_create.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.diliupengyun.differential_create.kinetics.NetworkPhysics;
import com.diliupengyun.differential_create.kinetics.NetworkStatePayload;
import com.diliupengyun.differential_create.kinetics.NetworkStructurePayload;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side mirror of the server's kinetic network speeds. Structure
 * packets install the member→network mapping and ratios; state packets
 * deliver (ω, ω*, K/J, L/J, C/J) which the client advances every tick with
 * the same exact steps the server uses. Member speeds are then r_i × ω of
 * their network, so all members of a network are strictly proportional —
 * no per-member jitter or angle drift between connected blocks.
 * <p>
 * Only runs on the client; lookups degrade gracefully to "no data" so
 * block entities never synced yet fall back to their vanilla field.
 */
public final class ClientNetworkSpeeds {

	private static final Map<ResourceKey<Level>, Map<Long, Entry>> networks = new HashMap<>();
	private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> memberToNetwork = new HashMap<>();

	private ClientNetworkSpeeds() {}

	/** Extrapolated state of one network: shared ω plus the ratio table. */
	private static final class Entry {
		final Map<BlockPos, Float> ratios = new HashMap<>();
		float speed;
		float equilibrium;
		float invTau;
		float linearDecay;
		float fanOverJ;
		/**
		 * Accumulated rotation angle of the network, degrees. Advanced with
		 * the same trapezoid rule as the speed so member angles r_i × θ stay
		 * continuous through ramps (the shader animates offset + t × speed,
		 * which is discontinuous for a varying speed — the visuals re-anchor
		 * their offset to this θ instead).
		 */
		float theta;

		/** Advances ω by one client tick with the server's exact steps. */
		void advance() {
			float before = speed;
			if (invTau > 0)
				speed = (float) (equilibrium + (speed - equilibrium) * Math.exp(-invTau * NetworkPhysics.TICK_MINUTES));
			else if (linearDecay > 0) {
				// Sourceless network: dry friction decelerates linearly to a stop
				double decel = linearDecay * NetworkPhysics.TICK_MINUTES;
				speed = Math.abs(speed) <= decel ? 0 : (float) (speed - Math.signum(speed) * decel);
			}
			if (fanOverJ > 0)
				speed = (float) (speed / (1 + fanOverJ * NetworkPhysics.TICK_MINUTES * Math.abs(speed)));
			// Trapezoid: θ += (ω_before + ω_after)/2 × 0.3 deg per RPM-tick
			theta += (before + speed) * 0.5f * 0.3f;
			// Stiction/sign-clamp nuances of the server integrator are skipped
			// here: within a 1-second sync window they never matter visually
		}
	}

	public static void applyStructure(ResourceKey<Level> dimension, long networkId,
		List<NetworkStructurePayload.Entry> entries) {
		Map<BlockPos, Long> members = memberToNetwork.get(dimension);
		if (members != null)
			members.values()
				.removeIf(id -> id == networkId);
		if (entries.isEmpty()) {
			// Network dissolved
			Map<Long, Entry> levelNetworks = networks.get(dimension);
			if (levelNetworks != null)
				levelNetworks.remove(networkId);
			return;
		}
		Entry entry = networks.computeIfAbsent(dimension, $ -> new HashMap<>())
			.computeIfAbsent(networkId, $ -> new Entry());
		entry.ratios.clear();
		Map<BlockPos, Long> memberMap = memberToNetwork.computeIfAbsent(dimension, $ -> new HashMap<>());
		for (NetworkStructurePayload.Entry structureEntry : entries) {
			entry.ratios.put(structureEntry.pos(), structureEntry.ratio());
			memberMap.put(structureEntry.pos(), networkId);
		}
	}

	public static void applyState(ResourceKey<Level> dimension, NetworkStatePayload payload) {
		Entry entry = networks.computeIfAbsent(dimension, $ -> new HashMap<>())
			.computeIfAbsent(payload.networkId(), $ -> new Entry());
		entry.speed = payload.speed();
		entry.equilibrium = payload.equilibrium();
		entry.invTau = payload.invTau();
		entry.linearDecay = payload.linearDecay();
		entry.fanOverJ = payload.fanOverJ();
	}

	/** Applied every client tick: advances all extrapolations one tick. */
	public static void onClientTick() {
		Minecraft minecraft = Minecraft.getInstance();
		Level level = minecraft.level;
		if (level == null) {
			if (!networks.isEmpty()) {
				networks.clear();
				memberToNetwork.clear();
			}
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
		if (levelNetworks != null)
			levelNetworks.values()
				.forEach(Entry::advance);
	}

	/**
	 * Physical speed of a member on the client (ratio × extrapolated network
	 * ω), or null when nothing was synced for this block entity yet — the
	 * caller falls back to the vanilla field then.
	 */
	public static Float speedOf(KineticBlockEntity kbe) {
		Entry entry = entryOf(kbe);
		if (entry == null)
			return null;
		Float ratio = entry.ratios.get(kbe.getBlockPos());
		return ratio == null ? null : ratio * entry.speed;
	}

	/**
	 * Accumulated rotation angle of a member (ratio × network θ, degrees),
	 * or null when unsynced. Drives the continuous-animation offset
	 * compensation in the render hooks.
	 */
	public static Float thetaOf(KineticBlockEntity kbe) {
		Entry entry = entryOf(kbe);
		if (entry == null)
			return null;
		Float ratio = entry.ratios.get(kbe.getBlockPos());
		return ratio == null ? null : ratio * entry.theta;
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
		Map<Long, Entry> levelNetworks = networks.get(dimension);
		return levelNetworks == null ? null : levelNetworks.get(networkId);
	}

}
