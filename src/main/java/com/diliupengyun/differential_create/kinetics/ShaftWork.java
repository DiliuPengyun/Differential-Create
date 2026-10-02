package com.diliupengyun.differential_create.kinetics;

/** A machine phase measured in local RPM-ticks (1200 times shaft travel). */
public interface ShaftWork {
	/** Most machines use their own shaft; a sequencer measures its physical output. */
	default com.simibubi.create.content.kinetics.base.KineticBlockEntity differentialcreate$workShaft(
		com.simibubi.create.content.kinetics.base.KineticBlockEntity controller) { return controller; }
	/** Infinity means idle or blocked. Zero is an immediate state transition. */
	double differentialcreate$remainingTravel();
	/** The scheduler never supplies more travel than the current phase needs. */
	void differentialcreate$advanceTravel(double travel);
	/** Moving tools also need signed travel to follow a contact path through reversals. */
	default void differentialcreate$advanceMotion(double travel, double signedTravel) { differentialcreate$advanceTravel(travel); }
	/** Side-effect-free boundary query for a trial that may reverse the shaft. */
	default double differentialcreate$phaseLimit(double travel, double signedTravel, double remaining) { return remaining; }
}
