package com.diliupengyun.differential_create.kinetics;

/** A machine phase measured in local RPM-ticks (1200 times shaft travel). */
public interface ShaftWork {
	/** Infinity means idle or blocked. Zero is an immediate state transition. */
	double differentialcreate$remainingTravel();
	/** The scheduler never supplies more travel than the current phase needs. */
	void differentialcreate$advanceTravel(double travel);
}
