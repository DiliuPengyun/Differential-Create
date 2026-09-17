package com.diliupengyun.differential_create.kinetics;

import net.minecraft.core.BlockPos;

public final class MemberState {
	/** Independent physical rotor state; Create's network is now topology/metadata only. */
	public double physicalSpeed = Double.NaN;
	/** Integrated render angle in turns, with its assembly zero aligned on non-clutch connections. */
	public double angle;
	public double lastInertia = Double.NaN;
	/** Signed contact torque on this member, preferring its source-side connection. */
	public double transmittedTorque;
	/** Contact chosen for the current readout; rebuilt on each physical commit. */
	public BlockPos torqueContact;
	public record SavedConnection(double ratio, double twist) {}
	public final java.util.Map<BlockPos, SavedConnection> elasticConnections = new java.util.HashMap<>();
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
