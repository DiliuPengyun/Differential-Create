package com.diliupengyun.differential_create.kinetics;

/** Instrument range and redstone quantization, independent of the network's allowed speed. */
public final class SpeedGaugeScale {
	public static final int DEFAULT_INDEX = 1;
	public static final int MAX_INDEX = 7;
	public static final int STOPPED_COLOR = 0x888888;
	public static final int RUNNING_COLOR = 0x55CC77;
	public static final int OVER_RANGE_COLOR = 0xFFCC55;

	private SpeedGaugeScale() {}

	public static int range(int index) {
		return 64 << (2 * Math.clamp(index, 0, MAX_INDEX));
	}

	public static float fraction(double speed, int range) {
		return (float) Math.clamp(Math.abs(speed) / range, 0, 1);
	}

	public static int signal(double speed, int range) {
		return (int) Math.ceil(Math.clamp(15 * Math.abs(speed) / range, 0, 15));
	}

	public static int color(double speed, int range) {
		return speed == 0 ? STOPPED_COLOR : Math.abs(speed) > range ? OVER_RANGE_COLOR : RUNNING_COLOR;
	}
}
