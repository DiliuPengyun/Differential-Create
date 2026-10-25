package com.diliupengyun.differential_create.client;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;

/**
 * Re-anchors the belt surface's scrolling texture to the member's accumulated
 * travel. Create scrolls the surface with speed × absolute render time, which
 * jumps whenever the finite-torque solver changes speed; the rotating models
 * already use the accumulated angle for the same reason.
 */
public final class BeltScrollPhase {
	/** One texture repeat per 31.5 × 16 RPM-ticks, as in Create's belt renderers. */
	private static final double TICKS_PER_REPEAT = 31.5 * 16;

	private BeltScrollPhase() {}

	/**
	 * Signed texture repeats for the Flywheel visual, or NaN when the member
	 * has no synced angle yet.
	 */
	public static double visualRepeats(BeltBlockEntity be) {
		Double theta = theta(be);
		if (theta == null) return Double.NaN;
		var state = be.getBlockState();
		if (!AllBlocks.BELT.has(state)) return Double.NaN;
		BeltSlope slope = state.getValue(BeltBlock.SLOPE);
		Direction facing = state.getValue(BeltBlock.HORIZONTAL_FACING);
		boolean diagonal = slope.isDiagonal();
		boolean sideways = slope == BeltSlope.SIDEWAYS;
		boolean vertical = slope == BeltSlope.VERTICAL;
		boolean upward = slope == BeltSlope.UPWARD;
		boolean alongX = facing.getAxis() == Axis.X;
		boolean alongZ = facing.getAxis() == Axis.Z;
		boolean flip = ((facing.getAxisDirection() == AxisDirection.NEGATIVE) ^ upward)
			^ ((alongX && !diagonal) || (alongZ && diagonal));
		if (sideways && (facing == Direction.SOUTH || facing == Direction.WEST)
			|| vertical && facing == Direction.EAST)
			flip = !flip;
		double ticks = theta * (10.0 / 3.0);
		if (flip) ticks = -ticks;
		double repeats = ticks / TICKS_PER_REPEAT;
		return repeats - Math.floor(repeats);
	}

	/**
	 * Equivalent of {@code getSpeed() × renderTick} for the fallback renderer,
	 * reduced modulo one texture repeat so the float conversion keeps its
	 * fractional phase. NaN when no angle is synced.
	 */
	public static double rendererTicks(BeltBlockEntity be) {
		Double theta = theta(be);
		if (theta == null) return Double.NaN;
		return theta * (10.0 / 3.0) % TICKS_PER_REPEAT;
	}

	private static Double theta(BeltBlockEntity be) {
		if (be == null) return null;
		Double theta = ClientNetworkSpeeds.thetaOf(be);
		return theta == null || !Double.isFinite(theta) ? null : theta;
	}
}
