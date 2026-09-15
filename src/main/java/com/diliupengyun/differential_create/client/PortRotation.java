package com.diliupengyun.differential_create.client;

/** Keeps only a port's direction and a disconnected half-shaft's stationary angle. */
public final class PortRotation {
	private double ratio = 1;
	private boolean detached;
	private RotationFrame lastFrame;

	public RotationFrame resolve(RotationFrame member, float physicalSpeed, float portSpeed, double renderTick) {
		if (physicalSpeed != 0) {
			if (portSpeed != 0) {
				ratio = portSpeed / physicalSpeed;
				detached = false;
			} else if (!detached) {
				double angle = lastFrame == null ? 0 : lastFrame.angleAt(renderTick);
				lastFrame = new RotationFrame(member.tick(), angle, 0);
				detached = true;
			}
		}
		// A stopped network retains the port's sign. Moving ports always use
		// the network phase, regardless of this instance's creation/update time.
		if (!detached)
			lastFrame = member.scaled(ratio);
		return lastFrame;
	}
}
