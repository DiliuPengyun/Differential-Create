package com.diliupengyun.differential_create.kinetics;

import java.util.Map;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;

public final class NetworkPhysics {
	public static final double TICK_MINUTES = RotationMath.TICK_MINUTES;
	public record Balance(double t0, double k, double l, double j, double fanDrag) {}
	public record StepResult(float newSpeed, float equilibrium) {}
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
				if (be instanceof EncasedFanBlockEntity)
					c += Math.pow(Math.abs(r), 3) * impact / Math.pow(PhysicsTuning.FAN_ANCHOR_RPM, 2);
				else
					l += Math.abs(r) * impact;
			}
			base = new Balance(0, 0, l, j, c);
		}
		double torque = 0, damping = 0;
		for (KineticBlockEntity source : network.sources.keySet()) {
			Float r = ratios.get(source);
			if (source.isRemoved() || r == null) continue;
			SourcePhysics.Curve curve = SourcePhysics.curve(source);
			torque += r * curve.stallTorque();
			damping += r * r * curve.damping();
		}
		return new Balance(torque, damping, base.l, base.j, base.fanDrag);
	}

	public static StepResult integrate(float omega, Balance b) {
		double next = RotationMath.advance(omega, b.t0, b.k, b.l, b.j, b.fanDrag, TICK_MINUTES);
		double equilibrium = RotationMath.equilibrium(b.t0, b.k, b.l, b.fanDrag);
		return new StepResult((float) next, (float) equilibrium);
	}
}
