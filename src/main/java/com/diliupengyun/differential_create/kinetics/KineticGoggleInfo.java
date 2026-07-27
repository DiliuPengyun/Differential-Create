package com.diliupengyun.differential_create.kinetics;

import java.util.List;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Appends the physical readouts (DESIGN.md §8) to Create's goggle tooltip.
 * Runs on the client: the member's speed field is the synced physical local
 * speed, and source parameters come from the same data-driven formulas the
 * server uses, so no network state is needed.
 */
public final class KineticGoggleInfo {

	private KineticGoggleInfo() {}

	public static void append(KineticBlockEntity kbe, List<Component> tooltip) {
		if (kbe instanceof ClutchBlockEntity) {
			// Clutch: coupling torque and slip (synced from the server)
			IClutchDynamics clutch = (IClutchDynamics) kbe;
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_torque",
				fmt(clutch.differentialcreate$getLastCouplingTorque())));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_slip",
				fmt1(clutch.differentialcreate$getLastDeltaOmega())));
			return;
		}

		if (kbe instanceof SpeedControllerBlockEntity controller) {
			// CVT: current ratio and both sides' local speeds
			float ratio = controller.targetSpeed.getValue() / 16f;
			float input = 0;
			if (controller.getLevel() != null
				&& controller.getLevel().getBlockEntity(controller.getBlockPos().above()) instanceof KineticBlockEntity cog)
				input = cog.getSpeed();
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.rsc_ratio", fmt(ratio)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.rsc_io", fmt1(input), fmt1(
				controller.getSpeed())));
			return;
		}

		// getSpeed() is the physical local speed on both sides (see the
		// getSpeed mixin): it is 0 when the network is stalled, which is
		// exactly when these readouts matter
		float local = kbe.getSpeed();

		if (kbe.isSource()) {
			float generated = kbe.getGeneratedSpeed();
			float omega0 = 2 * generated;
			float ratedSU = kbe.calculateAddedStressCapacity() * Math.abs(generated);
			float k = generated == 0 ? 0 : ratedSU / (generated * generated);
			float torque = k * (omega0 - local);
			float loadFactor = omega0 == 0 ? 0 : (omega0 - local) / omega0;

			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(local)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.no_load_speed", fmt1(omega0)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.load_factor", percent(loadFactor))
				.withStyle(loadFactorColor(loadFactor)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.torque", fmt(torque)));
			return;
		}

		float impact = kbe.calculateStressApplied();
		if (impact > 0) {
			// Machines: constant load torque (fans: quadratic in local speed)
			double torque = impact;
			if (kbe instanceof EncasedFanBlockEntity) {
				double scaled = local / PhysicsTuning.FAN_ANCHOR_RPM;
				torque = impact * scaled * scaled;
			}
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(local)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.torque", fmt(torque)));
			return;
		}

		// Transmission members carry no load of their own: just the speed
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(local)));
	}

	/**
	 * Green below 50% (the maximum-power point), red near/above stall,
	 * yellow in between.
	 */
	private static ChatFormatting loadFactorColor(float loadFactor) {
		if (loadFactor >= 0.9f)
			return ChatFormatting.RED;
		if (loadFactor < 0.5f)
			return ChatFormatting.GREEN;
		return ChatFormatting.YELLOW;
	}

	private static String fmt(double value) {
		return String.format("%.2f", value);
	}

	private static String fmt1(double value) {
		return String.format("%.1f", value);
	}

	private static String percent(double fraction) {
		return Math.round(fraction * 100) + "%";
	}

}
