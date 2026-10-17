package com.diliupengyun.differential_create.kinetics;

/**
 * Hand-tuned physics constants. These are the balance entry points for the
 * mod; they may become config or datapack values later.
 */
public final class PhysicsTuning {

	/** Constant-power threshold at full per-engine supply, not a no-load speed or hard limit. */
	public static final double STEAM_RATED_RPM = 256;
	/** Governor target on the engine's own shaft, independent of the power threshold. */
	public static final double STEAM_GOVERNOR_RPM = 512;
	/** Admission and water demand taper together over the last 64 RPM. */
	public static final double STEAM_GOVERNOR_RANGE = 64;
	/** Cylinder torque per capacity unit; the default engine capacity gives 1024 su/RPM. */
	public static final double STEAM_TORQUE_PER_CAPACITY = 1;

	/** Torque per calibrated turn; 128 torque twists one contact by only 0.0046 degrees. */
	public static final double TORSIONAL_STIFFNESS = 10_000_000;
	/** No viscous contact loss: unloaded elastic connections may keep oscillating. */
	public static final double TORSIONAL_DAMPING = 0;
	/** Local-shaft structural limits, used during both transients and steady operation. */
	public static final double ANDESITE_STRENGTH = 65_536;
	public static final double BRASS_STRENGTH = 262_144;
	/** Numerical resolution, not a material contact duration (64 steps per normal tick). */
	public static final double TORSION_MAX_STEP = RotationMath.TICK_MINUTES / 64;

	/**
	 * Fan load anchor (DESIGN.md §3.3): at this local speed (RPM) an encased
	 * fan consumes exactly its vanilla stress impact. The load grows with the
	 * square of the speed below/above the anchor.
	 */
	public static final double FAN_ANCHOR_RPM = 64;

	/** One air unit at 128 RPM per tick, matching Create's compressor at that anchor. */
	public static final double BACKTANK_TRAVEL_PER_AIR = 128;

	/** Native ejector winding: 256 local RPM-ticks per block of target distance. */
	public static final double EJECTOR_TRAVEL_PER_DISTANCE = 256;

	/**
	 * Maximum torque a fully engaged starter clutch transmits, su/RPM.
	 * (DESIGN.md §6 clutch rework). Tuning entry: higher means stiffer
	 * engagement and larger shock torque on the driven side. Redstone scales
	 * this limit linearly by (15 - signal) / 15.
	 */
	public static final double CLUTCH_TORQUE = 1_024;

	/** Heavy-duty clutch rating, scaled by its manual pressure and the redstone release. */
	public static final double BRASS_CLUTCH_TORQUE = 32_768;

	/** Speed difference below which a clutch counts as synchronous, RPM. */
	public static final double CLUTCH_LOCK_EPSILON_RPM = 0.5;

	/** Consecutive synchronous ticks before the clutch locks structurally. */
	public static final int CLUTCH_LOCK_TICKS = 20;

	private PhysicsTuning() {}

}
