package com.diliupengyun.differential_create.kinetics;

/** Fractional shaft travel; integer machine counters consume only earned work. */
public final class WorkAccumulator {
	private double remainder;
	public int advance(double units) {
		if (!Double.isFinite(units) || units <= 0) return 0;
		remainder += units;
		int whole = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.floor(remainder)));
		remainder -= whole;
		return Math.max(0, whole);
	}
	public double remainder() { return remainder; }
	public void restore(double value) { remainder = Double.isFinite(value) ? value : 0; }
	public void borrowOne() { remainder -= 1; }
	public void reset() { remainder = 0; }
}
