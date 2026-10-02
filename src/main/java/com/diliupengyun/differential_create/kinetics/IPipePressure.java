package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.fluids.FlowSource;
import net.neoforged.neoforge.fluids.FluidStack;

/** Create's existing flow object is presentation only; movement has one owner. */
public interface IPipePressure {
    FlowSource differentialcreate$source();
    void differentialcreate$showFlow(FluidStack fluid, double outward);
}
