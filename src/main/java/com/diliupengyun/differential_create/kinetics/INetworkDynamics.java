package com.diliupengyun.differential_create.kinetics;

import java.util.Map;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Continuous-rotation state attached to Create's {@code KineticNetwork} by
 * mixin. A network owns a single speed state variable; each member's speed is
 * derived as ratio × network speed.
 */
public interface INetworkDynamics {

	/**
	 * Current network speed (RPM), integrated every server tick. Corresponds to
	 * the speed of a ratio-1 member (the source shaft in a single-source
	 * network).
	 */
	float differentialcreate$getSpeed();

	void differentialcreate$setSpeed(float speed);

	/**
	 * Nominal network speed (RPM) the current speed approaches exponentially:
	 * the fastest |getGeneratedSpeed| among the network's sources. Always
	 * positive; member directions live in the ratios.
	 */
	float differentialcreate$getTargetSpeed();

	void differentialcreate$setTargetSpeed(float targetSpeed);

	/**
	 * Per-member speed ratios r_i = member target speed / network target speed.
	 * Signed (counter-rotating members are negative), 0 when the network target
	 * is 0.
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
	float differentialcreate$getExternalTorque();

	void differentialcreate$setExternalTorque(float torque);

	/**
	 * Last non-zero nominal network speed (RPM). Used as the ratio
	 * denominator for blocks joining a sourceless (coasting) network, whose
	 * live target speed is 0.
	 */
	float differentialcreate$getLastNonZeroTarget();

	void differentialcreate$setLastNonZeroTarget(float target);

}
