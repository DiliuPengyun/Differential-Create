package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class NetworkSpeedTicker {
	private NetworkSpeedTicker() {}

	@SubscribeEvent
	public static void onPreTick(ServerTickEvent.Pre event) { NetworkTopology.flush(); }

	@SubscribeEvent
	public static void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
		if (event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)
			for (var be : chunk.getBlockEntities().values())
				if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic)
					NetworkTopology.mark(kinetic);
	}

	@SubscribeEvent
	public static void onPostTick(ServerTickEvent.Post event) {
		NetworkTopology.flush();
		Set<KineticNetwork> live = new HashSet<>();
		for (var entry : new ArrayList<>(TorquePropagatorAccessor.differentialcreate$getNetworks().entrySet())) {
			if (!(entry.getKey() instanceof Level level) || level.isClientSide) continue;
			var networks = new ArrayList<>(entry.getValue().values());
			live.addAll(networks);
			if (level.tickRateManager().isFrozen()) continue;
			for (KineticNetwork network : networks) {
				INetworkDynamics dynamics = (INetworkDynamics) network;
				dynamics.differentialcreate$setExternalTorque(0);
				NetworkData data = dynamics.differentialcreate$data();
				MachineActivity.refresh(network);
				NetworkPhysics.Balance next = NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios());
				if (data.balance != null && next.j() > data.balance.j())
					dynamics.differentialcreate$setSpeed((float) (dynamics.differentialcreate$getSpeed() * data.balance.j() / next.j()));
				data.balance = next;
			}
			for (KineticNetwork network : networks)
				ClutchPhysics.accumulateCoupling(network, level);
			for (KineticNetwork network : networks) {
				INetworkDynamics dynamics = (INetworkDynamics) network;
				NetworkPhysics.Balance base = dynamics.differentialcreate$data().balance;
				NetworkPhysics.Balance balance = new NetworkPhysics.Balance(
					base.t0() + dynamics.differentialcreate$getExternalTorque(), base.k(), base.l(), base.j(), base.fanDrag());
				if (dynamics.differentialcreate$data().jammed)
					balance = new NetworkPhysics.Balance(0, 0, 0, base.j(), 0);
				float speed = dynamics.differentialcreate$data().jammed ? 0
					: NetworkPhysics.integrate(dynamics.differentialcreate$getSpeed(), balance).newSpeed();
				if (!Float.isFinite(speed)) throw new IllegalStateException("Non-finite kinetic speed in network " + network.id);
				dynamics.differentialcreate$setSpeed(speed);
				dynamics.differentialcreate$data().initialized = true;
				dynamics.differentialcreate$setTargetSpeed(NetworkDynamics.computeTargetSpeed(network));
				ClientSpeedSync.syncNetwork(network, level, dynamics, balance);
			}
		}
		ClientSpeedSync.prune(live);
		ClientSpeedSync.flush();
	}

	@SubscribeEvent
	public static void onStop(ServerStoppedEvent event) {
		NetworkTopology.clear();
		ClientSpeedSync.clear();
	}
}
