package com.diliupengyun.differential_create.kinetics;

import java.util.HashMap;
import java.util.Map;

import com.diliupengyun.differential_create.mixin.MechanicalBearingAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.world.level.block.Block;

/**
 * Moment of inertia per kinetic block, in su/RPM³ (see DESIGN.md §4.4).
 * Transmission blocks (shafts, cogwheels, gearboxes, ...) are inertialess;
 * machines and power sources carry a small positive value. Tuning lives here
 * until it moves to a config: the visible spin-up time of a network is
 * τ = J/K minutes, so values are derived from target seconds below.
 */
public final class InertiaRegistry {

	/** Default inertia for machines that have no explicit entry. */
	public static final float DEFAULT_INERTIA = 0.01f;

	private static Map<Block, Float> overrides;

	private InertiaRegistry() {}

	/**
	 * Registers an explicit inertia for a block. Must be called after Create's
	 * blocks are registered (the built-in defaults are initialised lazily on
	 * first use for the same reason).
	 */
	public static void register(Block block, float inertia) {
		if (!Float.isFinite(inertia) || inertia < 0) throw new IllegalArgumentException("Inertia must be finite and nonnegative");
		ensureInitialized();
		overrides.put(block, inertia);
		for (var level : com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor.differentialcreate$getNetworks().values())
			for (var network : level.values()) ((INetworkDynamics) network).differentialcreate$data().invalidate();
	}

	/** The fixed machine/source rotor is independent of its assembled structure's mass. */
	public static float getBaseInertia(KineticBlockEntity kbe) {
		ensureInitialized();
		return overrides.getOrDefault(kbe.getBlockState().getBlock(), DEFAULT_INERTIA);
	}

	public static float getInertia(KineticBlockEntity kbe) {
		double inertia = getBaseInertia(kbe);
		if (ActorFeed.enabled(kbe)) return (float) inertia + ActorFeed.INPUT_INERTIA;
		inertia += ContraptionPhysics.inertia(kbe);
		if (ContraptionPhysics.awaitingEntity(kbe)) {
			var state = ElasticKinetics.state(kbe);
			int count = ContraptionPhysics.drives(kbe).size();
			if (Float.isFinite(state.pendingStructureInertia) && (count < state.pendingStructureCount || kbe.getLevel().isClientSide))
				inertia = Math.max(inertia, state.pendingStructureInertia);
			else if (count == 0 && Float.isFinite(state.pendingBearingInertia)) inertia = Math.max(inertia, state.pendingBearingInertia);
		}
		return (float) inertia;
	}

	public static void checkAttachments(KineticBlockEntity be) {
		ContraptionPhysics.checkAttachments(be);
	}

	/** Sail count controls wind torque; inertia uses every mounted cube and its radius. */
	public static int getSailCount(WindmillBearingBlockEntity windmill) {
		ControlledContraptionEntity contraption =
			((MechanicalBearingAccessor) windmill).differentialcreate$getMovedContraption();
		if (contraption == null || !(contraption.getContraption() instanceof BearingContraption bearing))
			return 0;
		return bearing.getSailBlocks();
	}

	private static void ensureInitialized() {
		if (overrides != null)
			return;
		overrides = new HashMap<>();

		// Power sources. τ = J/K: water wheel K = 4 su/RPM², large wheel
		// K = 32. Steam has no internal damping; its startup depends on its finite torque.
		overrides.put(AllBlocks.WATER_WHEEL.get(), 0.15f); // τ ≈ 0.0375 min ≈ 2.3 s
		overrides.put(AllBlocks.LARGE_WATER_WHEEL.get(), 0.4f); // τ ≈ 0.0125 min ≈ 0.75 s
		overrides.put(AllBlocks.POWERED_SHAFT.get(), 0.1f); // retained rotor inertia, including after steam shuts off
		overrides.put(AllBlocks.WINDMILL_BEARING.get(), 0.02f); // rotor; assembled structure is measured separately
		overrides.put(AllBlocks.FLYWHEEL.get(), 0.5f);
		// The pair of heavy crushing rotors each carries ten ordinary machines' inertia.
		overrides.put(AllBlocks.CRUSHING_WHEEL.get(), 0.1f);

		// Inertialess transmission blocks
		inertialess(AllBlocks.SHAFT.get());
		inertialess(AllBlocks.COGWHEEL.get());
		inertialess(AllBlocks.LARGE_COGWHEEL.get());
		inertialess(AllBlocks.ANDESITE_ENCASED_SHAFT.get());
		inertialess(AllBlocks.BRASS_ENCASED_SHAFT.get());
		inertialess(AllBlocks.ANDESITE_ENCASED_COGWHEEL.get());
		inertialess(AllBlocks.BRASS_ENCASED_COGWHEEL.get());
		inertialess(AllBlocks.ANDESITE_ENCASED_LARGE_COGWHEEL.get());
		inertialess(AllBlocks.BRASS_ENCASED_LARGE_COGWHEEL.get());
		inertialess(AllBlocks.GEARBOX.get());
		inertialess(AllBlocks.CLUTCH.get());
		inertialess(com.diliupengyun.differential_create.content.ModContent.BRASS_CLUTCH.get());
		inertialess(AllBlocks.GEARSHIFT.get());
		inertialess(AllBlocks.ENCASED_CHAIN_DRIVE.get());
		inertialess(AllBlocks.ADJUSTABLE_CHAIN_GEARSHIFT.get());
		inertialess(AllBlocks.BELT.get());
		inertialess(AllBlocks.CHAIN_CONVEYOR.get());
		inertialess(AllBlocks.GANTRY_SHAFT.get());
		inertialess(AllBlocks.ROTATION_SPEED_CONTROLLER.get()); // ideal CVT: inertialess by design
	}

	private static void inertialess(Block block) {
		overrides.put(block, 0f);
	}

}
