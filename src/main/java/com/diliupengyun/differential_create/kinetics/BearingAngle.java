package com.diliupengyun.differential_create.kinetics;

/** Retains whole turns while following the native controller's wrapped pose. */
public final class BearingAngle {

	private boolean initialized;
	private double angle;

	public void align(double nativeAngle) {
		if (!initialized) {
			angle = nativeAngle;
			initialized = true;
		} else angle += Math.IEEEremainder(nativeAngle - angle, 360);
	}

	public void advance(double nativeAngle, double appliedTravel) {
		if (!initialized) align(nativeAngle - appliedTravel);
		angle += appliedTravel;
		align(nativeAngle);
	}

	public double withPending(double nativeAngle, double pendingTravel) {
		align(nativeAngle);
		return angle + pendingTravel;
	}
}
