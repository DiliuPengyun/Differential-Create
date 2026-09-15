package com.diliupengyun.differential_create.kinetics;

/**
 * Per-block-entity bookkeeping attached to Create's
 * {@code KineticBlockEntity} by mixin.
 */
public interface IMemberDynamics {
	MemberState differentialcreate$member();

	/**
	 * Network speed restored from NBT, waiting to be applied once the block
	 * entity has rejoined its network. {@link Float#NaN} when absent.
	 */
	float differentialcreate$getPendingNetworkSpeed();

	void differentialcreate$setPendingNetworkSpeed(float speed);

	void differentialcreate$clearPendingNetworkSpeed();

	/**
	 * Physical speed at the last visual refresh (client side). The tick hook
	 * queues a visual update when the synced speed moved past the threshold,
	 * so rotating instances re-anchor at ≤10 Hz during ramps and never at
	 * steady state.
	 */
	float differentialcreate$getLastVisualSpeed();

	void differentialcreate$setLastVisualSpeed(float speed);

	/** Game time of the last visual refresh (client side), for the 10 Hz cap. */
	long differentialcreate$getLastVisualTick();

	void differentialcreate$setLastVisualTick(long tick);

}
