package com.diliupengyun.differential_create.kinetics;

/**
 * Hand-tuned physics constants. These are the balance entry points for the
 * mod; they may become config or datapack values later.
 */
public final class PhysicsTuning {

	/**
	 * Kinetic energy dissipated by an inelastic network merge above which the
	 * contact block breaks off instead (DESIGN.md §6.3), in su/RPM (J·RPM²).
	 * Calibration: two water wheels (J_eff = 0.02 each) counter-rotating at
	 * 16 RPM collide with Δ = 32, μ = 0.01 → E = ½·0.01·32² ≈ 5 — must break.
	 * A gentle same-direction merge (16 vs 8 RPM: Δ = 8, μ = 0.01 → E ≈ 0.3)
	 * must pass. E_BREAK = 1.0 sits between the two.
	 */
	public static final double MERGE_BREAK_ENERGY = 1.0;

	/**
	 * Fan load anchor (DESIGN.md §3.3): at this local speed (RPM) an encased
	 * fan consumes exactly its vanilla stress impact. The load grows with the
	 * square of the speed below/above the anchor.
	 */
	public static final double FAN_ANCHOR_RPM = 64;

	/**
	 * Maximum torque an engaged clutch transmits while slipping, su/RPM
	 * (DESIGN.md §6 clutch rework). Tuning entry: higher means stiffer
	 * engagement and larger shock torque on the driven side.
	 */
	public static final double CLUTCH_TORQUE = 32;

	/**
	 * Slip feedback gain, su/RPM of torque per RPM of speed difference across
	 * the clutch. ~2-tick pull-in for common inertias (steam engine 0.1,
	 * machines 0.005). The clamp at CLUTCH_TORQUE bounds the two-network
	 * coupling — this is the double-network stiffness DESIGN.md warns about,
	 * deliberately handled with a torque limit instead of infinite stiffness,
	 * so the explicit coupling cannot blow up for any J.
	 */
	public static final double CLUTCH_GAIN = 3;

	/** Speed difference below which a clutch counts as synchronous, RPM. */
	public static final double CLUTCH_LOCK_EPSILON_RPM = 0.5;

	/** Consecutive synchronous ticks before the clutch locks structurally. */
	public static final int CLUTCH_LOCK_TICKS = 20;

	/**
	 * Dissipation energy above which a violent engagement destroys the clutch
	 * block itself (instead of the contact block for plain connections).
	 */
	public static final double CLUTCH_BREAK_ENERGY = 4 * MERGE_BREAK_ENERGY;

	private PhysicsTuning() {}

}
