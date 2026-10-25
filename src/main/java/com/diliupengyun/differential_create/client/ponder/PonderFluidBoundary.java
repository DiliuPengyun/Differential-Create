package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.foundation.ICapabilityProvider;
import net.createmod.catnip.math.BlockFace;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** A finite inventory beyond the displayed pipe end; only the production solver transfers fluid. */
public final class PonderFluidBoundary extends FlowSource {
    private final FluidTank inventory;
    private final ICapabilityProvider<IFluidHandler> provider;

    public PonderFluidBoundary(BlockFace port, FluidTank inventory) {
        super(port);
        this.inventory = inventory;
        provider = ICapabilityProvider.of(() -> inventory);
    }

    public FluidTank inventory() { return inventory; }
    @Override public boolean isEndpoint() { return true; }
    @Override public ICapabilityProvider<IFluidHandler> provideHandler() { return provider; }
}
