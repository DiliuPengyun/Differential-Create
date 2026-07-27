package com.diliupengyun.differential_create.kinetics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.diliupengyun.differential_create.kinetics.NetworkPhysics.Balance;
import com.diliupengyun.differential_create.kinetics.NetworkPhysics.StepResult;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the physical speed of every kinetic network (DESIGN.md §4–5).
 * <p>
 * The members' speed fields always hold vanilla nominal speeds, so Create's
 * propagation, validation and chunk saving run on vanilla-consistent state.
 * Every post-tick the ticker integrates the network equation
 * (J_eff·dω/dt = T0 − L·sign(ω) − K·ω − C·ω·|ω|) and ships the results to
 * clients as per-network structure/state packets (see ClientSpeedSync);
 * machine logic and renderers read the physical value through the getSpeed()
 * hook on both sides.
 */
public final class NetworkSpeedTicker {

	/** Enter the settled state within this distance of the equilibrium (RPM)... */
	private static final float SETTLE_EPSILON_RPM = 0.5f;
	/** ...and leave it again beyond this distance. */
	private static final float SETTLE_HYSTERESIS_RPM = 1f;

	private static final Set<KineticNetwork> liveNetworks = new HashSet<>();

	private NetworkSpeedTicker() {}

	@SubscribeEvent
	public static void onPreTick(ServerTickEvent.Pre event) {
		MergePhysics.onPreTick();
		// Queued clutch lock merges run here: the speed fields always hold
		// nominal values, so the merging propagation sees consistent input
		ClutchPhysics.flushLocks();
	}

	@SubscribeEvent
	public static void onPostTick(ServerTickEvent.Post event) {
		// Reset the external-torque slots, let clutches accumulate their
		// coupling into both sides, then integrate every network
		forEachNetwork(NetworkSpeedTicker::resetExternalTorque);
		forEachNetwork(ClutchPhysics::accumulateCoupling);
		liveNetworks.clear();
		forEachNetwork(NetworkSpeedTicker::integrateAndSync);
		ClientSpeedSync.prune(liveNetworks);
		ClientSpeedSync.flush();
	}

	private static void resetExternalTorque(KineticNetwork network, Level level) {
		((INetworkDynamics) network).differentialcreate$setExternalTorque(0);
	}

	private static void forEachNetwork(NetworkConsumer consumer) {
		for (Map.Entry<LevelAccessor, Map<Long, KineticNetwork>> entry : TorquePropagatorAccessor
			.differentialcreate$getNetworks()
			.entrySet()) {
			if (!(entry.getKey() instanceof Level level) || level.isClientSide || level.tickRateManager()
				.isFrozen())
				continue;
			for (KineticNetwork network : entry.getValue()
				.values())
				consumer.accept(network, level);
		}
	}

	/**
	 * Post-tick phase: integrate the network equation one tick, then ship
	 * the structure/state sync for this network.
	 */
	private static void integrateAndSync(KineticNetwork network, Level level) {
		liveNetworks.add(network);
		INetworkDynamics dynamics = (INetworkDynamics) network;
		applyPendingSpeeds(network, dynamics);

		// Nominal network speed: fastest source (single-source networks:
		// simply |getGeneratedSpeed| of the source). Only used to derive
		// ratios for members the propagation never captured.
		float target = NetworkDynamics.computeTargetSpeed(network);
		dynamics.differentialcreate$setTargetSpeed(target);

		Map<KineticBlockEntity, Float> ratios = dynamics.differentialcreate$getRatios();
		ratios.keySet()
			.retainAll(network.members.keySet());

		// Members that joined without a captured propagation (the source
		// itself, or restored from NBT): their field holds the vanilla
		// nominal speed, so the ratio can be derived from it.
		for (KineticBlockEntity member : network.members.keySet()) {
			if (member.isRemoved() || ratios.containsKey(member))
				continue;
			ratios.put(member, NetworkDynamics.ratioFromNominal(member, dynamics, target));
			ClientSpeedSync.markStructureDirty(network, level);
		}

		Balance balance = NetworkPhysics.aggregate(network, ratios);
		// External torques (clutch coupling) fold into the standstill-torque
		// term of the network equation
		float externalTorque = dynamics.differentialcreate$getExternalTorque();
		if (externalTorque != 0)
			balance = new Balance(balance.t0() + externalTorque, balance.k(), balance.l(), balance.j(),
				balance.fanDrag());
		StepResult step = NetworkPhysics.integrate(dynamics.differentialcreate$getSpeed(), balance);
		float speed = step.newSpeed();
		// Hard guarantee: no NaN/Inf may ever enter the network state
		if (!Float.isFinite(speed)) {
			DifferentialCreate.LOGGER.warn("Non-finite speed in kinetic network {}, resetting to 0", network.id);
			speed = 0;
		}
		dynamics.differentialcreate$setSpeed(speed);

		// Debug: log the first time the network settles at each equilibrium
		if (!dynamics.differentialcreate$isSettled() && Math.abs(speed - step.equilibrium()) <= SETTLE_EPSILON_RPM) {
			dynamics.differentialcreate$setSettled(true);
			DifferentialCreate.LOGGER.debug(
				"Kinetic network {} balance: members={}, J_eff={}, T0={}, K={}, L={}, equilibrium {} RPM",
				network.id, network.members.size(), fmt(balance.j()), fmt(balance.t0()), fmt(balance.k()),
				fmt(balance.l()), fmt(step.equilibrium()));
			DifferentialCreate.LOGGER.debug("Kinetic network {} settled at {} RPM (equilibrium {} RPM)", network.id,
				fmt(speed), fmt(step.equilibrium()));
		} else if (Math.abs(speed - step.equilibrium()) > SETTLE_HYSTERESIS_RPM) {
			dynamics.differentialcreate$setSettled(false);
		}

		// Sync state for client extrapolation: equilibrium, linear rate K/J,
		// coasting rate L/J (sourceless), fan drag C/J
		double j = balance.j(), k = balance.k(), l = balance.l(), fanDrag = balance.fanDrag();
		float invTau = j > 0 && k > 0 ? (float) (k / j) : 0;
		float linearDecay = j > 0 && k == 0 ? (float) (l / j) : 0;
		float fanOverJ = j > 0 ? (float) (fanDrag / j) : 0;
		ClientSpeedSync.syncNetwork(network, level, dynamics, step.equilibrium(), invTau, linearDecay, fanOverJ);
	}

	/**
	 * Applies network speeds restored from NBT. Restoring the saved physical
	 * speed keeps loading a world from replaying a fake acceleration.
	 */
	private static void applyPendingSpeeds(KineticNetwork network, INetworkDynamics dynamics) {
		for (KineticBlockEntity member : network.members.keySet()) {
			IMemberDynamics memberDynamics = (IMemberDynamics) member;
			float pending = memberDynamics.differentialcreate$getPendingNetworkSpeed();
			if (Float.isNaN(pending))
				continue;
			dynamics.differentialcreate$setSpeed(pending);
			memberDynamics.differentialcreate$clearPendingNetworkSpeed();
		}
	}

	private static String fmt(double value) {
		return String.format("%.3f", value);
	}

	@FunctionalInterface
	private interface NetworkConsumer {
		void accept(KineticNetwork network, Level level);
	}

}
