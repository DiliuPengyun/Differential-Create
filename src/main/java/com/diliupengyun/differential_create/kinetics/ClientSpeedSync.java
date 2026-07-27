package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the per-network speed sync (DESIGN.md §4.3). Instead of
 * per-member packets, the server sends:
 * <ul>
 * <li>a structure packet (network id + full ratio table) whenever a
 * network's topology or ratios change — marked dirty by the propagation
 * hooks and flushed after the ratio tables are complete for the tick;</li>
 * <li>a state packet (ω, ω*, K/J, L/J, C/J) at a staggered low rate or
 * when the state deviates materially (speed jump, parameter change).</li>
 * </ul>
 * The client extrapolates the exact curve between state packets.
 */
public final class ClientSpeedSync {

	/** Send a state packet at least this often, per network (ticks). */
	private static final int STATE_INTERVAL_TICKS = 20;
	/** Resend immediately when ω deviates from the last sent value by this much (RPM). */
	private static final float SPEED_RESEND_THRESHOLD = 1.0f;
	/** Resend when the equilibrium changed by this much (RPM). */
	private static final float EQUILIBRIUM_RESEND_THRESHOLD = 0.25f;
	/** Resend when a decay rate changed by more than this fraction. */
	private static final float RATE_RESEND_FRACTION = 0.1f;

	private ClientSpeedSync() {}

	/** Last state sent per network (identity-keyed; pruned against live networks). */
	private static final Map<KineticNetwork, SentState> sentStates = new IdentityHashMap<>();
	/** Networks whose ratio table changed and whose structure packet is pending. */
	private static final Map<KineticNetwork, ServerLevel> dirtyStructures = new IdentityHashMap<>();
	/** Queued packets per level, sent after the tick's integration pass. */
	private static final Map<ServerLevel, List<CustomPacketPayload>> queued = new IdentityHashMap<>();

	private record SentState(float speed, float equilibrium, float invTau, float linearDecay, float fanOverJ) {}

	/** Marks a network's ratio table as changed (propagation/membership events). */
	public static void markStructureDirty(KineticNetwork network, Level level) {
		if (level instanceof ServerLevel serverLevel)
			dirtyStructures.put(network, serverLevel);
	}

	/** Called when a network is removed from Create's registry: dissolves it on clients. */
	public static void networkDissolved(Level level, long networkId) {
		if (level instanceof ServerLevel serverLevel)
			queued.computeIfAbsent(serverLevel, $ -> new ArrayList<>())
				.add(new NetworkStructurePayload(serverLevel.dimension().location(), networkId, List.of()));
	}

	/**
	 * Called from the ticker's integration pass, after the ratio tables are
	 * complete for the tick: flushes the pending structure packet (if any)
	 * and decides whether a state packet is due.
	 */
	public static void syncNetwork(KineticNetwork network, Level level, INetworkDynamics dynamics, float equilibrium,
		float invTau, float linearDecay, float fanOverJ) {
		if (!(level instanceof ServerLevel serverLevel))
			return;

		if (dirtyStructures.remove(network) != null) {
			List<NetworkStructurePayload.Entry> entries = new ArrayList<>();
			for (Map.Entry<KineticBlockEntity, Float> member : dynamics.differentialcreate$getRatios()
				.entrySet())
				entries.add(new NetworkStructurePayload.Entry(member.getKey()
					.getBlockPos(), member.getValue()));
			queued.computeIfAbsent(serverLevel, $ -> new ArrayList<>())
				.add(new NetworkStructurePayload(serverLevel.dimension().location(),
					network.id == null ? 0 : network.id, entries));
		}

		float speed = dynamics.differentialcreate$getSpeed();
		SentState last = sentStates.get(network);
		boolean first = last == null;
		boolean heartbeat = network.id != null && (serverLevel.getGameTime() + network.id) % STATE_INTERVAL_TICKS == 0;
		boolean speedJump = first || Math.abs(speed - last.speed) > SPEED_RESEND_THRESHOLD;
		boolean paramChange = first || Math.abs(equilibrium - last.equilibrium) > EQUILIBRIUM_RESEND_THRESHOLD
			|| Math.abs(invTau - last.invTau) > RATE_RESEND_FRACTION * Math.max(0.01f, Math.abs(last.invTau))
			|| Math.abs(linearDecay - last.linearDecay) > RATE_RESEND_FRACTION
				* Math.max(0.001f, Math.abs(last.linearDecay))
			|| Math.abs(fanOverJ - last.fanOverJ) > RATE_RESEND_FRACTION
				* Math.max(0.001f, Math.abs(last.fanOverJ));
		if (first || heartbeat || speedJump || paramChange) {
			sentStates.put(network, new SentState(speed, equilibrium, invTau, linearDecay, fanOverJ));
			queued.computeIfAbsent(serverLevel, $ -> new ArrayList<>())
				.add(new NetworkStatePayload(serverLevel.dimension().location(), network.id == null ? 0 : network.id,
					speed, equilibrium, invTau, linearDecay, fanOverJ));
		}
	}

	/** Prunes bookkeeping of networks that no longer exist. Called once per tick. */
	public static void prune(Set<KineticNetwork> liveNetworks) {
		sentStates.keySet()
			.retainAll(liveNetworks);
		dirtyStructures.keySet()
			.retainAll(liveNetworks);
	}

	/** Sends all queued packets. Called once per server tick after integration. */
	public static void flush() {
		queued.forEach((level, packets) -> packets.forEach(
			payload -> PacketDistributor.sendToPlayersInDimension(level, payload)));
		queued.clear();
	}

}
