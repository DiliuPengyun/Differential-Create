package com.diliupengyun.differential_create.tests;

import java.util.function.BiConsumer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Test-side receipt meter. Every capability decision remains with the real receiver. */
final class ObservedFluidHandler implements IFluidHandler {
    private final IFluidHandler delegate;
    private final BiConsumer<FluidStack, Integer> received;
    ObservedFluidHandler(IFluidHandler delegate, BiConsumer<FluidStack, Integer> received) { this.delegate = delegate; this.received = received; }
    @Override public int getTanks() { return delegate.getTanks(); }
    @Override public FluidStack getFluidInTank(int tank) { return delegate.getFluidInTank(tank); }
    @Override public int getTankCapacity(int tank) { return delegate.getTankCapacity(tank); }
    @Override public boolean isFluidValid(int tank, FluidStack fluid) { return delegate.isFluidValid(tank, fluid); }
    @Override public int fill(FluidStack fluid, FluidAction action) {
        int accepted = delegate.fill(fluid, action);
        if (action.execute() && accepted > 0) received.accept(fluid, accepted);
        return accepted;
    }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return delegate.drain(resource, action); }
    @Override public FluidStack drain(int amount, FluidAction action) { return delegate.drain(amount, action); }
}
