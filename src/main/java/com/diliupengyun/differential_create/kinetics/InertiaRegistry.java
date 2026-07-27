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

	/**
	 * Inertia per sail block of a windmill (su/RPM³). Tuning entry: this is
	 * what makes big windmills slow to spin up and slow to stop (DESIGN.md
	 * §9.3). 16 sails ≈ 0.18 total → several seconds to full speed.
	 */
	public static final float SAIL_INERTIA = 0.01f;

	private static Map<Block, Float> overrides;

	private InertiaRegistry() {}

	/**
	 * Registers an explicit inertia for a block. Must be called after Create's
	 * blocks are registered (the built-in defaults are initialised lazily on
	 * first use for the same reason).
	 */
	public static void register(Block block, float inertia) {
		ensureInitialized();
		overrides.put(block, inertia);
	}

	public static float getInertia(KineticBlockEntity kbe) {
		ensureInitialized();
		if (kbe instanceof WindmillBearingBlockEntity windmill)
			return overrides.getOrDefault(kbe.getBlockState()
				.getBlock(), DEFAULT_INERTIA) + SAIL_INERTIA * getSailCount(windmill);
		return overrides.getOrDefault(kbe.getBlockState()
			.getBlock(), DEFAULT_INERTIA);
	}

	/**
	 * Sails of the assembled windmill contraption (DESIGN.md §9: the bearing
	 * aggregates J = base + sails × J_sail). Recomputed every call, so
	 * assembly/disassembly applies on the next tick with no event.
	 */
	private static int getSailCount(WindmillBearingBlockEntity windmill) {
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
		// K = 32, steam engine K = 4 — targets in comments
		overrides.put(AllBlocks.WATER_WHEEL.get(), 0.15f); // τ ≈ 0.0375 min ≈ 2.3 s
		overrides.put(AllBlocks.LARGE_WATER_WHEEL.get(), 0.4f); // τ ≈ 0.0125 min ≈ 0.75 s
		overrides.put(AllBlocks.POWERED_SHAFT.get(), 0.1f); // steam engine shaft: τ ≈ 1.5 s (approved)
		overrides.put(AllBlocks.WINDMILL_BEARING.get(), 0.02f); // base + per-sail SAIL_INERTIA

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
