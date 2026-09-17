package com.diliupengyun.differential_create.kinetics;

import java.util.List;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
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
		if (kbe instanceof PoweredShaftBlockEntity) {
			double local = NetworkDynamics.preciseSpeed(kbe);
			SourcePhysics.Curve curve = SourcePhysics.curve(kbe);
			double torque = curve.torque(local);
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(local, 1)));
			String mode = curve.stallTorque() == 0 ? "no_steam" : torque * local < 0 ? "steam_reversing" : "steam_driven";
			tooltip.add(Component.translatable("gui.differentialcreate.goggles." + mode).withStyle(ChatFormatting.GRAY));
			appendLoadFactor(kbe, tooltip);
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.torque", fmtSigned(torque, 2)));
			return;
		}
		if (kbe instanceof SpeedGaugeBlockEntity gauge) {
			double speed = NetworkDynamics.preciseSpeed(gauge);
			int range = SpeedGaugeRangeBehaviour.rangeOf(gauge);
			boolean overRange = Math.abs(speed) > range;
			ChatFormatting color = speed == 0 ? ChatFormatting.GRAY : overRange ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(speed, 1)).withStyle(color));
			tooltip.add(Component.translatable("gui.differentialcreate.gauge.range", range));
			if (overRange) tooltip.add(Component.translatable("gui.differentialcreate.gauge.over_range").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (kbe instanceof ClutchBlockEntity) {
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(kbe.getSpeed(), 1)));
			// Clutch: coupling torque and slip (synced from the server)
			IClutchDynamics clutch = (IClutchDynamics) kbe;
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_torque",
				fmtSigned(clutch.differentialcreate$getLastCouplingTorque(), 2)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.clutch_slip",
				fmtSigned(clutch.differentialcreate$getLastDeltaOmega(), 1)));
			return;
		}

		if (kbe instanceof SpeedControllerBlockEntity controller) {
			// CVT: current ratio and both sides' local speeds
			float ratio = controller.targetSpeed.getValue() / 16f;
			float input = 0;
			if (controller.getLevel() != null
				&& controller.getLevel().getBlockEntity(controller.getBlockPos().above()) instanceof KineticBlockEntity cog)
				input = cog.getSpeed();
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.rsc_ratio", fmtSigned(ratio, 2)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.rsc_io", fmtSigned(input, 1), fmtSigned(
				controller.getSpeed(), 1)));
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

			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(local, 1)));
			if (kbe instanceof WaterWheelBlockEntity)
				tooltip.add(Component.translatable(omega0 == 0
					? "gui.differentialcreate.goggles.no_water_flow"
					: "gui.differentialcreate.goggles.water_driven").withStyle(ChatFormatting.GRAY));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.no_load_speed", fmtSigned(omega0, 1)));
			appendLoadFactor(kbe, tooltip);
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.torque", fmtSigned(torque, 2)));
			return;
		}

		if (kbe instanceof IPumpDynamics pump)
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.pump_head", fmt1(pump.differentialcreate$head())));
		float impact = MachineActivity.impact(kbe);
		if (impact > 0 || MachineActivity.manages(kbe)) {
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(local, 1)));
			if (MachineActivity.manages(kbe))
				tooltip.add(Component.translatable(MachineActivity.active(kbe)
					? "gui.differentialcreate.goggles.machine_working" : "gui.differentialcreate.goggles.machine_idle"));
			double load = MachineActivity.loadTorque(kbe, local);
			// At rest the model supplies a two-sided breakaway limit, not a signed reaction.
			boolean starting = local == 0 && load >= .005;
			tooltip.add(Component.translatable(starting
				? "gui.differentialcreate.goggles.starting_resistance"
				: "gui.differentialcreate.goggles.resisting_torque",
				starting ? "±" + fmt(load) : fmtSigned(-Math.signum(local) * load, 2)));
			return;
		}

		// Transmission members show their local speed and transmitted torque.
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(local, 1)));
		// Keep the nullable client reading boxed: a Float/float conditional
		// unboxes it before the null check while rotor packets are still pending.
		Float transmitted;
		if (kbe.getLevel() != null && kbe.getLevel().isClientSide)
			transmitted = ClientNetworkSpeeds.transmittedTorqueOf(kbe);
		else
			transmitted = (float) ElasticKinetics.state(kbe).transmittedTorque;
		if (transmitted != null)
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.transmitted_torque", fmtSigned(transmitted, 2)));
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.strength", fmt(ElasticKinetics.strengthOf(kbe))));
	}

	private static void appendEnergy(KineticBlockEntity kbe, List<Component> tooltip) {
		double local = NetworkDynamics.preciseSpeed(kbe);
		double energy = 0.5 * InertiaRegistry.getInertia(kbe) * local * local;
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.local_energy", fmt(energy)));
		Double networkEnergy = null;
		if (kbe.getLevel() != null) {
			if (kbe.getLevel().isClientSide) networkEnergy = ClientNetworkSpeeds.energyOf(kbe);
			else if (kbe.hasNetwork()) networkEnergy = ElasticKinetics.kineticEnergy(kbe.getOrCreateNetwork());
		}
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

		if (kbe instanceof WaterWheelBlockEntity || kbe instanceof PoweredShaftBlockEntity || kbe.isSource()) {
			SourcePhysics.Curve curve = SourcePhysics.curve(kbe);
			double power = curve.torque(local) * local;
			// A back-driven or overspeed source can absorb energy from the network.
			tooltip.add(Component.translatable(power < 0
				? "gui.differentialcreate.goggles.absorbed_power" : "gui.differentialcreate.goggles.output_power",
				fmt(Math.abs(power))));
			return;
		}

		float impact = MachineActivity.impact(kbe);
		if (impact > 0 || MachineActivity.manages(kbe))
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.consumed_power",
					fmt(MachineActivity.loadTorque(kbe, local) * Math.abs(local))));
	}

	private static void appendLoadFactor(KineticBlockEntity be, List<Component> tooltip) {
		LoadFactor.Reading reading = be.getLevel() != null && be.getLevel().isClientSide
			? ClientNetworkSpeeds.loadFactorOf(be) : NetworkLoad.of(be);
		Component value = switch (reading.status()) {
			case AVAILABLE -> Component.literal(percent(reading.fraction()));
			case NO_POWER -> Component.translatable("gui.differentialcreate.goggles.load_no_power");
			case BRAKING -> Component.translatable("gui.differentialcreate.goggles.load_braking");
			case UNKNOWN -> Component.literal("—");
		};
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.load_factor", value)
			.withStyle(reading.status() == LoadFactor.Status.AVAILABLE ? loadFactorColor(reading.fraction()) : ChatFormatting.GRAY));
	}

	/** Color the displayed demand/supply percentage; equilibrium is 100%, not a stall warning. */
	private static ChatFormatting loadFactorColor(double loadFactor) {
		long displayed = Math.round(loadFactor * 100);
		if (displayed > 100)
			return ChatFormatting.RED;
		if (displayed < 90)
			return ChatFormatting.GREEN;
		return ChatFormatting.YELLOW;
	}

	private static String fmt(double value) {
		return String.format("%.2f", value);
	}

	/** Directional readouts share explicit signs and suppress rounded positive/negative zero. */
	private static String fmtSigned(double value, int decimals) {
		boolean zero = Math.abs(value) < .5 * Math.pow(10, -decimals);
		return String.format((zero ? "%." : "%+.") + decimals + "f", zero ? 0 : value);
	}

	private static String fmt1(double value) {
		return String.format("%.1f", value);
	}

	private static String percent(double fraction) {
		if (Double.isInfinite(fraction)) return "∞%";
		return Math.round(fraction * 100) + "%";
	}

}
