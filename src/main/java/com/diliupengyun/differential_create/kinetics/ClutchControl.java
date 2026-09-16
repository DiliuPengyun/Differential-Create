package com.diliupengyun.differential_create.kinetics;

/** Redstone releases the clutch: zero is fully engaged, fifteen fully open. */
public final class ClutchControl {
	private ClutchControl() {}

	public static double engagement(int signal) {
		return (15 - Math.clamp(signal, 0, 15)) / 15.0;
	}

	public static double torqueLimit(int signal) {
		return torqueLimit(PhysicsTuning.CLUTCH_TORQUE, 1, signal);
	}

	public static double engagement(double manual, int signal) {
		return Math.clamp(manual, 0, 1) * engagement(signal);
	}

	public static double torqueLimit(double rating, double manual, int signal) {
		return rating * engagement(manual, signal);
	}
}
