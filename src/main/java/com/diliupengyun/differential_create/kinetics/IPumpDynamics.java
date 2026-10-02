package com.diliupengyun.differential_create.kinetics;

public interface IPumpDynamics {
	float differentialcreate$head();
	double differentialcreate$flow();
	/** Shaft demand in the signed local axis; reaction on the shaft has the opposite sign. */
	double differentialcreate$torque();
	void differentialcreate$hydraulicState(double head, double flow, double torque);
	void differentialcreate$saveHydraulics(net.minecraft.nbt.CompoundTag tag);
}
