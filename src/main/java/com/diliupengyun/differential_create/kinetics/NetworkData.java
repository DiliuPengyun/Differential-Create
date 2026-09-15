package com.diliupengyun.differential_create.kinetics;

import net.minecraft.core.BlockPos;

/** State whose lifetime is exactly that of a physical network. */
public final class NetworkData {
	public void invalidate() { staticBalance = null; clutchBalances.clear(); }
	public boolean initialized;
	public boolean jammed;
	public BlockPos anchor;
	public NetworkPhysics.Balance staticBalance;
	public NetworkPhysics.Balance balance;
	public final java.util.List<com.simibubi.create.content.kinetics.base.KineticBlockEntity> activityMembers = new java.util.ArrayList<>();
	public final java.util.List<com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity> clutches = new java.util.ArrayList<>();
	public final java.util.Map<com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity,
		java.util.Map<com.simibubi.create.content.kinetics.base.KineticBlockEntity, Float>> clutchSides = new java.util.HashMap<>();
	public final java.util.Map<com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity,
		NetworkPhysics.Balance> clutchBalances = new java.util.HashMap<>();
}
