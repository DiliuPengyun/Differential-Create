package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.world.level.Level;

/** All shafts in a level advance together, stopping at each recipe/stroke boundary. */
public final class MachiningClock {
	private MachiningClock() {}
	private record Phase(MachineWork.Binding binding, double remaining) {}
	private record Step(RotationMath.Motion motion, NetworkPhysics.Balance balance) {}

	public static List<KineticNetwork> networks(Level level) {
		var registry = TorquePropagatorAccessor.differentialcreate$getNetworks().get(level);
		return registry == null ? List.of() : new ArrayList<>(registry.values());
	}

	public static void advance(Level level) {
		double remaining = RotationMath.TICK_MINUTES;
		boolean first = true;
		int transitions = 0;
		while (remaining > 0) {
			NetworkTopology.flush();
			var networks = networks(level);
			var bindings = MachineWork.collect(networks);
			var phases = new ArrayList<Phase>();
			boolean immediate = false;
			for (var binding : bindings) {
				double budget = binding.work().differentialcreate$remainingTravel();
				if (budget <= MachineWork.EPSILON) {
					binding.work().differentialcreate$advanceTravel(0);
					immediate = true;
				} else if (Double.isFinite(budget)) phases.add(new Phase(binding, budget));
			}
			if (++transitions > 16384) throw new IllegalStateException("Machine phases did not converge in " + level.dimension().location());
			if (immediate) continue;
			for (var network : networks) {
				INetworkDynamics dynamics = (INetworkDynamics) network;
				NetworkData data = dynamics.differentialcreate$data();
				MachineActivity.refresh(network);
				var next = NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios());
				if (data.balance != null && next.j() > data.balance.j())
					dynamics.differentialcreate$setSpeed(dynamics.differentialcreate$getSpeed() * data.balance.j() / next.j());
				data.balance = next;
				dynamics.differentialcreate$setExternalTorque(0);
			}
			// Lock hysteresis and visual effects count real ticks, never trial integrations or phase events.
			if (first) {
				for (var network : networks) ClutchPhysics.accumulateCoupling(network, level);
				first = false;
			}
			double duration = phases.isEmpty() ? remaining : ShaftClock.firstBoundary(remaining, minutes -> {
				var trial = sample(networks, minutes);
				for (var phase : phases)
					if (travel(trial, phase.binding()) >= phase.remaining()) return true;
				return false;
			});
			var steps = sample(networks, duration);
			// Commit every shaft before callbacks can change inventories or topology.
			steps.forEach((dynamics, step) -> {
				double speed = step.motion().speed();
				if (!Double.isFinite(speed)) throw new IllegalStateException("Non-finite kinetic speed");
				dynamics.differentialcreate$setSpeed(speed);
				dynamics.differentialcreate$data().initialized = true;
			});
			// Capture motion before an earlier callback can reconnect a later machine.
			double[] advances = phases.stream().mapToDouble(phase -> Math.min(phase.remaining(), travel(steps, phase.binding()))).toArray();
			MachineWork.batch(() -> {
				for (int i = 0; i < phases.size(); i++)
					if (advances[i] > 0 && !phases.get(i).binding().shaft().isRemoved())
						phases.get(i).binding().work().differentialcreate$advanceTravel(advances[i]);
			});
			remaining = Math.max(0, remaining - duration);
		}
		for (var network : networks(level)) {
			INetworkDynamics dynamics = (INetworkDynamics) network;
			MachineActivity.refresh(network);
			var base = NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios());
			dynamics.differentialcreate$data().balance = base;
			dynamics.differentialcreate$setTargetSpeed(NetworkDynamics.computeTargetSpeed(network));
			ClientSpeedSync.syncNetwork(network, level, dynamics, effective(dynamics));
		}
	}

	private static double travel(Map<INetworkDynamics, Step> steps, MachineWork.Binding binding) {
		Step step = steps.get(binding.dynamics());
		return step == null ? 0 : step.motion().travel() * binding.ratio() / RotationMath.TICK_MINUTES;
	}
	private static Map<INetworkDynamics, Step> sample(List<KineticNetwork> networks, double minutes) {
		for (var network : networks) ((INetworkDynamics) network).differentialcreate$setExternalTorque(0);
		for (var network : networks) ClutchPhysics.sampleCoupling(network, minutes);
		var result = new LinkedHashMap<INetworkDynamics, Step>();
		for (var network : networks) {
			INetworkDynamics dynamics = (INetworkDynamics) network;
			var balance = effective(dynamics);
			var motion = dynamics.differentialcreate$data().jammed ? new RotationMath.Motion(0, 0, 0)
				: RotationMath.motion(dynamics.differentialcreate$getSpeed(), balance.t0(), balance.k(), balance.l(), balance.j(), balance.fanDrag(), minutes);
			result.put(dynamics, new Step(motion, balance));
		}
		return result;
	}
	private static NetworkPhysics.Balance effective(INetworkDynamics dynamics) {
		var base = dynamics.differentialcreate$data().balance;
		return dynamics.differentialcreate$data().jammed ? new NetworkPhysics.Balance(0, 0, 0, base.j(), 0)
			: new NetworkPhysics.Balance(base.t0() + dynamics.differentialcreate$getExternalTorque(), base.k(), base.l(), base.j(), base.fanDrag());
	}
}
