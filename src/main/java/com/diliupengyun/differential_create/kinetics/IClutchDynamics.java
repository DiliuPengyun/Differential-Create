package com.diliupengyun.differential_create.kinetics;

/**
 * Per-clutch slip state attached to Create's {@code ClutchBlockEntity} by
 * mixin. Server authoritative; the two display values are synced to clients
 * through the block entity's sync packet for the goggle overlay.
 */
public interface IClutchDynamics {

	/** Consecutive ticks with |Δω| below the lock epsilon. */
	int differentialcreate$getLockTimer();

	void differentialcreate$setLockTimer(int ticks);

	/** Last measured speed difference across the clutch, RPM (signed). */
	float differentialcreate$getLastDeltaOmega();

	void differentialcreate$setLastDeltaOmega(float delta);

	/** Last coupling torque transmitted through the clutch, su/RPM (signed). */
	float differentialcreate$getLastCouplingTorque();

	void differentialcreate$setLastCouplingTorque(float torque);

	/** One-shot: boundary lifted while the lock merge propagates. */
	boolean differentialcreate$isMergePending();

	void differentialcreate$setMergePending(boolean pending);

}
