package com.diliupengyun.differential_create.kinetics;

import java.lang.ref.WeakReference;
import java.util.HashSet;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;

import net.minecraft.core.BlockPos;

/** Steam links must not load chunks or treat unloaded block-entity caches as live supply. */
public final class SteamEnginePhysics {
	private SteamEnginePhysics() {}

	public static FluidTankBlockEntity tank(SteamEngineBlockEntity engine) {
		var level = engine.getLevel();
		if (((com.diliupengyun.differential_create.trains.SteamHoseLinks.EngineLink) engine).differentialcreate$hose() != null) {
			var remote = engine.isRemoved() ? null : com.diliupengyun.differential_create.trains.SteamHoseLinks.tank(engine);
			if (engine.source.get() != remote) engine.source = new WeakReference<>(remote);
			return remote;
		}
		FluidTankBlockEntity attached = null;
		if (level != null && !engine.isRemoved() && engine.getBlockState().getBlock() instanceof SteamEngineBlock) {
			BlockPos pos = engine.getBlockPos().relative(SteamEngineBlock.getFacing(engine.getBlockState()).getOpposite());
			if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank && !tank.isRemoved())
				attached = tank;
		}
		if (engine.source.get() != attached) engine.source = new WeakReference<>(attached);
		if (attached == null || !level.isLoaded(attached.getController())) return null;
		return level.getBlockEntity(attached.getController()) instanceof FluidTankBlockEntity controller
			&& !controller.isRemoved() && controller.isController() ? controller : null;
	}

	public static PoweredShaftBlockEntity shaft(SteamEngineBlockEntity engine) {
		var level = engine.getLevel();
		if (level != null && level.isClientSide && engine.isVirtual())
			return com.diliupengyun.differential_create.client.ClientTrainKinetics.renderShaft(engine);
		PoweredShaftBlockEntity target = null;
		// Create calls getShaft() during remove(), after marking the engine removed.
		if (level != null && engine.getBlockState().getBlock() instanceof SteamEngineBlock) {
			BlockPos pos = SteamEngineBlock.getShaftPos(engine.getBlockState(), engine.getBlockPos());
			if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PoweredShaftBlockEntity shaft
				&& !shaft.isRemoved() && shaft.canBePoweredBy(engine.getBlockPos())
				&& SteamEngineBlock.isShaftValid(engine.getBlockState(), shaft.getBlockState())) target = shaft;
		}
		if (engine.target.get() != target) engine.target = new WeakReference<>(target);
		return target;
	}

	/** Follow an upstream drive command even while stalled or reversing; otherwise follow real coasting. */
	public static float drivingSpeed(PoweredShaftBlockEntity shaft) {
		var level = shaft.getLevel();
		// Direction changes are server-authoritative; clients do not have the server's source membership.
		if (level == null || level.isClientSide) return 0;
		if (shaft.hasNetwork()) {
			var ratios = ((INetworkDynamics) shaft.getOrCreateNetwork()).differentialcreate$getRatios();
			Float localRatio = ratios.get(shaft);
			var seen = new HashSet<BlockPos>();
			seen.add(shaft.getBlockPos());
			KineticBlockEntity member = shaft;
			while (member.hasSource() && seen.add(member.source) && level.isLoaded(member.source)) {
				if (!(level.getBlockEntity(member.source) instanceof KineticBlockEntity upstream) || upstream.isRemoved()) break;
				Float upstreamRatio = ratios.get(upstream);
				if (upstreamRatio == null) break;
				float command = upstream.getGeneratedSpeed();
				if (command != 0 && localRatio != null && upstreamRatio != 0)
					return command * localRatio / upstreamRatio;
				member = upstream;
			}
		}
		return shaft.getSpeed();
	}
}
