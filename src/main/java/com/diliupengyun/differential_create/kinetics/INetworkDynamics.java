package com.diliupengyun.differential_create.kinetics;

import java.util.Map;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Continuous-rotation state attached to Create's {@code KineticNetwork} by
 * mixin. Members retain independent speeds; a network also publishes a
 * reference summary and geometric ratios for Create's connection context.
 */
public interface INetworkDynamics {
	NetworkData differentialcreate$data();

	/**
	 * Current network speed (RPM), integrated every server tick. Corresponds to
	 * the speed of a ratio-1 member (the source shaft in a single-source
	 * network).
	 */
	double differentialcreate$getSpeed();

	void differentialcreate$setSpeed(double speed);

	/**
	 * Geometric member-to-reference ratios, signed and independent of speed.
	 * Ratios remain defined at standstill and in networks without sources.
	 */
	Map<KineticBlockEntity, Float> differentialcreate$getRatios();

	/**
	 * True once the network speed has entered ±0.5 RPM of its equilibrium;
	 * reset when it drifts further than 1 RPM away. Used to log the first
	 * settling of each balance.
	 */
	boolean differentialcreate$isSettled();

	void differentialcreate$setSettled(boolean settled);

	/**
	 * External torque (su/RPM, referred to the base shaft) applied to the
	 * network this tick — currently the clutch coupling. Reset and
	 * re-accumulated every tick before integration; folded into the T0 term
	 * of the network equation.
	 */
	double differentialcreate$getExternalTorque();

	void differentialcreate$setExternalTorque(double torque);

	/**
	 * Nominal reference basis, fixed at 16 in newly built networks and saved
	 * alongside each member's ratio for restoring the same reference basis.
	 */
	float differentialcreate$getLastNonZeroTarget();

	void differentialcreate$setLastNonZeroTarget(float target);

}
