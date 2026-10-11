package com.diliupengyun.differential_create.kinetics;

import net.minecraft.core.BlockPos;

public final class MemberState {
	/** Independent physical rotor state; Create's network is now topology/metadata only. */
	public double physicalSpeed = Double.NaN;
	/** Integrated render angle in turns, with its assembly zero aligned on non-clutch connections. */
	public double angle;
	public double lastInertia = Double.NaN;
	public final ActorFeed actorFeed = new ActorFeed();
	/** Saved total bearing inertia while its contraption entity is still loading. */
	public float pendingBearingInertia = Float.NaN;
	public float pendingStructureInertia = Float.NaN;
	public int pendingStructureCount;
	public double pendingStructureDrag = Double.NaN;
	public double structureDrag, structureLoad;
	public double sampledStructureDrag = Double.NaN, sampledStructureLoad = Double.NaN;
	public final java.util.Set<com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity> gantryAttachments = new java.util.HashSet<>();
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
	public java.util.Set<java.util.UUID> structureAttachments = java.util.Set.of();
	public boolean machineActive, machineActivityKnown;
	public long lastWorkingTick = Long.MIN_VALUE;
	public net.minecraft.world.item.crafting.RecipeManager.CachedCheck<
		net.minecraft.world.item.crafting.RecipeInput,
		com.simibubi.create.content.kinetics.millstone.MillingRecipe> millingRecipeCheck;
}
