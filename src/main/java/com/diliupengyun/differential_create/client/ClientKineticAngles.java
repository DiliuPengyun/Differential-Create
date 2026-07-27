package com.diliupengyun.differential_create.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Continuous animation for networks whose speed changes every tick
 * (DESIGN.md §5.3). Flywheel's rotating instances animate in the shader as
 * degrees = offset + renderSeconds × speed, which jumps whenever the speed
 * changes. The instance is re-anchored so the displayed angle is exactly
 * r_i × θ of the network (trapezoid-integrated client-side):
 *
 * <pre>
 * first anchor (new instance): offset = staticOffset + r·θ − renderSeconds × speed
 * later refreshes (zero-jump): offset = displayed − renderSeconds × speed,
 *     unless the displayed angle already matches the network angle within a
 *     small tolerance, in which case the exact network angle is used.
 * </pre>
 *
 * The zero-jump rule guarantees the displayed angle is continuous across a
 * refresh by construction, while the rate updates to the current physical
 * speed; since every member's rate is r_i × ω, mesh alignment is preserved
 * going forward. AnimationTickHolder's render time is the same counter the
 * shader's flw_renderSeconds derives from (LevelRenderer.ticks), so the
 * re-anchor is exact. Refreshes are driven at most at 10 Hz by the block
 * entity tick hook; at constant speed no refresh happens and the GPU
 * animates on its own.
 */
public final class ClientKineticAngles {

	/** Re-anchor to the exact network angle below this deviation, zero-jump above it. */
	private static final float SNAP_TOLERANCE_DEGREES = 22.5f;

	/** Instances that already received their initial network-angle anchor. */
	private static final Map<RotatingInstance, Boolean> anchored = new WeakHashMap<>();

	private ClientKineticAngles() {}

	/**
	 * Re-anchors a freshly set-up rotating instance. First time per instance
	 * (placement): snaps to the network angle so new members mesh correctly.
	 * Afterwards: keeps the currently displayed angle (zero jump) unless it
	 * already matches the network angle closely.
	 */
	public static void apply(RotatingInstance instance, KineticBlockEntity kbe, Axis axis, float fallbackSpeed) {
		Float physical = ClientNetworkSpeeds.speedOf(kbe);
		Float ratioTheta = ClientNetworkSpeeds.thetaOf(kbe);
		if (physical == null || ratioTheta == null)
			return;
		BlockState state = kbe.getBlockState();
		float staticOffset = KineticBlockEntityVisual.rotationOffset(state, axis, kbe.getBlockPos())
			+ kbe.getRotationAngleOffset(axis);
		float renderSeconds = AnimationTickHolder.getRenderTime(kbe.getLevel()) / 20f;
		float newSpeed = physical * RotatingInstance.SPEED_MULTIPLIER;

		// What the shader is displaying right now with the old attributes
		float displayed = instance.rotationOffset + renderSeconds * instance.rotationalSpeed;
		float target = staticOffset + ratioTheta;

		float anchor;
		if (anchored.putIfAbsent(instance, Boolean.TRUE) == null) {
			anchor = target; // first anchor: align the new member with the network
		} else {
			anchor = Math.abs(displayed - target) <= SNAP_TOLERANCE_DEGREES ? target : displayed;
		}
		instance.setRotationalSpeed(newSpeed);
		instance.setRotationOffset(anchor - renderSeconds * newSpeed);
	}

	/**
	 * Angle for the block-entity-renderer path (radians), replacing
	 * time × speed when the member is synced; NaN when unsynced.
	 */
	public static float angleOf(KineticBlockEntity kbe, Axis axis, float staticOffset) {
		Float ratioTheta = ClientNetworkSpeeds.thetaOf(kbe);
		if (ratioTheta == null)
			return Float.NaN;
		return (float) (((staticOffset + ratioTheta) % 360) / 180f * Math.PI);
	}

}
