package com.diliupengyun.differential_create.client;

/** One shared linear segment of the network angle, in Flywheel's render clock. */
public record RotationFrame(int tick, double angle, double degreesPerTick) {

	public double angleAt(double renderTick) {
		return angle + (renderTick - tick) * degreesPerTick;
	}

	public RotationFrame scaled(double ratio) {
		return new RotationFrame(tick, angle * ratio, degreesPerTick * ratio);
	}

	public boolean sameMotion(RotationFrame other) {
		return degreesPerTick == other.degreesPerTick && Math.abs(angleAt(other.tick) - other.angle) < 1e-8;
	}

	public float shaderSpeed() {
		return (float) (degreesPerTick * 20);
	}

	public float shaderOffset(float modelOffset) {
		// Match the shader's float seconds and speed, including their rounding.
		return (float) ((angle + modelOffset) % 360 - (double) (tick / 20f) * shaderSpeed());
	}
}
