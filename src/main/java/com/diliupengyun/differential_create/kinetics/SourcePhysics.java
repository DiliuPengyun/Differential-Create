package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;

/** Shared source characteristics for simulation and goggles. */
public final class SourcePhysics {
	public record Curve(double noLoadSpeed, double damping) {
		public double stallTorque() { return noLoadSpeed * damping; }
		public double torque(double speed) { return damping * (noLoadSpeed - speed); }
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
		double capacity = shaft.capacityKey == null ? 0 : BlockStressValues.getCapacity(shaft.capacityKey);
		double level = Math.max(0, shaft.engineEfficiency);
		int engines = 1;
		if (shaft.enginePos != null && shaft.getLevel() != null) {
			var pos = shaft.getBlockPos().subtract(shaft.enginePos);
			if (shaft.getLevel().isLoaded(pos) && shaft.getLevel().getBlockEntity(pos)
				instanceof com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity engine) {
				var tank = engine.getTank();
				if (tank != null) {
					var boiler = tank.boiler;
					int size = tank.getTotalTankSize();
					level = boiler.isPassive(size) ? 0.125 : Math.max(0, Math.min(boiler.activeHeat,
						Math.min(boiler.getMaxHeatLevelForBoilerSize(size), boiler.getMaxHeatLevelForWaterSupply())));
					engines = Math.max(1, boiler.attachedEngines);
				}
			}
		}
		// Pressure scales with boiler level; steam volume is shared by all engines.
		// Level 1, one engine: w0=128, k=4, stall=512 at Create's default capacity.
		return steamCurve(capacity, level, engines, shaft.movementDirection);
	}

	public static Curve windCurve(double generatedSpeed, double capacity, int sails) {
		return new Curve(2 * generatedSpeed, capacity / 16 * Math.max(0, sails) / 128);
	}

	public static Curve steamCurve(double capacity, double boilerLevel, int engines, int direction) {
		int count = Math.max(1, engines);
		double level = Math.max(0, boilerLevel);
		return new Curve(128 * level / count * direction, level == 0 ? 0 : capacity / 256 * count);
	}
}
