package com.diliupengyun.differential_create.kinetics;

/** A machine phase measured in local RPM-ticks (1200 times shaft travel). */
public interface ShaftWork {
	/** Absolute work and the two directional motion boundaries, in local RPM-ticks. */
	record Boundary(double travel, double positive, double negative) {}
	/** Most machines use their own shaft; a sequencer measures its physical output. */
	default com.simibubi.create.content.kinetics.base.KineticBlockEntity differentialcreate$workShaft(
		com.simibubi.create.content.kinetics.base.KineticBlockEntity controller) { return controller; }
	/** Infinity means idle or blocked. Zero is an immediate state transition. */
	double differentialcreate$remainingTravel();
	/** Explicit limits let the solver detect a crossing even when the shaft reverses. */
	default Boundary differentialcreate$boundary(double remaining) {
		return new Boundary(remaining, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
	}
	/** A blocked moving tool can resume within this tick when speed enters this direction. */
	default int differentialcreate$wakeDirection() { return 0; }
	/** The scheduler never supplies more travel than the current phase needs. */
	void differentialcreate$advanceTravel(double travel);
	/** Moving tools also need signed travel to follow a contact path through reversals. */
	default void differentialcreate$advanceMotion(double travel, double signedTravel) { differentialcreate$advanceTravel(travel); }
	/** Convert signed motion to the work charged at a boundary. Overrides must also
	 * expose equivalent limits through differentialcreate$boundary. */
	default double differentialcreate$phaseLimit(double travel, double signedTravel, double remaining) { return remaining; }
}
