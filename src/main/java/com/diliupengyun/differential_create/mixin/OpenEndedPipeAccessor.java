package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(OpenEndedPipe.class)
public interface OpenEndedPipeAccessor {
    @Invoker("provideFluidToSpace")
    boolean differentialcreate$provideFluidToSpace(FluidStack fluid, boolean simulate);

    @Invoker("removeFluidFromSpace")
    FluidStack differentialcreate$removeFluidFromSpace(boolean simulate);

    @Accessor("wasPulling")
    void differentialcreate$setPulling(boolean pulling);
}
