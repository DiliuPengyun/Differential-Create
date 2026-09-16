package com.diliupengyun.differential_create.kinetics;

/**
 * Hand-tuned physics constants. These are the balance entry points for the
 * mod; they may become config or datapack values later.
 */
public final class PhysicsTuning {

	/**
	 * Kinetic energy dissipated by an inelastic network merge above which the
	 * contact block breaks off instead (DESIGN.md §6.3), in su/RPM (J·RPM²).
	 * Two water wheels (J=0.15 each) at ±16 RPM dissipate 38.4 and break.
	 * At 16 and 14 RPM they dissipate 0.15 and remain connected.
	 */
	public static final double MERGE_BREAK_ENERGY = 1.0;

	/**
	 * Fan load anchor (DESIGN.md §3.3): at this local speed (RPM) an encased
	 * fan consumes exactly its vanilla stress impact. The load grows with the
	 * square of the speed below/above the anchor.
	 */
	public static final double FAN_ANCHOR_RPM = 64;

	/**
	 * Maximum torque a fully engaged starter clutch transmits, su/RPM.
	 * (DESIGN.md §6 clutch rework). Tuning entry: higher means stiffer
	 * engagement and larger shock torque on the driven side. Redstone scales
	 * this limit linearly by (15 - signal) / 15.
	 */
	public static final double CLUTCH_TORQUE = 32;

	/** Heavy-duty clutch rating, scaled by its manual pressure and the redstone release. */
	public static final double BRASS_CLUTCH_TORQUE = 1024;

	/** Speed difference below which a clutch counts as synchronous, RPM. */
	public static final double CLUTCH_LOCK_EPSILON_RPM = 0.5;

	/** Consecutive synchronous ticks before the clutch locks structurally. */
	public static final int CLUTCH_LOCK_TICKS = 20;

	/**
	 * Maximum residual synchronization loss allowed when a clutch locks.
	 * Larger mismatches keep slipping; they do not destroy the clutch.
	 */
	public static final double CLUTCH_LOCK_ENERGY = 4 * MERGE_BREAK_ENERGY;

	private PhysicsTuning() {}

}
