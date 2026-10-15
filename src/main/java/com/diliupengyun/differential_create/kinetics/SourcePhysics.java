package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.fluids.tank.BoilerData;

/** Shared source characteristics for simulation and goggles. */
public final class SourcePhysics {
	private static final Curve INACTIVE = new Curve(0, 0, 0, PhysicsTuning.STEAM_GOVERNOR_RPM, PhysicsTuning.STEAM_GOVERNOR_RANGE);
	public record Curve(double stallTorque, double damping, double powerLimit, double governorSpeed, double governorRange) {
		public Curve(double stallTorque, double damping, double powerLimit) {
			this(stallTorque, damping, powerLimit, Double.POSITIVE_INFINITY, 0);
		}
		public Curve(double noLoadSpeed, double damping) {
			this(noLoadSpeed * damping, damping, Double.POSITIVE_INFINITY);
		}
		public boolean powerLimited() { return Double.isFinite(powerLimit); }
		public double torque(double speed) {
			return DriveTorque.torque(stallTorque, powerLimit, speed, governorSpeed, governorRange) - damping * speed;
		}
		public double noLoadSpeed() {
			return stallTorque == 0 ? 0 : Math.copySign(Math.min(governorSpeed,
				damping > 0 ? Math.abs(stallTorque) / damping : Double.POSITIVE_INFINITY), stallTorque);
		}
		public double powerThresholdSpeed() { return stallTorque == 0 ? 0 : powerLimit / Math.abs(stallTorque); }
		/** A finite direction/connection command for Create, never an assigned physical speed. */
		public double generatedSpeed() {
			return powerLimited() ? Math.signum(stallTorque) * powerThresholdSpeed() : noLoadSpeed() / 2;
		}
		public double maximumPower() {
			return powerLimited() ? powerLimit : damping > 0 ? stallTorque * stallTorque / (4 * damping) : 0;
		}
		public double tangentDamping(double speed) {
			return damping + DriveTorque.slope(stallTorque, powerLimit, speed, governorSpeed, governorRange);
		}
		public double regulatorClosure(double speed) {
			return stallTorque == 0 || !Double.isFinite(governorSpeed) ? 0
				: 1 - SteamGovernor.opening(stallTorque, speed, governorSpeed, governorRange);
		}
		/** Potential power withheld by the regulator, not steam consumed or vented. */
		public double withheldPower(double speed) { return powerLimited() ? powerLimit * regulatorClosure(speed) : 0; }
	}
	private SourcePhysics() {}

	public static Curve curve(KineticBlockEntity source) {
		if (source instanceof PoweredShaftBlockEntity shaft) return steamCurve(shaft);
		double generated = source.getGeneratedSpeed();
		if (generated == 0) return new Curve(0, 0);
		double capacity = source.calculateAddedStressCapacity();
		if (source instanceof WindmillBearingBlockEntity windmill) {
			return windCurve(generated, capacity, InertiaRegistry.getSailCount(windmill));
		}
		return new Curve(2 * generated, capacity / Math.abs(generated));
	}

	public static Curve steamCurve(PoweredShaftBlockEntity shaft) {
		var world = shaft.getLevel();
		if (world == null || shaft.enginePos == null || shaft.isRemoved()) return INACTIVE;
		var pos = shaft.getBlockPos().subtract(shaft.enginePos);
		if (!world.isLoaded(pos) || !(world.getBlockEntity(pos) instanceof SteamEngineBlockEntity engine)
			|| engine.isRemoved() || !(engine.getBlockState().getBlock() instanceof SteamEngineBlock)
			|| !engine.isValid()
			|| !SteamEngineBlock.getShaftPos(engine.getBlockState(), pos).equals(shaft.getBlockPos())
			|| !SteamEngineBlock.isShaftValid(engine.getBlockState(), shaft.getBlockState())) return INACTIVE;
		var tank = engine.getTank();
		if (tank == null) return INACTIVE;
		// Resolve current supply and capacity from the live engine, never a saved efficiency.
		return admittedCurve(BlockStressValues.getCapacity(engine.getBlockState().getBlock()), tank.boiler,
			tank.getTotalTankSize(), shaft.movementDirection);
	}

	public static double steamLevel(BoilerData boiler, int size) {
		return thermalSteamLevel(boiler, size) * BoilerAdmission.fraction(boiler);
	}

	/** Boiler production ceiling, independent of current RPM and water demand. */
	public static double thermalSteamLevel(BoilerData boiler, int size) {
		if (!boiler.isActive() || boiler.getMaxHeatLevelForBoilerSize(size) == 0) return 0;
		return boiler.passiveHeat && boiler.activeHeat == 0 ? .125
			: Math.max(0, Math.min(boiler.activeHeat, boiler.getMaxHeatLevelForBoilerSize(size)));
	}

	public static Curve admittedCurve(double capacity, BoilerData boiler, int size, int direction) {
		return admission(steamCurve(capacity, thermalSteamLevel(boiler, size), boiler.attachedEngines, direction),
			BoilerAdmission.opening(boiler) * BoilerAdmission.fraction(boiler));
	}

	public static Curve admission(Curve curve, double opening) {
		if (!(opening > 0) || !Double.isFinite(opening) || curve.stallTorque() == 0) return INACTIVE;
		return new Curve(curve.stallTorque() * opening, curve.damping(), curve.powerLimit() * opening,
			curve.governorSpeed(), curve.governorRange());
	}

	public static double steamDemand(double capacity, double level, int engines, int direction, double opening, double rpm) {
		var curve = admission(steamCurve(capacity, level, engines, direction), opening);
		return SteamDemand.flow(curve.stallTorque(), curve.powerLimit(), rpm, curve.governorSpeed(),
			curve.governorRange(), PhysicsTuning.STEAM_TORQUE_PER_CAPACITY * capacity * PhysicsTuning.STEAM_RATED_RPM);
	}
	public static float steamIntake(PoweredShaftBlockEntity shaft, double rpm) {
		var curve = steamCurve(shaft);
		if (curve.stallTorque() == 0 || !curve.powerLimited()) return 0;
		var engine = (SteamEngineBlockEntity) shaft.getLevel().getBlockEntity(shaft.getBlockPos().subtract(shaft.enginePos));
		double rated = BlockStressValues.getCapacity(engine.getBlockState().getBlock())
			* PhysicsTuning.STEAM_TORQUE_PER_CAPACITY * PhysicsTuning.STEAM_RATED_RPM;
		return rated > 0 ? (float) Math.clamp(curve.powerLimit() / rated * SteamGovernor.opening(curve.stallTorque(),
			rpm, curve.governorSpeed(), curve.governorRange()), 0, 1) : 0;
	}

	/** Available continuous shaft power, limited by boiler supply and installed engine intake capacity. */
	public static double steamPeakPower(double capacity, BoilerData boiler, int size) {
		Curve curve = admittedCurve(capacity, boiler, size, 1);
		return boiler.attachedEngines * curve.maximumPower();
	}

	public static Curve windCurve(double generatedSpeed, double capacity, int sails) {
		return new Curve(2 * generatedSpeed, capacity / 16 * Math.max(0, sails) / 128);
	}

	public static Curve steamCurve(double capacity, double boilerLevel, int engines, int direction) {
		if (!(capacity > 0) || !(boilerLevel > 0) || engines <= 0 || direction == 0) return INACTIVE;
		double torqueLimit = PhysicsTuning.STEAM_TORQUE_PER_CAPACITY * capacity;
		double ratedPower = torqueLimit * PhysicsTuning.STEAM_RATED_RPM;
		// Fixed nominal cylinder pressure/displacement gives finite torque even at standstill.
		// Each engine accepts at most one boiler level; scarce supply is shared equally.
		return new Curve(Math.signum(direction) * torqueLimit, 0,
			ratedPower * Math.min(1, boilerLevel / engines),
			PhysicsTuning.STEAM_GOVERNOR_RPM, PhysicsTuning.STEAM_GOVERNOR_RANGE);
	}
}
