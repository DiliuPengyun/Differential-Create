package com.diliupengyun.differential_create.kinetics;

/** Redstone releases the clutch: zero is fully engaged, fifteen fully open. */
public final class ClutchControl {
	private ClutchControl() {}

	public static double engagement(int signal) {
		return (15 - Math.clamp(signal, 0, 15)) / 15.0;
	}

	public static double torqueLimit(int signal) {
		return PhysicsTuning.CLUTCH_TORQUE * engagement(signal);
	}
}
