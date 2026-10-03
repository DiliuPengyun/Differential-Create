package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.DifferentialCreate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

public final class FluidMaterials {
    /** A datapack can mark a positive-density fluid as gas without negative mass. */
    public static final TagKey<Fluid> GASES = TagKey.create(Registries.FLUID,
        ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "gases"));
    private FluidMaterials() {}
    public static boolean isGas(FluidStack fluid) {
        return fluid.getFluid().is(GASES) || fluid.getFluidType().isLighterThanAir();
    }
    public static IdealFluidSolver.Material of(FluidStack fluid) {
        int density = fluid.getFluidType().getDensity(fluid);
        return new IdealFluidSolver.Material(Math.max(1, Math.abs((double) density)), isGas(fluid));
    }
}
