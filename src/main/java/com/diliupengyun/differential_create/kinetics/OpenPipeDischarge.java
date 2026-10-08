package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.mixin.OpenEndedPipeAccessor;
import com.simibubi.create.AllParticleTypes;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.particle.FluidParticleData;
import com.simibubi.create.foundation.fluid.FluidHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Only committed world discharges produce outward particles. */
public final class OpenPipeDischarge {
    private OpenPipeDischarge() {}

    public interface Placement {
        void differentialcreate$clearDischarge();
        /** Read-only preview of the indivisible world transfer, excluding cached stock. */
        FluidStack differentialcreate$worldFluid();
        /** Withdraw a parcel only after reserving space for its whole world transfer. */
        FluidStack differentialcreate$takeWorld(FluidStack filter, int amount, double receivingSpace);
        /** Actual fractional open-air receipt; -1 keeps a solid target's native integer staging. */
        double differentialcreate$discharge(FluidStack identity, double amount, FluidAction action);
    }

    public static boolean react(OpenEndedPipe pipe, FluidStack owned) {
        var world = pipe.getWorld();
        var output = pipe.getOutputPos();
        if (world == null || world.isClientSide || !world.isLoaded(output) || owned.isEmpty()
            || !(owned.getFluid() instanceof FlowingFluid) || !FluidHelper.hasBlockState(owned.getFluid())) return false;
        var outside = world.getFluidState(output);
        if (outside.isEmpty() || FluidHelper.convertToStill(outside.getType()) == owned.getFluid()) return false;
        boolean reacted = ((OpenEndedPipeAccessor) pipe).differentialcreate$provideFluidToSpace(owned, false);
        if (reacted && pipe.provideHandler().getCapability() instanceof Placement placement)
            placement.differentialcreate$clearDischarge();
        return reacted;
    }

    /** Send an ordinary particle packet after the caller has spent real stock.
     * No saved flow flag or client animation can regenerate this discharge. */
    public static void emit(OpenEndedPipe pipe, FluidStack spent) {
        if (spent.isEmpty() || !(pipe.getWorld() instanceof ServerLevel world)) return;
        var pos = pipe.getPos();
        var output = pipe.getOutputPos();
        int dx = output.getX() - pos.getX(), dy = output.getY() - pos.getY(), dz = output.getZ() - pos.getZ();
        double x = pos.getX() + .5 + dx * .6;
        double y = pos.getY() + .5 + dy * .6 - 1.0 / 16;
        double z = pos.getZ() + .5 + dz * .6;
        // count=0 carries this single particle's explicit outward velocity.
        world.sendParticles(new FluidParticleData(AllParticleTypes.FLUID_PARTICLE.get(), spent.copyWithAmount(1)),
            x, y, z, 0, dx * .12, dy * .12, dz * .12, 1);
    }
}
