package com.diliupengyun.differential_create.kinetics;

import java.util.Map;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;

public final class NetworkPhysics {
	public static final double TICK_MINUTES = RotationMath.TICK_MINUTES;
	public record Balance(double t0, double k, double l, double j, double fanDrag) {}
	public record StepResult(double newSpeed, double equilibrium) {}
	private NetworkPhysics() {}

	public static Balance aggregate(KineticNetwork network, Map<KineticBlockEntity, Float> ratios) {
		NetworkData data = ((INetworkDynamics) network).differentialcreate$data();
		boolean full = ratios == ((INetworkDynamics) network).differentialcreate$getRatios();
		Balance base = full ? data.staticBalance : null;
		Balance result = aggregate(network, ratios, base);
		if (full) data.staticBalance = result;
		return result;
	}

	static Balance aggregate(KineticNetwork network, Map<KineticBlockEntity, Float> ratios, Balance base) {
		if (base == null) {
			double l = 0, j = 0, c = 0;
			for (var entry : ratios.entrySet()) {
				KineticBlockEntity be = entry.getKey();
				if (be.isRemoved()) continue;
				double r = entry.getValue(), impact = MachineActivity.impact(be);
				j += r * r * InertiaRegistry.getInertia(be);
				c += Math.pow(Math.abs(r), 3) * MachineActivity.quadraticDrag(be);
				if (!(be instanceof EncasedFanBlockEntity)) l += Math.abs(r) * impact;
			}
			base = new Balance(0, 0, l, j, c);
		}
		double torque = 0, damping = 0;
		for (KineticBlockEntity source : network.sources.keySet()) {
			Float r = ratios.get(source);
			if (source.isRemoved() || r == null) continue;
			SourcePhysics.Curve curve = SourcePhysics.curve(source);
			if (!curve.powerLimited()) {
				torque += r * curve.stallTorque();
				damping += r * r * curve.damping();
				continue;
			}
			// Local tangent for diagnostics/client fallback only. TorsionSolver evaluates the
			// actual nonlinear source at every substep; this slope is not internal friction.
			double speed = NetworkDynamics.preciseSpeed(source), slope = curve.tangentDamping(speed);
			torque += r * (curve.torque(speed) + slope * speed);
			damping += r * r * slope;
		}
		return new Balance(torque, damping, base.l, base.j, base.fanDrag);
	}

	public static StepResult integrate(double omega, Balance b) {
		double next = RotationMath.advance(omega, b.t0, b.k, b.l, b.j, b.fanDrag, TICK_MINUTES);
		double equilibrium = RotationMath.equilibrium(b.t0, b.k, b.l, b.fanDrag);
		return new StepResult(next, equilibrium);
	}
}
