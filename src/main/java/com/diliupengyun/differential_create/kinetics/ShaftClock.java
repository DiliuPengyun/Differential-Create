package com.diliupengyun.differential_create.kinetics;

import java.util.function.DoublePredicate;

/** Locate the first machine boundary on a shared physical clock. */
public final class ShaftClock {
	private ShaftClock() {}
	/** Legacy helper for a predicate that is monotone on the supplied interval. */
	public static double firstBoundary(double minutes, DoublePredicate reached) {
		return firstBoundary(0, minutes, reached);
	}
	/** Dense accepted motion is split at velocity zeros before using this helper. */
	public static double firstBoundary(double low, double high, DoublePredicate reached) {
		if (reached.test(low)) return low;
		if (!reached.test(high)) return high;
		for (int i = 0; i < 48; i++) {
			double mid = (low + high) / 2;
			if (reached.test(mid)) high = mid;
			else low = mid;
		}
		return high;
	}
}
