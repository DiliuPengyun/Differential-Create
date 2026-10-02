package com.diliupengyun.differential_create.client;

/** Static alignment of a shaft, excluding the large cog's tooth-only offset. */
public final class ShaftModelOffset {
	private ShaftModelOffset() {}

	public static float of(boolean largeCog, boolean offsetPosition, float angleOffset) {
		float positionOffset = offsetPosition ? 22.5f : 0;
		// Create's separate large-cog shaft uses only the position alignment.
		// Other shaft models also retain the block entity's custom angle offset.
		return positionOffset + (largeCog ? 0 : angleOffset);
	}
}
