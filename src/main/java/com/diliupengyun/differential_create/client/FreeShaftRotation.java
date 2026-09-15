package com.diliupengyun.differential_create.client;

/** A free port retains its phase while its server-supplied RPM changes. */
public final class FreeShaftRotation {
	private RotationFrame frame;

	public RotationFrame frame() {
		return frame;
	}

	public RotationFrame resolve(RotationFrame connected, float freeSpeed, int tick, double renderTick, boolean frozen) {
		if (connected != null) {
			frame = connected;
		} else {
			double rate = frozen ? 0 : freeSpeed * 0.3;
			if (frame == null) {
				frame = new RotationFrame(tick, 0, rate);
			} else if (frame.degreesPerTick() != rate) {
				// Anchor a rate change at the visible angle, including partial ticks.
				double angle = frame.angleAt(renderTick) - (renderTick - tick) * rate;
				frame = new RotationFrame(tick, angle, rate);
			}
		}
		return frame;
	}
}
