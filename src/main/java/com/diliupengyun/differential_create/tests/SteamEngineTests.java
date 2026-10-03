package com.diliupengyun.differential_create.tests;

import java.lang.ref.WeakReference;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.diliupengyun.differential_create.kinetics.NetworkLoad;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real boiler/engine/shaft integration, with heat and water inputs held independently of pumps. */
@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class SteamEngineTests {
	private static final String TEMPLATE = "kineticphysicstests.physics_test";
	private static final BlockPos TANK = new BlockPos(8, 2, 8);

	private static final class Fixture {
		final SteamEngineBlockEntity[] engines;
		final PoweredShaftBlockEntity[] shafts;
		int heat = 2;
		float water = 20;
		boolean passive;
		Fixture(int count) { engines = new SteamEngineBlockEntity[count]; shafts = new PoweredShaftBlockEntity[count]; }
		FluidTankBlockEntity tank() { return engines[0].getTank(); }
		void supply() {
			var tank = tank();
			if (tank == null) return;
			tank.boiler.activeHeat = heat;
			tank.boiler.waterSupply = water;
			// Populate Create's rolling samples as well as its current reading.
			if (water > 0) tank.boiler.createHandler().fill(new net.neoforged.neoforge.fluids.FluidStack(
				net.minecraft.world.level.material.Fluids.WATER, (int) water),
				net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
			tank.boiler.passiveHeat = passive;
			tank.boiler.needsHeatLevelUpdate = false;
		}
	}

	private static Fixture fixture(GameTestHelper h, int count, boolean positive, boolean geared) {
		var bounds = h.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				h.getLevel().setChunkForced(x, z, true);
		for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++)
			h.setBlock(TANK.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
		Fixture fixture = new Fixture(count);
		Direction facing = positive ? Direction.SOUTH : Direction.NORTH;
		BlockPos base = positive ? TANK.south(2) : TANK.north();
		for (int i = 0; i < count; i++) {
			BlockPos engine = geared ? base.above(i) : base.east(i), shaft = engine.relative(facing, 2);
			h.setBlock(engine, AllBlocks.STEAM_ENGINE.getDefaultState()
				.setValue(SteamEngineBlock.FACE, AttachFace.WALL).setValue(SteamEngineBlock.FACING, facing));
			h.setBlock(shaft, AllBlocks.POWERED_SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			fixture.engines[i] = h.getBlockEntity(engine);
			fixture.shafts[i] = h.getBlockEntity(shaft);
			if (geared) h.setBlock(shaft.east(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		}
		h.onEachTick(fixture::supply);
		return fixture;
	}

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 300)
	public static void reverseParallelStartupAndReversal(GameTestHelper h) { parallel(h, false, false); }

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 300)
	public static void positiveParallelStartupAndReversal(GameTestHelper h) { parallel(h, true, false); }

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 300)
	public static void gearedEngineAlignment(GameTestHelper h) { parallel(h, false, true); }

	private static void parallel(GameTestHelper h, boolean positive, boolean geared) {
		Fixture f = fixture(h, 2, positive, geared);
		h.runAfterDelay(100, () -> {
			int lead = f.shafts[0].hasSource() ? 1 : 0, follower = 1 - lead;
			var a = f.shafts[lead];
			var b = f.shafts[follower];
			h.assertTrue(!a.hasSource() && b.hasSource(), "fixture has a lead and following generator");
			int sign = positive ? 1 : -1, ratio = geared ? -1 : 1;
			near(h, 256, SourcePhysics.steamCurve(a).powerThresholdSpeed(), 0, "lead rated calibration");
			h.assertTrue(sign * a.getSpeed() > 256,
				"lead accelerates from rest beyond the constant-power threshold");
			near(h, ratio * a.getSpeed(), b.getSpeed(), 0.1, "follower respects connection direction");
			// Saving and rebuilding must not reintroduce the nominal +16 direction.
			for (var shaft : f.shafts) {
				var saved = shaft.saveWithoutMetadata(h.getLevel().registryAccess());
				shaft.loadWithComponents(saved, h.getLevel().registryAccess());
				NetworkTopology.mark(shaft);
			}
			NetworkTopology.flush();
			// Begin reversal with stored motion, without waiting minutes for the new high-RPM steady state.
			var ratios = ((INetworkDynamics) a.getOrCreateNetwork()).differentialcreate$getRatios();
			double reference = ratios.get(a);
			ratios.forEach((be, r) -> ElasticKinetics.state(be).physicalSpeed = sign * 8 * r / reference);
			var settings = f.engines[lead].getBehaviour(ScrollValueBehaviour.TYPE);
			h.assertTrue(settings.isActive(), "lead direction control is available");
			double before = a.getSpeed();
			settings.setValue(1 - settings.getValue());
			f.engines[lead].tick();
			f.engines[follower].tick();
			h.assertTrue(a.movementDirection == -sign && b.movementDirection == -sign * ratio,
				"the follower accepts a reverse drive command while the rotor still coasts forward");
			near(h, before, a.getSpeed(), 1e-6, "changing direction preserves instantaneous rotor speed");
			h.runAfterDelay(100, () -> {
				h.assertTrue(-sign * a.getSpeed() > 1, "lead reverses its stored motion");
				near(h, ratio * a.getSpeed(), b.getSpeed(), 0.1, "follower completes reversal");
				// Even at standstill, the follower must keep the commanded direction.
				((INetworkDynamics) a.getOrCreateNetwork()).differentialcreate$setSpeed(0);
				f.engines[follower].tick();
				h.assertTrue(b.movementDirection == -sign * ratio, "stalled follower retains upstream direction");
				h.succeed();
			});
		});
	}

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 100)
	public static void unavailableEngineSnapshots(GameTestHelper h) {
		Fixture f = fixture(h, 1, false, false);
		h.runAfterDelay(12, () -> {
			var original = f.shafts[0];
			var saved = original.saveWithoutMetadata(h.getLevel().registryAccess());
			for (BlockPos pos : new BlockPos[]{h.absolutePos(new BlockPos(20, 2, 20)), original.getBlockPos().offset(8192, 0, 8192)}) {
				var clone = (PoweredShaftBlockEntity) original.getType().create(pos, original.getBlockState());
				clone.setLevel(h.getLevel());
				clone.loadWithComponents(saved, h.getLevel().registryAccess());
				BlockPos enginePos = pos.subtract(clone.enginePos);
				boolean loaded = h.getLevel().isLoaded(enginePos);
				double speed = ElasticKinetics.state(clone).physicalSpeed;
				inactive(h, clone, "missing engine snapshot");
				h.assertTrue(!clone.isSource(), "missing engine is not a generator");
				h.assertTrue(h.getLevel().isLoaded(enginePos) == loaded, "reading steam cannot load the engine chunk");
				near(h, speed, ElasticKinetics.state(clone).physicalSpeed, 0, "missing supply retains saved coasting speed");
			}
			// Normal removal must still clear the owned shaft despite the loaded-link overrides.
			h.getLevel().setBlock(f.engines[0].getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
			h.assertTrue(original.enginePos == null && original.engineEfficiency == 0, "removing an engine clears shaft ownership");
			inactive(h, original, "removed engine");
			h.succeed();
		});
	}

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 100)
	public static void missingControllerAndStaleLinks(GameTestHelper h) {
		Fixture f = fixture(h, 1, false, false);
		h.runAfterDelay(12, () -> {
			var tank = f.tank();
			var shaft = f.shafts[0];
			double torque = SourcePhysics.steamCurve(shaft).stallTorque();
			tank.setController(h.absolutePos(new BlockPos(20, 2, 20)));
			h.assertTrue(f.tank() == null, "missing boiler controller is unavailable");
			inactive(h, shaft, "missing controller");
			tank.setController(tank.getBlockPos());
			near(h, torque, SourcePhysics.steamCurve(shaft).stallTorque(), 0, "loaded supply recovers without replacing the shaft");
			var engine = f.engines[0];
			var state = engine.getBlockState();
			h.getLevel().setBlock(engine.getBlockPos(), state.setValue(SteamEngineBlock.FACING, Direction.EAST), 3);
			engine.source = new WeakReference<>(tank);
			inactive(h, shaft, "stale boiler link after rotation");
			h.assertTrue(engine.getTank() == null && engine.getShaft() == null, "rotated engine cannot use old tank/shaft caches");
			h.getLevel().setBlock(engine.getBlockPos(), state, 3);
			near(h, torque, SourcePhysics.steamCurve(shaft).stallTorque(), 0, "restored orientation reconnects supply");
			h.succeed();
		});
	}

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 150)
	public static void boilerSupplyAndCoasting(GameTestHelper h) {
		Fixture f = fixture(h, 1, false, false);
		h.runAfterDelay(12, () -> {
			var tank = f.tank();
			var shaft = f.shafts[0];
			h.assertTrue(tank.getTotalTankSize() == 8 && tank.boiler.attachedEngines == 1, "real 8-block boiler and engine association");
			for (int count = 1; count <= 36; count++) {
				tank.boiler.attachedEngines = count;
				var curve = SourcePhysics.steamCurve(shaft);
				near(h, 256 * Math.min(1, 2.0 / count), curve.powerThresholdSpeed(), 1e-8, "shared steam volume " + count);
				near(h, 0, curve.damping(), 0, "steam has no internal damping " + count);
				near(h, 1024, Math.abs(curve.stallTorque()), 0, "fixed cylinder pressure and displacement " + count);
				near(h, 512, Math.abs(curve.noLoadSpeed()), 0, "governor target independent of shared steam volume " + count);
				near(h, Math.min(1, 2.0 / count), curve.steamVent(-8192), 1e-8, "bypass animation follows actual available supply " + count);
				near(h, 262144 * Math.min(2, count), SourcePhysics.steamPeakPower(1024, tank.boiler, 8), 1e-6, "linear combined power " + count);
			}
			tank.boiler.attachedEngines = 0;
			inactive(h, shaft, "boiler without attached engines");
			tank.boiler.attachedEngines = 1;
			tank.boiler.waterSupply = 0;
			inactive(h, shaft, "no water");
			tank.boiler.waterSupply = 20;
			tank.boiler.activeHeat = 0;
			inactive(h, shaft, "no heat");
			tank.boiler.passiveHeat = true;
			near(h, 32, SourcePhysics.steamCurve(shaft).powerThresholdSpeed(), 0, "passive heat power threshold");
			near(h, 32768, SourcePhysics.steamPeakPower(1024, tank.boiler, 8), 0, "passive heat power budget");
			near(h, .125, SourcePhysics.steamCurve(shaft).steamVent(-8192), 0, "passive heat produces only one eighth of full exhaust");
			near(h, 0, SourcePhysics.steamPeakPower(0, tank.boiler, 8), 0, "disabled capacity contributes no power");
			f.heat = 0;
			f.supply();
			NetworkTopology.mark(shaft);
			NetworkTopology.flush();
			double coasting = shaft.getSpeed();
			h.assertTrue(Math.abs(coasting) > 1, "fixture has stored motion before heat loss");
			h.runAfterDelay(30, () -> {
				inactive(h, shaft, "sustained heat loss");
				h.assertTrue(shaft.movementDirection == -1, "cold engine retains its configured geometric direction");
				near(h, coasting, shaft.getSpeed(), 1e-6, "unloaded engine coasts without drive or artificial braking");
				f.heat = 2;
				f.supply();
				h.runAfterDelay(40, () -> {
					h.assertTrue(shaft.isSource() && Math.abs(shaft.getSpeed()) > Math.abs(coasting), "steam resumes and accelerates the existing rotor");
					h.succeed();
				});
			});
		});
	}

	@GameTest(template = TEMPLATE, batch = "steam_full_boiler", timeoutTicks = 100)
	public static void eighteenEnginesCrossRatedSpeed(GameTestHelper h) {
		var bounds = h.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				h.getLevel().setChunkForced(x, z, true);
		for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) for (int y = 0; y < 8; y++)
			h.setBlock(TANK.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
		Fixture f = new Fixture(18);
		f.heat = 18; f.water = 180;
		for (int i = 0; i < 18; i++) {
			BlockPos engine = TANK.north().offset(i % 3, i / 3, 0), shaft = engine.north(2);
			h.setBlock(engine, AllBlocks.STEAM_ENGINE.getDefaultState()
				.setValue(SteamEngineBlock.FACE, AttachFace.WALL).setValue(SteamEngineBlock.FACING, Direction.NORTH));
			h.setBlock(shaft, AllBlocks.POWERED_SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			f.engines[i] = h.getBlockEntity(engine); f.shafts[i] = h.getBlockEntity(shaft);
		}
		h.onEachTick(f::supply);
		h.runAfterDelay(12, () -> {
			var tank = f.tank();
			h.assertTrue(tank.getTotalTankSize() == 72 && tank.boiler.attachedEngines == 18, "real full boiler has eighteen engines");
			near(h, 4718592, SourcePhysics.steamPeakPower(1024, tank.boiler, 72), 1e-6, "full boiler supplies eighteen rated engines");
			var single = SourcePhysics.steamCurve(1024, 18, 1, 1);
			near(h, 256, single.powerThresholdSpeed(), 0, "a single engine retains its own intake rating");
			near(h, 262144, single.torque(384) * 384, 1e-6, "a single engine cannot consume eighteen engines' steam allocation");
			near(h, 0, single.torque(73728), 0, "governor closes at externally imposed overspeed");
			near(h, 262144, single.ventedPower(73728), 0, "unused engine allocation leaves through the bypass");
			for (var shaft : f.shafts) {
				near(h, 256, SourcePhysics.steamCurve(shaft).powerThresholdSpeed(), 0, "full boiler power threshold");
				near(h, -1024, SourcePhysics.steamCurve(shaft).stallTorque(), 0, "finite cylinder torque");
				ElasticKinetics.state(shaft).physicalSpeed = -256;
			}
			h.runAfterDelay(25, () -> {
				for (var shaft : f.shafts) {
					h.assertTrue(shaft.getSpeed() < -260, "actual rotor continues accelerating beyond the rated speed");
					h.assertTrue(Math.abs(shaft.getSpeed()) < 512, "each engine approaches its own governor target");
					var curve = SourcePhysics.steamCurve(shaft);
					near(h, 262144, curve.torque(shaft.getSpeed()) * shaft.getSpeed() + curve.ventedPower(shaft.getSpeed()), 1e-6,
						"shaft output plus bypass loss equals one allocated share");
				}
				for (int i = 1; i < f.engines.length; i++)
					h.getLevel().setBlock(f.engines[i].getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
				h.runAfterDelay(30, () -> {
					h.assertTrue(tank.boiler.attachedEngines == 1, "removing seventeen real engines updates the boiler allocation");
					near(h, 262144, SourcePhysics.steamPeakPower(1024, tank.boiler, 72), 1e-6,
						"a full boiler with one installed engine exposes only one engine's power");
					h.assertTrue(f.shafts[0].getSpeed() < -256, "removing engines preserves existing rotor motion");
					h.succeed();
				});
			});
		});
	}

	@GameTest(template = TEMPLATE, batch = "steam", timeoutTicks = 250)
	public static void fanLoadReleaseAndSteamReadouts(GameTestHelper h) {
		Fixture f = fixture(h, 1, true, false);
		BlockPos fanPos = TANK.south(4).east();
		h.setBlock(fanPos, AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity fan = h.getBlockEntity(fanPos);
		h.runAfterDelay(40, () -> {
			var shaft = f.shafts[0];
			h.assertTrue(shaft.getSpeed() > 0 && fan.getSpeed() > 0, "steam starts the real connected fan");
			// Solve the fan's power demand against the full supply or the governor's remaining admission.
			double drag = fan.calculateStressApplied() / (PhysicsTuning.FAN_ANCHOR_RPM * PhysicsTuning.FAN_ANCHOR_RPM);
			double equilibrium = Math.cbrt(262144 / drag);
			if (equilibrium > PhysicsTuning.STEAM_GOVERNOR_RPM - PhysicsTuning.STEAM_GOVERNOR_RANGE) {
				double low = PhysicsTuning.STEAM_GOVERNOR_RPM - PhysicsTuning.STEAM_GOVERNOR_RANGE;
				double high = PhysicsTuning.STEAM_GOVERNOR_RPM;
				for (int i = 0; i < 60; i++) {
					double mid = (low + high) / 2;
					if (drag * mid * mid * mid > 262144 * (PhysicsTuning.STEAM_GOVERNOR_RPM - mid) / PhysicsTuning.STEAM_GOVERNOR_RANGE)
						high = mid;
					else low = mid;
				}
				equilibrium = (low + high) / 2;
			}
			double balancedSpeed = equilibrium;
			var ratios = ((INetworkDynamics) shaft.getOrCreateNetwork()).differentialcreate$getRatios();
			double reference = ratios.get(shaft);
			ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = balancedSpeed * ratio / reference);
			h.runAfterDelay(30, () -> {
				near(h, balancedSpeed, shaft.getSpeed(), .05, "external fan drag determines the real engine equilibrium");
				readouts(h, shaft, balancedSpeed > 448 ? "steam_governing" : "steam_driven", false);
				double actualSpeed = NetworkDynamics.preciseSpeed(shaft);
				double fraction = drag * Math.pow(Math.abs(NetworkDynamics.preciseSpeed(fan)), 3)
					/ (SourcePhysics.steamCurve(shaft).torque(actualSpeed) * actualSpeed);
				near(h, 1, fraction, .001, "fan demand remains near the steam power budget during residual oscillation");
				LoadFactorTests.fraction(h, shaft, fraction, "fan demand follows its actual speed during elastic oscillation");
				LoadFactorTests.tooltip(h, shaft, "100%", net.minecraft.ChatFormatting.YELLOW);
				double fanSpeed = NetworkDynamics.preciseSpeed(fan);
				double weakSpeed = Math.nextDown(PhysicsTuning.STEAM_GOVERNOR_RPM);
				ElasticKinetics.state(shaft).physicalSpeed = weakSpeed;
				ElasticKinetics.state(fan).physicalSpeed = weakSpeed * ratios.get(fan) / reference;
				var weak = NetworkLoad.of(shaft);
				h.assertTrue(weak.status() == LoadFactor.Status.AVAILABLE && Double.isFinite(weak.fraction()) && weak.fraction() > 1e10,
					"genuine tiny governor admission retains a finite load deficit rather than being erased as roundoff: " + weak);
				ElasticKinetics.state(shaft).physicalSpeed = actualSpeed;
				ElasticKinetics.state(fan).physicalSpeed = fanSpeed;
				h.setBlock(fanPos, Blocks.AIR);
				NetworkTopology.flush();
				double unloaded = shaft.getSpeed();
				h.runAfterDelay(40, () -> {
					h.assertTrue(shaft.getSpeed() > unloaded + 1 && shaft.getSpeed() <= 512.1,
						"removing the fan restores acceleration towards the governor target");
					ElasticKinetics.state(shaft).physicalSpeed = 8192;
					readouts(h, shaft, "steam_governing", true);
					near(h, 1, SourcePhysics.steamCurve(shaft).steamVent(8192), 0, "full supply vents fully above target");
					LoadFactorTests.fraction(h, shaft, 0, "removing the fan removes sustained demand");
					f.heat = 0; f.passive = true; f.supply();
					near(h, 32768, SourcePhysics.steamCurve(shaft).maximumPower(), 0, "passive heat reduces the shared power budget");
					near(h, 8192, shaft.getSpeed(), 0, "supply loss never clamps existing speed");
					h.runAfterDelay(40, () -> {
						near(h, 8192, NetworkDynamics.preciseSpeed(shaft), 1e-6,
							"closed admission preserves overspeed without adding work or braking");
						near(h, .125, SourcePhysics.steamCurve(shaft).steamVent(8192), 0, "weak supply yields a weaker bypass plume");
						f.passive = false; f.supply();
						readouts(h, shaft, "no_steam", true);
						LoadFactorTests.tooltip(h, shaft, "gui.differentialcreate.goggles.load_no_power", net.minecraft.ChatFormatting.GRAY);
						h.succeed();
					});
				});
			});
		});
	}

	@GameTest(template = TEMPLATE, batch = "steam_natural_start", timeoutTicks = 1400)
	public static void naturalStartupPastRatedSpeed(GameTestHelper h) {
		Fixture f = fixture(h, 1, true, false);
		f.heat = 1; f.water = 10;
		h.runAfterDelay(12, () -> {
			var shaft = f.shafts[0];
			// Start from rest once. All later motion comes from normal server physics ticks.
			ElasticKinetics.state(shaft).physicalSpeed = 0;
			LoadFactorTests.fraction(h, shaft, 0, "unloaded steam startup is zero load");
			h.runAfterDelay(15, () -> {
				near(h, 128, shaft.getSpeed(), 1, "natural constant-torque acceleration before the 256 RPM threshold");
				readouts(h, shaft, "steam_driven", false);
				LoadFactorTests.fraction(h, shaft, 0, "steam acceleration does not count as machine load");
			});
			h.runAfterDelay(1200, () -> {
				double expected = PhysicsTuning.STEAM_GOVERNOR_RPM;
				near(h, expected, NetworkDynamics.preciseSpeed(shaft), 1e-6, "natural acceleration settles at the governor target");
				readouts(h, shaft, "steam_governing", true);
				near(h, 1, SourcePhysics.steamCurve(shaft).steamVent(shaft.getSpeed()), 1e-8, "unloaded equilibrium bypasses the full steam allocation");
				DifferentialCreate.LOGGER.info("STEAM_NATURAL ticks=1200 speed={} expected={} power={}",
					NetworkDynamics.preciseSpeed(shaft), expected, SourcePhysics.steamCurve(shaft).torque(shaft.getSpeed()) * shaft.getSpeed());
				f.heat = 0; f.supply();
				NetworkTopology.mark(shaft); NetworkTopology.flush();
				double coast = NetworkDynamics.preciseSpeed(shaft);
				h.runAfterDelay(30, () -> {
					near(h, coast, NetworkDynamics.preciseSpeed(shaft), 1e-8, "high-speed rotor coasts after cutting steam");
					h.succeed();
				});
			});
		});
	}

	private static void readouts(GameTestHelper h, PoweredShaftBlockEntity shaft, String mode, boolean energy) {
		var tooltip = new java.util.ArrayList<Component>();
		KineticGoggleInfo.append(shaft, tooltip, false);
		h.assertTrue(hasKey(tooltip, "gui.differentialcreate.goggles.inertia")
			&& hasKey(tooltip, "gui.differentialcreate.goggles.speed")
			&& hasKey(tooltip, "gui.differentialcreate.goggles.torque"), "steam uses the shared dynamics fields");
		h.assertTrue(hasKey(tooltip, "gui.differentialcreate.goggles." + mode), "dynamics view shows the current drive state: " + mode);
		h.assertTrue(!hasKey(tooltip, "gui.differentialcreate.goggles.no_load_speed")
			&& hasKey(tooltip, "gui.differentialcreate.goggles.load_factor"), "steam shows network demand/supply without a fictitious no-load speed");
		h.assertTrue(hasKey(tooltip, "gui.differentialcreate.goggles.steam_governor_speed")
			&& hasKey(tooltip, "gui.differentialcreate.goggles.steam_venting"), "governor target and bypass fraction are visible");
		tooltip.clear();
		KineticGoggleInfo.append(shaft, tooltip, true);
		if (energy) {
			h.assertTrue(hasKey(tooltip, "gui.differentialcreate.goggles.local_energy")
				&& hasKey(tooltip, "gui.differentialcreate.goggles.energy")
				&& hasKey(tooltip, "gui.differentialcreate.goggles.local_elastic_energy")
				&& hasKey(tooltip, "gui.differentialcreate.goggles.elastic_energy"), "steam uses all four shared energy fields");
			h.assertTrue(hasKey(tooltip, "gui.differentialcreate.goggles.output_power"), "high-speed steam never becomes fictitious braking");
			h.assertTrue(hasKey(tooltip, "gui.differentialcreate.goggles.steam_vent_power"), "energy view reports bypass loss separately");
		}
	}
	private static boolean hasKey(java.util.List<Component> lines, String key) {
		return lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents text && text.getKey().equals(key));
	}

	private static void inactive(GameTestHelper h, PoweredShaftBlockEntity shaft, String message) {
		var curve = SourcePhysics.steamCurve(shaft);
		near(h, 0, curve.noLoadSpeed(), 0, message + " no-load speed");
		near(h, 0, curve.damping(), 0, message + " damping");
		near(h, 0, shaft.getGeneratedSpeed(), 0, message + " source command");
	}
	private static void near(GameTestHelper h, double expected, double actual, double tolerance, String message) {
		h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
			message + ": expected " + expected + ", got " + actual);
	}
}
