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
			if (!level.tickRateManager().isFrozen()) MachiningClock.advance(level);
			live.addAll(MachiningClock.networks(level));
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
