package com.diliupengyun.differential_create.client;

/** Plays one confirmed pose per tick, retaining one sample to absorb arrival jitter. */
public final class BearingAnimation {

	private static final int CAPACITY = 8;
	private final double[] samples = new double[CAPACITY];
	private int head, size;
	private boolean playing, wasFrozen;
	private int entityId;
	private RotorAnimation animation;

	public void receive(int id, double angle) {
		if (animation == null || entityId != id) {
			entityId = id;
			animation = new RotorAnimation();
			head = size = 0;
			playing = wasFrozen = false;
			animation.receive(angle);
			return;
		}
		// A packet is one server-tick endpoint. Keep both endpoints when two
		// packets arrive together instead of doubling the next tick's travel.
		if (size == CAPACITY) {
			// Bound latency after a long client stall; interpolate from the last
			// displayed pose to the retained confirmed endpoint, never predict.
			head = (head + 1) % CAPACITY;
			size--;
		}
		samples[(head + size) % CAPACITY] = angle;
		size++;
	}

	public void advance(int tick, double renderTick, boolean frozen) {
		if (animation == null) return;
		if (frozen) {
			wasFrozen = true;
			animation.advance(tick, renderTick, true);
			return;
		}
		if (wasFrozen) {
			// Finish the interrupted segment before consuming its successor.
			wasFrozen = false;
			animation.advance(tick, renderTick, false);
			return;
		}
		if (size > 3) {
			// Startup/long frame stalls can deliver many ticks together. Restore
			// the one-sample reserve instead of retaining a permanent long delay.
			head = (head + size - 2) % CAPACITY;
			size = 2;
		}
		if (!playing && size >= 2) playing = true;
		if (playing && size > 0) {
			animation.receive(samples[head]);
			head = (head + 1) % CAPACITY;
			size--;
		} else playing = false;
		animation.advance(tick, renderTick, false);
	}

	public RotationFrame frame(int id) {
		return id == entityId && animation != null ? animation.frame() : null;
	}

	public static float angleAt(RotationFrame frame, float partialTicks) {
		// Reduce in double precision, after interpolation; reducing the endpoints
		// first would reverse motion or discard turns at high speed.
		return (float) (frame.angleAt(frame.tick() + (double) partialTicks) % 360);
	}
}
