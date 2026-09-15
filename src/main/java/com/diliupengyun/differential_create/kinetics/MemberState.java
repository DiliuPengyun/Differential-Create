package com.diliupengyun.differential_create.kinetics;

import net.minecraft.core.BlockPos;

public final class MemberState {
	public float savedRatio = Float.NaN;
	public float savedNominal = 16;
	public BlockPos savedAnchor;
	public float notifiedSpeed = Float.NaN;
	public float sourceCommand = Float.NaN;
	public Object attachmentA, attachmentB;
	public boolean machineActive, machineActivityKnown;
	public long lastWorkingTick = Long.MIN_VALUE;
	public net.minecraft.world.item.crafting.RecipeManager.CachedCheck<
		net.minecraft.world.item.crafting.RecipeInput,
		com.simibubi.create.content.kinetics.millstone.MillingRecipe> millingRecipeCheck;
}
