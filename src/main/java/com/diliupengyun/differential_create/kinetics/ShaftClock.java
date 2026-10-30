package com.diliupengyun.differential_create.kinetics;

import java.util.function.DoublePredicate;
import java.util.function.DoubleFunction;
import java.util.ArrayList;
import java.util.List;

/** Locate the first machine boundary on a shared physical clock. */
public final class ShaftClock {
	private ShaftClock() {}
	public record Prefix<T>(double end, T state) {}
	public record Alignment<T>(double time, List<T> states) {}
	/** Reconcile shortened numerical trials before any state or world callback is committed.
	 * A new final implicit step can locate a boundary slightly before a longer prediction. */
	public static <T> Alignment<T> align(double floor, double deadline, double epsilon,
		List<DoubleFunction<Prefix<T>>> samplers) {
		for (int pass = 0; pass < 128; pass++) {
			double next = deadline;
			var states = new ArrayList<T>(samplers.size());
			for (var sampler : samplers) {
				var prefix = sampler.apply(deadline);
				if (!Double.isFinite(prefix.end) || prefix.end > deadline + epsilon || prefix.end < floor - epsilon)
					throw new IllegalStateException("An island prefix is outside the uncommitted interval");
				next = Math.min(next, prefix.end); states.add(prefix.state);
			}
			if (deadline - next <= epsilon) return new Alignment<>(deadline, List.copyOf(states));
			deadline = next;
		}
		throw new IllegalStateException("Island event times did not converge");
	}
	/** Reference helper for a predicate that is monotone on the supplied interval. */
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
