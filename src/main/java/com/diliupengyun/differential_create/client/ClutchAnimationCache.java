package com.diliupengyun.differential_create.client;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Flywheel workers and client ticks share phase state, never a live map iterator. */
public final class ClutchAnimationCache<K> {
	private final Map<K, FreeShaftRotation> outputs = new WeakHashMap<>();

	public synchronized FreeShaftRotation get(K clutch) {
		return outputs.computeIfAbsent(clutch, $ -> new FreeShaftRotation());
	}

	/** Hold live keys for this tick, then release the cache lock before visual callbacks. */
	public synchronized Map<K, FreeShaftRotation> snapshot(Predicate<? super K> removed) {
		outputs.keySet().removeIf(removed);
		return new HashMap<>(outputs);
	}
}
