package com.diliupengyun.differential_create.kinetics;

/** A bounded friction impulse consistent with the network's tick integrator. */
public final class ClutchCoupling {
	private ClutchCoupling() {}

	/** Coefficients in the network reference frame; ratio maps to the clutch shaft. */
	public record Shaft(double speed, double torque, double damping, double load,
		double inertia, double drag, double ratio) {
		private double advance(double coupling, double minutes) {
			return ratio * RotationMath.advance(speed, torque + ratio * coupling,
				damping, load, inertia, drag, minutes);
		}
	}

	/**
	 * Positive torque brakes A and drives B. Both networks must have positive
	 * inertia. Match their end-of-tick shaft speeds if the pressure allows it;
	 * otherwise transmit the limit. Source torque and load act during this same
	 * tick, so a synchronized loaded clutch still supplies its holding torque.
	 */
	public static double torque(Shaft a, Shaft b, double limit) {
		return torque(a, b, limit, RotationMath.TICK_MINUTES);
	}

	public static double torque(Shaft a, Shaft b, double limit, double minutes) {
		if (limit <= 0) return 0;
		double freeSlip = slipAfter(a, b, 0, minutes);
		if (freeSlip == 0) return 0;
		double low = freeSlip > 0 ? 0 : -limit, high = freeSlip > 0 ? limit : 0;
		if (freeSlip > 0 && slipAfter(a, b, high, minutes) >= 0) return high;
		if (freeSlip < 0 && slipAfter(a, b, low, minutes) <= 0) return low;
		// End-of-tick slip decreases monotonically with transmitted torque,
		// including damping, quadratic drag, stopping and static-friction plateaus.
		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2;
			double slip = slipAfter(a, b, mid, minutes);
			if (Math.abs(slip) < 1e-9) return mid;
			if (slip > 0) low = mid;
			else high = mid;
		}
		return (low + high) / 2;
	}

	private static double slipAfter(Shaft a, Shaft b, double torque, double minutes) {
		return a.advance(-torque, minutes) - b.advance(torque, minutes);
	}
}
