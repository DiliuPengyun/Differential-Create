package com.diliupengyun.differential_create.kinetics;

import java.util.List;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Switches Create's goggle readouts between dynamics and energy (DESIGN.md §8).
 * Uses the client's network mirror for speed/energy and the same source
 * parameter formulas as the server. Clutch and pump values use BE packets.
 */
public final class KineticGoggleInfo {

	private KineticGoggleInfo() {}

	public static void append(KineticBlockEntity kbe, List<Component> tooltip, boolean isPlayerSneaking) {
		// Reserve the first row for Create's overlay icon.
		tooltip.add(Component.empty());
		tooltip.add(Component.translatable(isPlayerSneaking
			? "gui.differentialcreate.goggles.view.energy" : "gui.differentialcreate.goggles.view.dynamics")
			.withStyle(ChatFormatting.GOLD));
		if (isPlayerSneaking)
			appendEnergy(kbe, tooltip);
		else
			appendDynamics(kbe, tooltip);
		tooltip.add(Component.translatable(isPlayerSneaking
			? "gui.differentialcreate.goggles.release_for_dynamics" : "gui.differentialcreate.goggles.hold_for_energy",
			Component.keybind("key.sneak")).withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void appendDynamics(KineticBlockEntity kbe, List<Component> tooltip) {
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.inertia", fmt(InertiaRegistry.getInertia(kbe))));
		if (kbe instanceof SpeedGaugeBlockEntity gauge) {
			double speed = NetworkDynamics.preciseSpeed(gauge);
			int range = SpeedGaugeRangeBehaviour.rangeOf(gauge);
			boolean overRange = Math.abs(speed) > range;
			ChatFormatting color = speed == 0 ? ChatFormatting.GRAY : overRange ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(speed)).withStyle(color));
			tooltip.add(Component.translatable("gui.differentialcreate.gauge.range", range));
			if (overRange) tooltip.add(Component.translatable("gui.differentialcreate.gauge.over_range").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (kbe instanceof ClutchBlockEntity clutchBlock) {
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(kbe.getSpeed())));
			// Clutch: coupling torque and slip (synced from the server)
			IClutchDynamics clutch = (IClutchDynamics) kbe;
			int signal = ClutchPhysics.signal(clutchBlock);
			String state = signal == 15 ? "open" : clutch.differentialcreate$isLocked() ? "locked"
				: Math.abs(clutch.differentialcreate$getLastDeltaOmega()) > PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM
					? "slipping" : "synchronizing";
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_state",
				Component.translatable("gui.differentialcreate.goggles.clutch_state." + state)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_engagement",
				percent(ClutchControl.engagement(signal)), signal));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_limit", fmt(ClutchControl.torqueLimit(signal))));
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

		if (kbe instanceof WaterWheelBlockEntity || kbe.isSource()) {
			SourcePhysics.Curve curve = SourcePhysics.curve(kbe);
			float omega0 = (float) curve.noLoadSpeed();
			float torque = (float) curve.torque(local);
			float loadFactor = omega0 == 0 ? 0 : (omega0 - local) / omega0;

			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(local)));
			if (kbe instanceof WaterWheelBlockEntity)
				tooltip.add(Component.translatable(omega0 == 0
					? "gui.differentialcreate.goggles.no_water_flow"
					: "gui.differentialcreate.goggles.water_driven").withStyle(ChatFormatting.GRAY));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.no_load_speed", fmt1(omega0)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.load_factor", omega0 == 0 ? "—" : percent(loadFactor))
				.withStyle(omega0 == 0 ? ChatFormatting.GRAY : loadFactorColor(loadFactor)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.torque", fmt(torque)));
			return;
		}

		if (kbe instanceof IPumpDynamics pump)
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.pump_head", fmt1(pump.differentialcreate$head())));
		float impact = MachineActivity.impact(kbe);
		if (impact > 0 || MachineActivity.manages(kbe)) {
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(local)));
			if (MachineActivity.manages(kbe))
				tooltip.add(Component.translatable(MachineActivity.active(kbe)
					? "gui.differentialcreate.goggles.machine_working" : "gui.differentialcreate.goggles.machine_idle"));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.torque", fmt(loadTorque(kbe, impact, local))));
			return;
		}

		// Transmission members carry no load of their own: just the speed
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmt1(local)));
	}

	private static void appendEnergy(KineticBlockEntity kbe, List<Component> tooltip) {
		double local = NetworkDynamics.preciseSpeed(kbe);
		double energy = 0.5 * InertiaRegistry.getInertia(kbe) * local * local;
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.local_energy", fmt(energy)));
		Double networkEnergy = kbe.getLevel() == null ? null : ClientNetworkSpeeds.energyOf(kbe);
		// Missing network state is unknown, even if the local fallback speed is zero.
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.energy",
			networkEnergy == null ? "—" : fmt(networkEnergy)));

		if (kbe instanceof ClutchBlockEntity) {
			IClutchDynamics clutch = (IClutchDynamics) kbe;
			double loss = Math.abs((double) clutch.differentialcreate$getLastCouplingTorque()
				* clutch.differentialcreate$getLastDeltaOmega());
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_loss_power", fmt(loss)));
			return;
		}

		if (kbe instanceof WaterWheelBlockEntity || kbe.isSource()) {
			double power = SourcePhysics.curve(kbe).torque(local) * local;
			// A back-driven or overspeed source can absorb energy from the network.
			tooltip.add(Component.translatable(power < 0
				? "gui.differentialcreate.goggles.absorbed_power" : "gui.differentialcreate.goggles.output_power",
				fmt(Math.abs(power))));
			return;
		}

		float impact = MachineActivity.impact(kbe);
		if (impact > 0 || MachineActivity.manages(kbe))
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.consumed_power",
				fmt(loadTorque(kbe, impact, local) * Math.abs(local))));
	}

	/** Machines have constant load torque; fan drag is quadratic in local RPM. */
	private static double loadTorque(KineticBlockEntity kbe, float impact, double local) {
		if (kbe instanceof EncasedFanBlockEntity) {
			double scaled = local / PhysicsTuning.FAN_ANCHOR_RPM;
			return impact * scaled * scaled;
		}
		return impact;
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
