package com.diliupengyun.differential_create.kinetics;

public interface IPumpDynamics {
	/** Head and through-flow use the renderer's right-hand positive world axis:
	 * east for X, up for Y, south for Z, independent of the block's front. */
	float differentialcreate$head();
	double differentialcreate$flow();
	/** Shaft demand in the signed local axis; reaction on the shaft has the opposite sign. */
	double differentialcreate$torque();
	void differentialcreate$hydraulicState(double head, double flow, double torque);
	void differentialcreate$saveHydraulics(net.minecraft.nbt.CompoundTag tag);
}
