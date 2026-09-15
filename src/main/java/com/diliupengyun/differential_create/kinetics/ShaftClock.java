package com.diliupengyun.differential_create.kinetics;

import java.util.function.DoublePredicate;

/** Locate the first machine boundary on a shared physical clock. */
public final class ShaftClock {
	private ShaftClock() {}
	/** The predicate is true once any phase has received its complete travel budget. */
	public static double firstBoundary(double minutes, DoublePredicate reached) {
		if (!reached.test(minutes)) return minutes;
		double low = 0, high = minutes;
		for (int i = 0; i < 48; i++) {
			double mid = (low + high) / 2;
			if (reached.test(mid)) high = mid;
			else low = mid;
		}
		return high;
	}
}
