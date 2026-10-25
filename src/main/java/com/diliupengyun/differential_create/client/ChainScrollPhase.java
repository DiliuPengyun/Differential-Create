package com.diliupengyun.differential_create.client;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;

/**
 * Re-anchors the chain link texture to the member's accumulated angle.
 * Create scrolls the chain with absolute render time divided by
 * {@code 360 / |speed|}, which jumps whenever the finite-torque solver changes
 * speed. One texture repeat corresponds to {@code 360 / |speed|} ticks, i.e.
 * 0.3 shaft turns or 108 degrees.
 */
public final class ChainScrollPhase {
	/** One chain texture repeat per 108 degrees of shaft rotation. */
	public static final double DEGREES_PER_REPEAT = 108.0;

	private ChainScrollPhase() {}

	/**
	 * Signed texture phase in {@code [0, 1)}, or NaN before the member's angle
	 * is synced. The sign follows the shaft direction, matching package motion;
	 * the native renderer used {@code abs(speed)} and could not show a reversal.
	 */
	public static double phase(ChainConveyorBlockEntity be) {
		if (be == null || be.getLevel() == null) return Double.NaN;
		Double theta = ClientNetworkSpeeds.thetaOf(be);
		if (theta == null || !Double.isFinite(theta)) return Double.NaN;
		return phaseFromDegrees(theta);
	}

	public static double phaseFromDegrees(double thetaDegrees) {
		double phase = thetaDegrees / DEGREES_PER_REPEAT;
		phase -= Math.floor(phase);
		return phase;
	}
}
