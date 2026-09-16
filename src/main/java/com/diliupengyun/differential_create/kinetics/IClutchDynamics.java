package com.diliupengyun.differential_create.kinetics;

/**
 * Per-clutch slip state attached to Create's {@code ClutchBlockEntity} by
 * mixin. Server authoritative; control and display values are synced to clients
 * through the block entity's sync packet for the goggle overlay.
 */
public interface IClutchDynamics {

	/** Last sampled redstone level, or -1 before the first server update. */
	int differentialcreate$getRedstoneSignal();

	void differentialcreate$setRedstoneSignal(int signal);

	/** Consecutive ticks with |Δω| below the lock epsilon. */
	int differentialcreate$getLockTimer();

	void differentialcreate$setLockTimer(int ticks);

	/** Last measured speed difference across the clutch, RPM (signed). */
	float differentialcreate$getLastDeltaOmega();

	void differentialcreate$setLastDeltaOmega(float delta);

	/** Last coupling torque transmitted through the clutch, su/RPM (signed). */
	float differentialcreate$getLastCouplingTorque();

	void differentialcreate$setLastCouplingTorque(float torque);

	/** Last output RPM, retained when the output has no neighbouring shaft. */
	float differentialcreate$getFreeOutputSpeed();

	void differentialcreate$setFreeOutputSpeed(float speed);

	/** Last control-pass ceiling, not persisted; identifies actual engage-to-open transitions. */
	double differentialcreate$getPreviousTorqueLimit();

	void differentialcreate$setPreviousTorqueLimit(double limit);

	/** Persistent rigid connection, released by redstone or excess load torque. */
	boolean differentialcreate$isLocked();

	void differentialcreate$setLocked(boolean pending);

}
