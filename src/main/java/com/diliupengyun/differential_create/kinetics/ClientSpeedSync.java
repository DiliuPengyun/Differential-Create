package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientSpeedSync {
	private static final Map<KineticNetwork, Sent> sent = new IdentityHashMap<>();
	private static final Set<KineticNetwork> dirty = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
	private static final Map<ServerPlayer, List<CustomPacketPayload>> queued = new IdentityHashMap<>();
	private static final class Sent {
		ServerLevel level;
		long id;
		NetworkStatePayload state;
		NetworkStructurePayload structure;
		Set<ChunkPos> chunks = new HashSet<>();
		Set<ServerPlayer> audience = new HashSet<>();
	}
	private ClientSpeedSync() {}

	public static void markStructureDirty(KineticNetwork network, Level level) {
		if (level instanceof ServerLevel) dirty.add(network);
	}

	public static void networkDissolved(Level level, long id) {
		for (Sent last : sent.values())
			if (last.level == level && last.id == id)
				for (ServerPlayer player : last.audience)
					queue(player, new NetworkStructurePayload(last.level.dimension().location(), id, List.of()));
	}

	public static void syncNetwork(KineticNetwork network, Level level, INetworkDynamics dynamics, NetworkPhysics.Balance b) {
		if (!(level instanceof ServerLevel server)) return;
		Sent last = sent.computeIfAbsent(network, $ -> new Sent());
		boolean structureChanged = dirty.remove(network) || last.structure == null;
		if (structureChanged) {
			last.level = server;
			last.id = network.id;
			last.chunks.clear();
			List<NetworkStructurePayload.Entry> entries = new ArrayList<>();
			for (var entry : dynamics.differentialcreate$getRatios().entrySet()) {
				var pos = entry.getKey().getBlockPos();
				entries.add(new NetworkStructurePayload.Entry(pos, entry.getValue()));
				last.chunks.add(new ChunkPos(pos));
			}
			last.structure = new NetworkStructurePayload(server.dimension().location(), network.id, entries);
		}
		boolean heartbeat = Math.floorMod(server.getGameTime() + network.id, 20) == 0;
		Set<ServerPlayer> newcomers = new HashSet<>();
		if (structureChanged || heartbeat) {
			Set<ServerPlayer> audience = new HashSet<>();
			for (ChunkPos chunk : last.chunks)
				audience.addAll(server.getChunkSource().chunkMap.getPlayers(chunk, false));
			newcomers.addAll(audience);
			newcomers.removeAll(last.audience);
			for (ServerPlayer departed : last.audience)
				if (!audience.contains(departed))
					queue(departed, new NetworkStructurePayload(server.dimension().location(), network.id, List.of()));
			last.audience = audience;
		}
		NetworkStatePayload current = new NetworkStatePayload(server.dimension().location(), network.id,
			(float) dynamics.differentialcreate$getSpeed(), (float) b.t0(), (float) b.k(), (float) b.l(), (float) b.j(), (float) b.fanDrag());
		boolean changed = last.state == null || Math.abs(current.speed() - last.state.speed()) > 1
			|| current.torque() != last.state.torque() || current.damping() != last.state.damping()
			|| current.load() != last.state.load() || current.inertia() != last.state.inertia() || current.drag() != last.state.drag();
		for (ServerPlayer player : last.audience) {
			if (structureChanged || newcomers.contains(player)) queue(player, last.structure);
			if (structureChanged || heartbeat || changed || newcomers.contains(player)) queue(player, current);
		}
		if (!last.audience.isEmpty()) {
			var rotors = new ArrayList<RotorStatePayload.Rotor>();
			double energy = ElasticKinetics.kineticEnergy(network);
			for (var be : dynamics.differentialcreate$getRatios().keySet()) {
				var member = ElasticKinetics.state(be);
				rotors.add(new RotorStatePayload.Rotor(be.getBlockPos(), (float) member.physicalSpeed,
					member.angle * 360, (float) member.transmittedTorque));
				// Keep every packet safely below the custom-payload size limit.
				if (rotors.size() == 2048) {
					var packet = new RotorStatePayload(server.dimension().location(), network.id, energy, List.copyOf(rotors));
					for (var player : last.audience) queue(player, packet);
					rotors.clear();
				}
			}
			if (!rotors.isEmpty()) {
				var packet = new RotorStatePayload(server.dimension().location(), network.id, energy, rotors);
				for (var player : last.audience) queue(player, packet);
			}
		}
		if (structureChanged || heartbeat || changed) last.state = current;
	}

	private static void queue(ServerPlayer player, CustomPacketPayload payload) {
		queued.computeIfAbsent(player, $ -> new ArrayList<>()).add(payload);
	}

	public static void prune(Set<KineticNetwork> live) {
		sent.keySet().retainAll(live);
		dirty.retainAll(live);
	}
	public static void flush() {
		queued.forEach((player, packets) -> {
			if (!player.hasDisconnected())
				packets.forEach(packet -> PacketDistributor.sendToPlayer(player, packet));
		});
		queued.clear();
	}
	public static void clear() { sent.clear(); dirty.clear(); queued.clear(); }
}
