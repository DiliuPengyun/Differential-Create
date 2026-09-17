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
		if (!Float.isFinite(inertia) || inertia < 0) throw new IllegalArgumentException("Inertia must be finite and nonnegative");
		ensureInitialized();
		overrides.put(block, inertia);
		for (var level : com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor.differentialcreate$getNetworks().values())
			for (var network : level.values()) ((INetworkDynamics) network).differentialcreate$data().invalidate();
	}

	public static float getInertia(KineticBlockEntity kbe) {
		ensureInitialized();
		float inertia = overrides.getOrDefault(kbe.getBlockState().getBlock(), DEFAULT_INERTIA);
		if (kbe instanceof WindmillBearingBlockEntity windmill) inertia += SAIL_INERTIA * getSailCount(windmill);
		for (var entity : attachments(kbe)) {
			if (entity == null || entity.getContraption() == null) continue;
			for (var block : entity.getContraption().getBlocks().values())
				if (block.state().getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate)
					inertia += overrides.getOrDefault(block.state().getBlock(), DEFAULT_INERTIA);
		}
		return inertia;
	}

	private static com.simibubi.create.content.contraptions.AbstractContraptionEntity[] attachments(KineticBlockEntity be) {
		if (be instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing)
			return new com.simibubi.create.content.contraptions.AbstractContraptionEntity[]{bearing.getMovedContraption(), null};
		if (be instanceof com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity actuator)
			return new com.simibubi.create.content.contraptions.AbstractContraptionEntity[]{actuator.movedContraption, null};
		if (be instanceof com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor clock)
			return new com.simibubi.create.content.contraptions.AbstractContraptionEntity[]{clock.differentialcreate$hour(), clock.differentialcreate$minute()};
		return new com.simibubi.create.content.contraptions.AbstractContraptionEntity[]{null, null};
	}

	public static void checkAttachments(KineticBlockEntity be) {
		if (!(be instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)
			&& !(be instanceof com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity)
			&& !(be instanceof com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor)) return;
		var entities = attachments(be);
		MemberState member = ((IMemberDynamics) be).differentialcreate$member();
		if (entities[0] == member.attachmentA && entities[1] == member.attachmentB) return;
		member.attachmentA = entities[0];
		member.attachmentB = entities[1];
		if (be.hasNetwork()) {
			NetworkData data = ((INetworkDynamics) be.getOrCreateNetwork()).differentialcreate$data();
			data.invalidate();
		}
	}

	/**
	 * Sails of the assembled windmill contraption (DESIGN.md §9: the bearing
	 * aggregates J = base + sails × J_sail). Recomputed every call, so
	 * assembly/disassembly applies on the next tick with no event.
	 */
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
		overrides.put(AllBlocks.WINDMILL_BEARING.get(), 0.02f); // base + per-sail SAIL_INERTIA
		overrides.put(AllBlocks.FLYWHEEL.get(), 0.5f);

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
