package com.diliupengyun.differential_create.kinetics;

import java.util.List;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
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
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.inertia", fmt(InertiaRegistry.getInertia(kbe)
			+ (kbe instanceof ISequencerDynamics ? SequencerMotion.OUTPUT_INERTIA : 0)
			+ (ActorFeed.of(kbe).active ? ActorFeed.of(kbe).inertia : 0))));
		double strength = ElasticKinetics.strengthOf(kbe);
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.strength",
			Double.isInfinite(strength) ? "∞" : fmt(strength)));
		if (kbe instanceof ISequencerDynamics servo) {
			var m = servo.differentialcreate$motion();
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.servo_io", fmtSigned(kbe.getSpeed(), 1), fmtSigned(m.speed, 1)));
			var program = servo.differentialcreate$program();
			boolean distance = m.index >= 0 && m.index < program.size() && program.get(m.index).kind() == SequencerMotion.Kind.DISTANCE;
			double remaining = Math.max(0, m.direction * (m.target - m.angle));
            if (m.inputDirection == 0 && m.index >= 0 && m.index < program.size()) remaining = program.get(m.index).turns();
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.servo_remaining_" + (distance ? "distance" : "angle"),
				fmt(remaining * (distance ? 1200d / 512 : 360))));
            String state = m.index >= 0 && m.inputDirection == 0 ? "waiting" : m.command.holding() ? "holding" : m.command.brake() > 0 ? "braking" : m.command.coupling() > 0 ? "moving" : "waiting";
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.servo_" + state).withStyle(ChatFormatting.GRAY));
			return;
		}
		if (kbe instanceof PoweredShaftBlockEntity) {
			double local = NetworkDynamics.preciseSpeed(kbe);
			SourcePhysics.Curve curve = SourcePhysics.curve(kbe);
			double torque = curve.torque(local);
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(local, 1)));
			double vent = curve.ventFraction(local);
			String mode = curve.stallTorque() == 0 ? "no_steam" : torque * local < 0 ? "steam_reversing"
				: vent > 1e-6 ? "steam_governing" : "steam_driven";
			tooltip.add(Component.translatable("gui.differentialcreate.goggles." + mode).withStyle(ChatFormatting.GRAY));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.steam_governor_speed", fmt(PhysicsTuning.STEAM_GOVERNOR_RPM)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.steam_venting", fmt1(100 * vent)));
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

		// A disassembled bearing has no moving output. Its remaining input-side
		// rotor may still spin, including in the client's last rotor packet.
		boolean mechanicalBearing = kbe instanceof MechanicalBearingBlockEntity && !(kbe instanceof WindmillBearingBlockEntity);
		float local = mechanicalBearing && !((MechanicalBearingBlockEntity) kbe).isRunning() ? 0 : kbe.getSpeed();

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

		if (kbe instanceof IPumpDynamics pump) {
			var axis = ((IRotate)kbe.getBlockState().getBlock()).getRotationAxis(kbe.getBlockState());
			var positive = Component.translatable("gui.differentialcreate.goggles.pump_axis_" + axis.getSerializedName());
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.speed", fmtSigned(local, 1)));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.pump_head", fmtSigned(pump.differentialcreate$head(), 1), positive));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.pump_flow", fmtSigned(pump.differentialcreate$flow(), 2), positive));
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.pump_reaction", fmtSigned(-pump.differentialcreate$torque(), 2)));
			return;
		}
		float impact = MachineActivity.impact(kbe);
		if (impact > 0 || MachineActivity.manages(kbe)) {
			// Keep the bearing's established output reading tied to the body,
			// even while the built-in hand take-off keeps its input turning.
			float output = mechanicalBearing && ((MechanicalBearingBlockEntity) kbe).isRunning() && ActorFeed.of(kbe).active
				? (float) ActorFeed.of(kbe).speed : local;
			tooltip.add(Component.translatable(mechanicalBearing
				? "gui.differentialcreate.goggles.bearing_output_speed" : "gui.differentialcreate.goggles.speed", fmtSigned(output, 1)));
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
	}

	private static void appendEnergy(KineticBlockEntity kbe, List<Component> tooltip) {
		double local = NetworkDynamics.preciseSpeed(kbe);
		double energy = 0.5 * InertiaRegistry.getInertia(kbe) * local * local;
		if (kbe instanceof ISequencerDynamics servo) energy += .5 * SequencerMotion.OUTPUT_INERTIA * Math.pow(servo.differentialcreate$motion().speed, 2);
		if (ActorFeed.of(kbe).active) energy += .5 * ActorFeed.of(kbe).inertia * Math.pow(ActorFeed.of(kbe).speed, 2);
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.local_energy", fmt(energy)));
		if (kbe instanceof ISequencerDynamics servo)
			tooltip.add(Component.translatable("gui.differentialcreate.goggles.servo_brake_heat", fmt(servo.differentialcreate$motion().brakeHeat)));
		Double networkEnergy = null;
		Double localElasticEnergy = null, networkElasticEnergy = null;
		if (kbe.getLevel() != null) {
			if (kbe.getLevel().isClientSide) {
				networkEnergy = ClientNetworkSpeeds.energyOf(kbe);
				localElasticEnergy = ClientNetworkSpeeds.localElasticEnergyOf(kbe);
				networkElasticEnergy = ClientNetworkSpeeds.elasticEnergyOf(kbe);
			} else {
				localElasticEnergy = 0d;
				if (kbe.hasNetwork()) {
					var network = kbe.getOrCreateNetwork();
					networkEnergy = ElasticKinetics.kineticEnergy(network);
					var elasticEnergy = ElasticKinetics.elasticEnergy(network);
					localElasticEnergy = elasticEnergy.of(kbe);
					networkElasticEnergy = elasticEnergy.total();
				}
			}
		}
		// Missing network state is unknown, even if the local fallback speed is zero.
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.energy",
			networkEnergy == null ? "—" : fmt(networkEnergy)));
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.local_elastic_energy",
			localElasticEnergy == null ? "—" : fmt(localElasticEnergy)));
		tooltip.add(Component.translatable("gui.differentialcreate.goggles.elastic_energy",
			networkElasticEnergy == null ? "—" : fmt(networkElasticEnergy)));

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
			if (kbe instanceof PoweredShaftBlockEntity)
				tooltip.add(Component.translatable("gui.differentialcreate.goggles.steam_vent_power", fmt(curve.ventedPower(local))));
			return;
		}

		if (kbe instanceof IPumpDynamics pump) {
			double power = pump.differentialcreate$torque() * local;
			tooltip.add(Component.translatable(power < 0
				? "gui.differentialcreate.goggles.output_power" : "gui.differentialcreate.goggles.consumed_power", fmt(Math.abs(power))));
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
