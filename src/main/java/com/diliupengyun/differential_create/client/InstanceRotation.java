package com.diliupengyun.differential_create.client;

/** Keeps a model's static alignment separate from its network animation phase. */
public final class InstanceRotation {
	private RotationFrame frame;
	private Float modelOverride;
	private float defaultOffset;
	private boolean settingUp;

	public void beginSetup() {
		settingUp = true;
		frame = null;
	}

	public float finishSetup(RotationFrame frame) {
		this.frame = frame;
		settingUp = false;
		return offset();
	}

	/**
	 * Create sets a default inside setup, then some models supply their own
	 * alignment afterward. Remember that override across later setup calls:
	 * encased large cogs supply their shaft alignment only during construction.
	 */
	public float setModelOffset(float modelOffset) {
		if (settingUp) defaultOffset = modelOffset;
		else modelOverride = modelOffset;
		return offset();
	}

	private float offset() {
		float modelOffset = modelOverride == null ? defaultOffset : modelOverride;
		return frame == null ? modelOffset : frame.shaderOffset(modelOffset);
	}
}
