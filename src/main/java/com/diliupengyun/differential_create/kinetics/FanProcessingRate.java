package com.diliupengyun.differential_create.kinetics;

/** Effective airflow: reference throughput at 64 RPM, diminishing returns toward 4x. */
public final class FanProcessingRate {
	private FanProcessingRate() {}
	public static double at(double rpm) {
		double speed = Math.abs(rpm);
		if (!Double.isFinite(speed) || speed == 0) return 0;
		return 4 / (1 + 3 * PhysicsTuning.FAN_ANCHOR_RPM / speed);
	}
}
