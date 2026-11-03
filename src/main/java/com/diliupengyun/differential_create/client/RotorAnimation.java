package com.diliupengyun.differential_create.client;

/** Plays signed, unwrapped server angle samples without extrapolating endpoint RPM. */
public final class RotorAnimation {

	private boolean initialized;
	private double angle, target;
	private RotationFrame frame;
	private boolean stepped;

	public void receive(double serverAngle) {
		receive(serverAngle, false);
	}

	/** A requested frozen-world step presents its complete confirmed endpoint. */
	public void receive(double serverAngle, boolean stepped) {
		target = serverAngle;
		this.stepped |= stepped;
		if (!initialized) {
			angle = serverAngle;
			initialized = true;
		}
	}

	public RotationFrame advance(int tick, double renderTick, boolean frozen) {
		if (!initialized) return null;
		if (stepped) {
			stepped = false;
			angle = target;
			return frame = new RotationFrame(tick, angle, 0);
		}
		if (frozen) {
			if (frame != null && frame.degreesPerTick() != 0)
				angle = frame.angleAt(Math.clamp(renderTick, frame.tick(), frame.tick() + 1.0));
			frame = new RotationFrame(tick, angle, 0);
		} else {
			// Several packets in one client tick collapse to the latest endpoint.
			// Without a fresh sample, target == angle and the completed segment holds.
			frame = new RotationFrame(tick, angle, target - angle);
			angle = target;
		}
		return frame;
	}

	public RotationFrame frame() { return frame; }
}
