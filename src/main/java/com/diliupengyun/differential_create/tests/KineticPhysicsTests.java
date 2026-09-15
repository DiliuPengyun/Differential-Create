package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.ClutchControl;
import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.diliupengyun.differential_create.kinetics.IClutchDynamics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.NetworkPhysics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Physics regression suite for the continuous kinetic model (DESIGN.md
 * §2/4/5/6). Sources are real water wheels (k = 4 su/RPM², ω0 = 16 RPM, stall
 * torque 64 su/RPM, J = 0.15 su/RPM³ with Create's default stress config);
 * loads are working presses (impact 8, J = 0.01 each).
 * <p>
 * All scenarios share one structure: the GameTest runner places the
 * structures of one batch directly adjacent, so separate test instances
 * would merge into one network. Assertions are gated on the physical state
 * (settle conditions), not fixed tick times, so they survive inertia
 * retuning.
 * <p>
 * The wheels' flow score is pinned: the lazy flow detection would otherwise
 * clear it before placed water spreads (killing the network mid-test), and
 * flowing water inside the structure makes the GameTest map display churn
 * thousands of log lines per tick. The physics under test do not depend on
 * the flow value's origin.
 */
@EventBusSubscriber(modid = DifferentialCreate.MODID)
public final class KineticPhysicsTests {

	private static final String TEMPLATE = "physics_test";
	private static final int TOTAL_CHECKS = 14;

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "ground_press_single", timeoutTicks = 1600)
	public static void groundPressingSingle(GameTestHelper helper) {
		forceImpactChunks(helper);
		GroundPressingTests.single(helper);
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "ground_press_bulk", timeoutTicks = 500)
	public static void groundPressingBulk(GameTestHelper helper) {
		forceImpactChunks(helper);
		GroundPressingTests.bulk(helper);
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "ground_press_stall", timeoutTicks = 1200)
	public static void groundPressingStall(GameTestHelper helper) {
		forceImpactChunks(helper);
		GroundPressingTests.stall(helper);
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "collision", timeoutTicks = 1000)
	public static void collisionRegression(GameTestHelper helper) {
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
		var motor = placeWheel(helper, new BlockPos(2, 2, 2), 1, null);
		var reverse = placeWheel(helper, new BlockPos(6, 2, 2), -1, null);
		BlockPos clutchPos = new BlockPos(4, 2, 2);
		helper.setBlock(clutchPos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
		helper.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)
			.setValue(BlockStateProperties.POWERED, true));
		helper.setBlock(new BlockPos(3, 2, 2), shaftX());
		helper.setBlock(new BlockPos(5, 2, 2), shaftX());
		var active = placeWheel(helper, new BlockPos(2, 2, 12), 1, null);
		boolean[] inactivePinned = {true};
		var inactive = placeWheel(helper, new BlockPos(6, 2, 12), 0, inactivePinned);
		for (int x = 3; x <= 5; x++) helper.setBlock(new BlockPos(x, 2, 12), shaftX());
		int[] done = {0};
		boolean[] engaged = {false}, activated = {false};
		helper.onEachTick(() -> {
			if (!engaged[0] && closeTo(speedOf(motor), 16, 0.2) && closeTo(speedOf(reverse), 16, 0.2)) {
				engaged[0] = true;
				helper.setBlock(clutchPos.above(), Blocks.AIR.defaultBlockState());
				helper.runAfterDelay(5, () -> {
					if (helper.getBlockState(clutchPos).isAir() || motor.isRemoved() || reverse.isRemoved())
						helper.fail("full-pressure engagement destroyed a block instead of slipping");
					ClutchBlockEntity clutch = (ClutchBlockEntity) helper.getBlockEntity(clutchPos);
					IClutchDynamics state = (IClutchDynamics) clutch;
					assertClutchSignal(helper, clutch, 0);
					if (state.differentialcreate$isLocked() || motor.getOrCreateNetwork() == reverse.getOrCreateNetwork()
						|| Math.abs(state.differentialcreate$getLastCouplingTorque()) < 31.9
						|| motor.getSpeed() * reverse.getSpeed() >= 0)
						helper.fail("opposing rotors did not retain independent speeds under limited clutch torque");
					done[0] |= 1;
				});
			}
			if (!activated[0] && closeTo(speedOf(active), 16, 0.2) && active.getOrCreateNetwork() == inactive.getOrCreateNetwork()) {
				activated[0] = true;
				inactivePinned[0] = false;
				inactive.setFlowScoreAndUpdate(-1);
				if (!inactive.isRemoved() || active.isRemoved()) helper.fail("reverse source activation did not break only the newly activated source");
				done[0] |= 2;
			}
			if (done[0] == 3) helper.succeed();
		});
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "clutch_impact", timeoutTicks = 1000)
	public static void lockedClutchImpactRegression(GameTestHelper helper) {
		forceImpactChunks(helper);
		int[] done = {0};
		// Exercise both rotation signs, both reference faces, and hot attachment
		// at either end. The contact shaft is several blocks from the clutch.
		for (int scenario = 0; scenario < 8; scenario++) {
			int x = 2 + 12 * (scenario % 2), z = 2 + 6 * (scenario / 2);
			boolean motorLeft = (scenario & 4) == 0, reverseReference = (scenario & 2) != 0;
			int sign = (scenario & 1) == 0 ? 1 : -1;
			var motor = placeWheel(helper, new BlockPos(x + (motorLeft ? 0 : 6), 2, z), sign, null);
			var driven = placeWheel(helper, new BlockPos(x + (motorLeft ? 6 : 0), 2, z), 0, null);
			BlockPos clutchPos = new BlockPos(x + 3, 2, z), bridge = new BlockPos(x + (motorLeft ? 5 : 1), 2, z);
			ClutchBlockEntity clutch = placeImpactClutch(helper, clutchPos,
				motorLeft != reverseReference ? Direction.WEST : Direction.EAST);
			for (int dx = 1; dx <= 5; dx++) {
				BlockPos pos = new BlockPos(x + dx, 2, z);
				if (!pos.equals(clutchPos) && !pos.equals(bridge)) helper.setBlock(pos, shaftX());
			}
			int[] stage = {0};
			helper.onEachTick(() -> {
				IClutchDynamics state = (IClutchDynamics) clutch;
				if (stage[0] == 0 && state.differentialcreate$isLocked()
					&& motor.getOrCreateNetwork() == clutch.getOrCreateNetwork() && driven.hasNetwork()) {
					stage[0] = 1;
					setLocalSpeed(motor, 16 * sign);
					setLocalSpeed(driven, 0);
					double before = networkEnergy(motor) + networkEnergy(driven);
					helper.setBlock(bridge, shaftX());
					NetworkTopology.mark((KineticBlockEntity) helper.getBlockEntity(bridge));
					NetworkTopology.flush();
					if (clutch.isRemoved() || motor.isRemoved() || driven.isRemoved() || helper.getBlockState(bridge).isAir())
						helper.fail("remote hot attachment broke a block across a locked clutch");
					if (state.differentialcreate$isLocked() || motor.getOrCreateNetwork() == driven.getOrCreateNetwork())
						helper.fail("locked clutch failed to release before the impact merged its rotors");
					assertNear(helper, 16 * sign, motor.getSpeed(), 1e-5, "driver retains its pre-impact speed");
					assertNear(helper, 0, driven.getSpeed(), 1e-5, "new rotor starts without an instantaneous impulse");
					assertNear(helper, before, networkEnergy(motor) + networkEnergy(driven), 1e-5,
						"splitting for clutch slip preserves kinetic energy before friction acts");
					assertMembersConsistent(motor, "driver after impact split");
					assertMembersConsistent(driven, "driven side after impact split");
				} else if (stage[0] == 1) {
					if (clutch.isRemoved() || helper.getBlockState(bridge).isAir()) helper.fail("slipping damaged the clutch or contact");
					assertClutchSignal(helper, clutch, 0);
					if (state.differentialcreate$isLocked() && motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) {
						assertNear(helper, motor.getSpeed(), driven.getSpeed(), 1e-4, "rotors synchronize before relocking");
						if (driven.getSpeed() * sign <= 0) helper.fail("friction did not start the new rotor");
						stage[0] = 2;
						done[0]++;
					}
				}
			});
		}
		helper.onEachTick(() -> { if (done[0] == 8) helper.succeed(); });
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "clutch_impact_boundaries", timeoutTicks = 1000)
	public static void clutchImpactBoundaryRegression(GameTestHelper helper) {
		forceImpactChunks(helper);
		int[] done = {0};
		for (int scenario = 0; scenario < 4; scenario++) {
			int kind = scenario, z = 2 + 6 * scenario;
			boolean across = kind == 1 || kind == 2;
			var motor = placeWheel(helper, new BlockPos(across ? 2 : 4, 2, z), 1, null);
			boolean[] pinned = {true};
			var driven = placeWheel(helper, new BlockPos(8, 2, z), 0, pinned);
			BlockPos clutchPos = new BlockPos(across ? 4 : 2, 2, z), bridge = new BlockPos(7, 2, z);
			ClutchBlockEntity clutch = placeImpactClutch(helper, clutchPos, across ? Direction.WEST : Direction.EAST);
			for (int x = 1; x <= 7; x++) {
				BlockPos pos = new BlockPos(x, 2, z);
				if (x != (across ? 2 : 4) && !pos.equals(clutchPos) && !(kind < 2 && pos.equals(bridge)))
					helper.setBlock(pos, shaftX());
			}
			boolean[] checked = {false};
			helper.runAfterDelay(950, () -> {
				if (!checked[0]) helper.fail("clutch impact fixture did not become ready: scenario " + kind);
			});
			helper.onEachTick(() -> {
				if (checked[0] || !((IClutchDynamics) clutch).differentialcreate$isLocked() || !driven.hasNetwork()) return;
				if (kind >= 2 && motor.getOrCreateNetwork() != driven.getOrCreateNetwork()) return;
				checked[0] = true;
				if (kind == 1) {
					// Add a same-ratio rigid bypass after the clutch has locked.
					for (int x : new int[]{3, 5})
						for (int dz : new int[]{0, 1}) helper.setBlock(new BlockPos(x, 2, z + dz),
							AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
					helper.setBlock(new BlockPos(4, 2, z + 1), shaftX());
					NetworkTopology.mark((KineticBlockEntity) helper.getBlockEntity(new BlockPos(3, 2, z)));
					NetworkTopology.flush();
				}
				setLocalSpeed(motor, 16);
				if (kind < 2) {
					setLocalSpeed(driven, 0);
					helper.setBlock(bridge, shaftX());
					NetworkTopology.mark((KineticBlockEntity) helper.getBlockEntity(bridge));
					NetworkTopology.flush();
					if (!helper.getBlockState(bridge).isAir())
						helper.fail(kind == 0 ? "same-side impact incorrectly received clutch protection"
							: "rigid bypass incorrectly received clutch protection");
					if (driven.isRemoved() || motor.getOrCreateNetwork() == driven.getOrCreateNetwork())
						helper.fail("hard impact did not break only the contact shaft");
				} else {
					pinned[0] = false;
					driven.setFlowScoreAndUpdate(-1);
					NetworkTopology.flush();
					if (kind == 2) {
						if (driven.isRemoved() || ((IClutchDynamics) clutch).differentialcreate$isLocked()
							|| motor.getOrCreateNetwork() == driven.getOrCreateNetwork())
							helper.fail("opposite-side source activation did not release the separating clutch");
						assertNear(helper, 16, motor.getSpeed(), 1e-5, "source release preserves driver speed");
						assertNear(helper, 16, driven.getSpeed(), 1e-5, "source command does not overwrite shaft speed");
					} else if (!driven.isRemoved()) helper.fail("same-side source activation bypassed the hard impact rule");
				}
				if (motor.isRemoved() || clutch.isRemoved()) helper.fail("impact destroyed an unrelated motor or clutch");
				done[0]++;
			});
		}
		helper.onEachTick(() -> { if (done[0] == 4) helper.succeed(); });
	}

	private static void forceImpactChunks(GameTestHelper helper) {
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
	}

	private static ClutchBlockEntity placeImpactClutch(GameTestHelper helper, BlockPos pos, Direction input) {
		helper.setBlock(pos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		ClutchBlockEntity clutch = (ClutchBlockEntity) helper.getBlockEntity(pos);
		clutch.source = helper.absolutePos(pos.relative(input));
		return clutch;
	}

	private static void setLocalSpeed(KineticBlockEntity member, float speed) {
		INetworkDynamics dynamics = (INetworkDynamics) member.getOrCreateNetwork();
		dynamics.differentialcreate$setSpeed(speed / dynamics.differentialcreate$getRatios().get(member));
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "free_clutch_output", timeoutTicks = 700)
	public static void freeClutchOutputRegression(GameTestHelper helper) {
		forceImpactChunks(helper);
		int[] done = {0};
		for (int scenario = 0; scenario < 4; scenario++) {
			int z = 2 + 6 * scenario, sign = (scenario & 1) == 0 ? 1 : -1;
			boolean fromLeft = scenario < 2;
			Direction input = fromLeft ? Direction.WEST : Direction.EAST;
			var motor = placeWheel(helper, new BlockPos(fromLeft ? 2 : 8, 2, z), sign, null);
			BlockPos clutchPos = new BlockPos(fromLeft ? 4 : 6, 2, z);
			helper.setBlock(clutchPos.relative(input), shaftX());
			ClutchBlockEntity clutch = placeImpactClutch(helper, clutchPos, input);
			IClutchDynamics state = (IClutchDynamics) clutch;
			int[] stage = {0};
			helper.onEachTick(() -> {
				if (stage[0] == 0 && state.differentialcreate$isLocked() && closeTo(speedOf(motor), 16, 0.1)) {
					stage[0] = 1;
					assertNear(helper, 0, InertiaRegistry.getInertia(clutch), 0, "clutch remains an inertialess transmission");
					assertNear(helper, motor.getSpeed(), state.differentialcreate$getFreeOutputSpeed(), 0.01,
						"engaged bare output follows the input");
					setClutchSignal(helper, clutchPos, 15);
					helper.runAfterDelay(2, () -> {
						assertClutchSignal(helper, clutch, 15);
						if (state.differentialcreate$isLocked()) helper.fail("bare clutch failed to release");
						float released = state.differentialcreate$getFreeOutputSpeed();
						assertNear(helper, motor.getSpeed(), released, 0.01, "release retains nonzero output RPM");
						var tag = clutch.saveWithoutMetadata(helper.getLevel().registryAccess());
						var restored = (ClutchBlockEntity) clutch.getType().create(clutch.getBlockPos(), clutch.getBlockState());
						restored.setLevel(helper.getLevel());
						restored.loadWithComponents(tag, helper.getLevel().registryAccess());
						assertNear(helper, released, ((IClutchDynamics) restored).differentialcreate$getFreeOutputSpeed(), 0,
							"free output RPM survives NBT round trip");
						assertNear(helper, released, clutch.getUpdateTag(helper.getLevel().registryAccess()).getFloat("DCClutchFreeOutputSpeed"),
							0, "tracking packet includes free output RPM");
						setLocalSpeed(motor, -4 * sign);
						helper.runAfterDelay(8, () -> {
							assertNear(helper, released, state.differentialcreate$getFreeOutputSpeed(), 0,
								"free output is independent of input speed changes");
							if (Math.abs(motor.getSpeed() - released) < 8) helper.fail("fixture did not change input RPM");
							setClutchSignal(helper, clutchPos, 14);
							stage[0] = 2;
						});
					});
				} else if (stage[0] == 2 && state.differentialcreate$isLocked()) {
					stage[0] = 3;
					assertClutchSignal(helper, clutch, 14);
					assertNear(helper, motor.getSpeed(), state.differentialcreate$getFreeOutputSpeed(), 0.3,
						"weak pressure synchronizes and relocks the unloaded port");
					setClutchSignal(helper, clutchPos, 15);
					helper.runAfterDelay(2, () -> {
						BlockPos flywheelPos = clutchPos.relative(input.getOpposite());
						helper.setBlock(flywheelPos, AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
						KineticBlockEntity flywheel = (KineticBlockEntity) helper.getBlockEntity(flywheelPos);
						NetworkTopology.mark(flywheel);
						NetworkTopology.flush();
						assertNear(helper, 0.5, InertiaRegistry.getInertia(flywheel), 0, "flywheel has its explicit inertia");
						INetworkDynamics dynamics = (INetworkDynamics) flywheel.getOrCreateNetwork();
						float ratio = dynamics.differentialcreate$getRatios().get(flywheel);
						assertNear(helper, 0.5, NetworkPhysics.aggregate(flywheel.getOrCreateNetwork(),
							dynamics.differentialcreate$getRatios()).j() / (ratio * ratio), 1e-6,
							"flywheel inertia participates in the physical network");
						if (ClutchPhysics.output(clutch) != flywheel) helper.fail("attached flywheel was not the clutch output");
						helper.runAfterDelay(2, () -> {
							assertNear(helper, 0, flywheel.getSpeed(), 0, "open clutch does not spin a newly attached flywheel for free");
							assertNear(helper, flywheel.getSpeed(), state.differentialcreate$getFreeOutputSpeed(), 0,
								"attached output replaces the saved free speed");
							done[0]++;
						});
					});
				}
			});
		}
		helper.onEachTick(() -> { if (done[0] == 4) helper.succeed(); });
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "machines", timeoutTicks = 1800)
	public static void machineRegression(GameTestHelper helper) {
		prepareWorkingLoads(helper);
		var windSmall = com.diliupengyun.differential_create.kinetics.SourcePhysics.windCurve(8, 512, 64);
		var windLarge = com.diliupengyun.differential_create.kinetics.SourcePhysics.windCurve(16, 512, 128);
		if (windLarge.damping() != 2 * windSmall.damping() || windLarge.stallTorque() != 4 * windSmall.stallTorque())
			helper.fail("windmill sail area did not increase its torque characteristic");
		for (int count = 1; count <= 36; count++) {
			var steam = com.diliupengyun.differential_create.kinetics.SourcePhysics.steamCurve(1024, 3, count, 1);
			if (Math.abs(steam.noLoadSpeed() - 384.0 / count) > 1e-9 || Math.abs(steam.stallTorque() - 1536) > 1e-9)
				helper.fail("steam volume sharing or boiler pressure calibration failed for " + count + " engines");
		}
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
		BlockPos millPos = new BlockPos(3, 2, 3), mixerPos = new BlockPos(8, 2, 3);
		helper.setBlock(millPos, AllBlocks.MILLSTONE.getDefaultState());
		var mill = (com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity) helper.getBlockEntity(millPos);
		mill.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT));
		mill.timer = 100; // A partially completed, real milling recipe.
		helper.setBlock(mixerPos.below(2), AllBlocks.BASIN.getDefaultState());
		helper.setBlock(mixerPos, AllBlocks.MECHANICAL_MIXER.getDefaultState());
		var mixer = (com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity) helper.getBlockEntity(mixerPos);
		var basin = (com.simibubi.create.content.processing.basin.BasinBlockEntity) helper.getBlockEntity(mixerPos.below(2));
		basin.getInputInventory().setStackInSlot(0, new ItemStack(Items.ANDESITE));
		basin.getInputInventory().setStackInSlot(1, new ItemStack(Items.IRON_NUGGET));
		mixer.running = true;
		mixer.runningTicks = 10;
		BlockPos rscPos = new BlockPos(14, 2, 12), fanRscPos = new BlockPos(4, 2, 20);
		var rscMotor = controllerFixture(helper, rscPos, 32, workingPressX());
		var fanMotor = controllerFixture(helper, fanRscPos, 256,
			AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		var rsc = (com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity) helper.getBlockEntity(rscPos);
		KineticBlockEntity rscOutput = (KineticBlockEntity) helper.getBlockEntity(rscPos.east(2));
		int[] done = {0};
		boolean[] changingRatio = {false};
		helper.runAfterDelay(5, () -> {
			((INetworkDynamics) mill.getOrCreateNetwork()).differentialcreate$setSpeed(0.5f);
			helper.runAfterDelay(20, () -> {
				if (mill.timer != 100) helper.fail("sub-RPM milling still received free integer progress: " + mill.timer);
				var tag = mill.saveWithoutMetadata(helper.getLevel().registryAccess());
				if (tag.getDouble("DCWorkFraction") <= 0) helper.fail("low-speed milling lost its fractional work");
				var clone = (com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity) mill.getType().create(millPos, mill.getBlockState());
				clone.setLevel(helper.getLevel());
				clone.loadWithComponents(tag, helper.getLevel().registryAccess());
				if (clone.timer != 100 || clone.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble("DCWorkFraction")
					!= tag.getDouble("DCWorkFraction")) helper.fail("milling progress changed on NBT round trip");
				if (mixer.runningTicks != 10 || !mixer.running) helper.fail("stalled mixer retracted or reset its travel");
				((INetworkDynamics) mill.getOrCreateNetwork()).differentialcreate$setSpeed(128);
				((INetworkDynamics) mixer.getOrCreateNetwork()).differentialcreate$setSpeed(128);
				done[0] |= 1;
			});
		});
		helper.onEachTick(() -> {
			if (helper.getTick() % 200 == 0)
				DifferentialCreate.LOGGER.info("MACHINES tick={} done={} mill={} mixer={}/{} rsc={} -> {} fan={}",
					helper.getTick(), Integer.toHexString(done[0]), mill.timer, mixer.runningTicks, mixer.processingTicks,
					rscMotor.getSpeed(), rscOutput.getSpeed(), fanMotor.getSpeed());
			if (mill.outputInv.getStackInSlot(0).is(com.simibubi.create.AllItems.WHEAT_FLOUR.get())) done[0] |= 2;
			for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++)
				if (basin.getOutputInventory().getStackInSlot(slot).is(com.simibubi.create.AllItems.ANDESITE_ALLOY.get())) done[0] |= 4;
			if (!changingRatio[0] && closeTo(speedOf(rscMotor), 12, 0.2)) {
				changingRatio[0] = true;
				if (Math.abs(rscOutput.getSpeed() - 2 * rscMotor.getSpeed()) > 0.001) helper.fail("RSC 2x setting is not a physical ratio");
				if (!(rsc.targetSpeed instanceof com.diliupengyun.differential_create.kinetics.RatioValueBehaviour))
					helper.fail("RSC still uses the RPM input control");
				done[0] |= 8;
				rsc.targetSpeed.setValue(0);
				helper.runAfterDelay(80, () -> {
					if (rscMotor.getOrCreateNetwork() == rscOutput.getOrCreateNetwork() || Math.abs(rscOutput.getSpeed()) > 0.001)
						helper.fail("RSC zero setting did not disconnect its output");
					done[0] |= 16;
					rsc.targetSpeed.setValue(-8);
				});
			}
			if ((done[0] & 16) != 0 && rscMotor.getOrCreateNetwork() == rscOutput.getOrCreateNetwork()
				&& closeTo(speedOf(rscMotor), 15, 0.2)) {
				if (Math.abs(rscOutput.getSpeed() + 0.5 * rscMotor.getSpeed()) > 0.001) helper.fail("negative RSC ratio did not reverse and reduce the output");
				done[0] |= 32;
			}
			// Fan impact=2 at 64 RPM; r=16 refers C=2*16^3/64^2=2.
			double fanEquilibrium = (-4 + Math.sqrt(16 + 4 * 2 * 64)) / 4;
			if (closeTo(speedOf(fanMotor), fanEquilibrium, 0.03)) done[0] |= 64;
			if (done[0] == 127) helper.succeed();
		});
	}

	private static WaterWheelBlockEntity controllerFixture(GameTestHelper helper, BlockPos pos, int ratio, BlockState load) {
		helper.setBlock(pos, AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
		((com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity) helper.getBlockEntity(pos)).targetSpeed.setValue(ratio);
		helper.setBlock(pos.above(), AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		helper.setBlock(pos.above().north(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		BlockPos wheelPos = pos.above().north(2);
		helper.setBlock(wheelPos, AllBlocks.WATER_WHEEL.getDefaultState().setValue(WaterWheelBlock.FACING, Direction.SOUTH));
		var wheel = (WaterWheelBlockEntity) helper.getBlockEntity(wheelPos);
		wheel.setFlowScoreAndUpdate(1);
		helper.onEachTick(() -> { if (!wheel.isRemoved() && wheel.flowScore != 1) wheel.setFlowScoreAndUpdate(1); });
		helper.setBlock(pos.east(), shaftX());
		helper.setBlock(pos.east(2), load);
		if (helper.getBlockEntity(pos.east(2)) instanceof MechanicalPressBlockEntity press) keepWorking(helper, press);
		return wheel;
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "topology", timeoutTicks = 1800)
	public static void topologyRegression(GameTestHelper helper) {
		prepareWorkingLoads(helper);
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
		var driven = placeWheel(helper, new BlockPos(6, 2, 2), 0, null);
		var motor = placeWheel(helper, new BlockPos(2, 2, 2), 1, null);
		helper.setBlock(new BlockPos(3, 2, 2), shaftX());
		BlockPos clutchPos = new BlockPos(4, 2, 2);
		helper.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(new BlockPos(5, 2, 2), shaftX());
		placeLoad(helper, new BlockPos(7, 2, 2));

		var splitMotor = placeWheel(helper, new BlockPos(2, 2, 8), 1, null);
		var splitLoad = placeWheel(helper, new BlockPos(6, 2, 8), 0, null);
		for (int x = 3; x <= 5; x++) helper.setBlock(new BlockPos(x, 2, 8), shaftX());
		placeLoad(helper, new BlockPos(7, 2, 8));
		var reverseA = placeWheel(helper, new BlockPos(14, 2, 2), 1, null);
		var reverseB = placeWheel(helper, new BlockPos(18, 2, 2), -1, null);
		helper.setBlock(new BlockPos(15, 2, 2), shaftX());
		helper.setBlock(new BlockPos(16, 2, 2), shaftX());
		var geared = placeWheel(helper, new BlockPos(14, 2, 8), 1, null);
		helper.setBlock(new BlockPos(15, 2, 8), shaftX());
		helper.setBlock(new BlockPos(16, 2, 8), AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		BlockPos smallPos = new BlockPos(16, 3, 9);
		helper.setBlock(smallPos, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(new BlockPos(17, 3, 9), shaftX());
		placeLoad(helper, new BlockPos(18, 3, 9));
		var hotMotor = placeWheel(helper, new BlockPos(14, 2, 14), 1, null);
		helper.setBlock(new BlockPos(15, 2, 14), shaftX());
		int[] done = {0};
		boolean[] disconnected = {false}, split = {false}, reversed = {false}, hot = {false};
		helper.onEachTick(() -> {
			if (helper.getTick() % 200 == 0)
				DifferentialCreate.LOGGER.info("TOPOLOGY tick={} done={} clutch={} -> {} locked={}", helper.getTick(),
					Integer.toHexString(done[0]), motor.getSpeed(), driven.getSpeed(),
					((com.diliupengyun.differential_create.kinetics.IClutchDynamics) helper.getBlockEntity(clutchPos)).differentialcreate$isLocked());
			if (!disconnected[0] && closeTo(speedOf(motor), 14, 0.3) && closeTo(speedOf(driven), 14, 0.3)
				&& motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) {
				disconnected[0] = true;
				done[0] |= 1;
				helper.setBlock(clutchPos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
				helper.runAfterDelay(20, () -> {
					if (motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) helper.fail("powered clutch did not split the networks");
					if (motor.getSpeed() <= driven.getSpeed()) helper.fail("disconnected clutch still shared its load");
					done[0] |= 2;
					helper.setBlock(clutchPos.above(), Blocks.AIR.defaultBlockState());
				});
			}
			if ((done[0] & 2) != 0 && (done[0] & 4) == 0 && motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) done[0] |= 4;
			if (!split[0] && closeTo(speedOf(splitMotor), 14, 0.2)) {
				split[0] = true;
				helper.setBlock(new BlockPos(5, 2, 8), Blocks.AIR.defaultBlockState());
				helper.runAfterDelay(20, () -> {
					if (splitMotor.getOrCreateNetwork() == splitLoad.getOrCreateNetwork()) helper.fail("digging the shaft did not split the network");
					if (splitMotor.getSpeed() <= splitLoad.getSpeed() + 0.5) helper.fail("split components did not evolve independently");
					done[0] |= 8;
				});
			}
			if (!reversed[0] && reverseA.hasNetwork() && reverseB.hasNetwork()) {
				reversed[0] = true;
				((INetworkDynamics) reverseA.getOrCreateNetwork()).differentialcreate$setSpeed(0.5f);
				((INetworkDynamics) reverseB.getOrCreateNetwork()).differentialcreate$setSpeed(-0.5f);
				BlockPos bridge = new BlockPos(17, 2, 2);
				helper.setBlock(bridge, shaftX());
				com.diliupengyun.differential_create.kinetics.NetworkTopology.mark((KineticBlockEntity) helper.getBlockEntity(bridge));
				com.diliupengyun.differential_create.kinetics.NetworkTopology.flush();
				if (reverseA.getOrCreateNetwork() != reverseB.getOrCreateNetwork() || helper.getBlockState(bridge).isAir())
					helper.fail("gentle reverse contact failed to merge real membership");
				if (Math.abs(reverseA.getSpeed()) > 0.001) helper.fail("reverse merge did not conserve momentum");
				done[0] |= 16;
			}
			if ((done[0] & 32) == 0 && closeTo(speedOf(geared), 12, 0.2)) {
				KineticBlockEntity small = (KineticBlockEntity) helper.getBlockEntity(smallPos);
				if (Math.abs(small.getSpeed() + 2 * geared.getSpeed()) > 0.001) helper.fail("large-to-small gearing did not produce -2 ratio");
				var tag = small.saveWithoutMetadata(helper.getLevel().registryAccess());
				var saved = tag.getCompound("Network");
				if (!saved.contains("DCRatio") || !saved.contains("DCAnchor")) helper.fail("save omitted the physical reference frame");
				var clone = (KineticBlockEntity) small.getType().create(small.getBlockPos(), small.getBlockState());
				clone.setLevel(helper.getLevel());
				clone.loadWithComponents(tag, helper.getLevel().registryAccess());
				KineticNetwork restored = new KineticNetwork();
				com.diliupengyun.differential_create.kinetics.NetworkDynamics.restoreMember(clone, restored);
				float restoredLocal = com.diliupengyun.differential_create.kinetics.NetworkDynamics.localSpeed(clone, restored);
				if (Math.abs(restoredLocal - small.getSpeed()) > 0.001) helper.fail("NBT round trip changed a geared member's physical speed");
				float before = ((INetworkDynamics) geared.getOrCreateNetwork()).differentialcreate$getSpeed();
				saved.putFloat("DCSpeed", -1000);
				com.diliupengyun.differential_create.kinetics.NetworkDynamics.readNetworkDynamics(tag, false, small);
				com.diliupengyun.differential_create.kinetics.NetworkDynamics.restoreMember(small, small.getOrCreateNetwork());
				if (((INetworkDynamics) geared.getOrCreateNetwork()).differentialcreate$getSpeed() != before)
					helper.fail("late saved state overwrote an already running network");
				done[0] |= 32;
			}
			if (!hot[0] && hotMotor.hasNetwork()) {
				hot[0] = true;
				((INetworkDynamics) hotMotor.getOrCreateNetwork()).differentialcreate$setSpeed(8);
				BlockPos newLoad = new BlockPos(16, 2, 14);
				placeLoad(helper, newLoad);
				com.diliupengyun.differential_create.kinetics.NetworkTopology.mark((KineticBlockEntity) helper.getBlockEntity(newLoad));
				com.diliupengyun.differential_create.kinetics.NetworkTopology.flush();
				if (Math.abs(hotMotor.getSpeed() - 7.5) > 0.001) helper.fail("stationary new load was accelerated without paying its momentum cost");
				done[0] |= 64;
			}
			if (done[0] == 127) helper.succeed();
		});
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "analog_clutch", timeoutTicks = 1800)
	public static void analogClutchRegression(GameTestHelper helper) {
		prepareWorkingLoads(helper);
		var motor = placeWheel(helper, new BlockPos(2, 2, 2), 1, null);
		var driven = placeWheel(helper, new BlockPos(6, 2, 2), 0, null);
		BlockPos clutchPos = new BlockPos(4, 2, 2);
		helper.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(clutchPos.west(), shaftX());
		helper.setBlock(clutchPos.east(), shaftX());
		placeLoad(helper, new BlockPos(7, 2, 2));
		setClutchSignal(helper, clutchPos, 15);
		ClutchBlockEntity clutch = (ClutchBlockEntity) helper.getBlockEntity(clutchPos);
		IClutchDynamics state = (IClutchDynamics) clutch;
		int[] stage = {0};
		helper.runAfterDelay(5, () -> {
			if (motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) helper.fail("signal 15 did not initially disconnect the clutch");
			((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$setSpeed(16);
			((INetworkDynamics) driven.getOrCreateNetwork()).differentialcreate$setSpeed(0);
			setClutchSignal(helper, clutchPos, 14);
			helper.runAfterDelay(30, () -> {
				if (clutch.isRemoved()) helper.fail("soft engagement incorrectly applied the full hard-collision loss");
				assertClutchSignal(helper, clutch, 14);
				if (Math.abs(state.differentialcreate$getLastCouplingTorque() - 32.0 / 15) > 0.002)
					helper.fail("signal 14 did not transmit its small nonzero torque");
				if (Math.abs(driven.getSpeed()) > 0.001 || state.differentialcreate$isLocked())
					helper.fail("weak clutch bypassed the driven machine's static load");
				var tag = clutch.saveWithoutMetadata(helper.getLevel().registryAccess());
				var clone = (ClutchBlockEntity) clutch.getType().create(clutch.getBlockPos(), clutch.getBlockState());
				clone.loadWithComponents(tag, helper.getLevel().registryAccess());
				if (((IClutchDynamics) clone).differentialcreate$getRedstoneSignal() != 14)
					helper.fail("clutch control value did not survive NBT round trip");
				setClutchSignal(helper, clutchPos, 7);
				helper.runAfterDelay(10, () -> {
					assertClutchSignal(helper, clutch, 7);
					if (driven.getSpeed() <= 0 || state.differentialcreate$getLastCouplingTorque() < 16)
						helper.fail("nonzero-to-nonzero signal change failed to increase transmitted torque");
					stage[0] = 1;
				});
			});
		});
		helper.onEachTick(() -> {
			if (clutch.isRemoved()) helper.fail("analog clutch unexpectedly broke");
			if (helper.getTick() % 200 == 0)
				DifferentialCreate.LOGGER.info("ANALOG CLUTCH tick={} stage={} signal={} torque={} speed={} -> {} locked={}",
					helper.getTick(), stage[0], state.differentialcreate$getRedstoneSignal(), state.differentialcreate$getLastCouplingTorque(),
					motor.getSpeed(), driven.getSpeed(), state.differentialcreate$isLocked());
			if (stage[0] == 1 && state.differentialcreate$isLocked() && closeTo(speedOf(motor), 14, 0.2)
				&& motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) {
				stage[0] = 2;
				// Enough torque permits full synchronization even at partial engagement.
				if (!clutch.getBlockState().getValue(BlockStateProperties.POWERED)
					|| clutch.getRotationSpeedModifier(clutch.getSourceFacing().getOpposite()) != 1)
					helper.fail("binary POWERED flag still prevented a partially engaged clutch from locking");
				setClutchSignal(helper, clutchPos, 8);
				helper.runAfterDelay(5, () -> {
					assertClutchSignal(helper, clutch, 8);
					if (!state.differentialcreate$isLocked() || motor.getOrCreateNetwork() != driven.getOrCreateNetwork())
						helper.fail("reducing pressure unnecessarily released a load below the new torque limit");
					setClutchSignal(helper, clutchPos, 14);
					helper.runAfterDelay(5, () -> {
						assertClutchSignal(helper, clutch, 14);
						if (state.differentialcreate$isLocked() || motor.getOrCreateNetwork() == driven.getOrCreateNetwork())
							helper.fail("lowering the torque limit below the load failed to unlock and split the clutch");
						setClutchSignal(helper, clutchPos, 15);
						helper.runAfterDelay(5, () -> {
							assertClutchSignal(helper, clutch, 15);
							if (state.differentialcreate$getLastCouplingTorque() != 0
								|| motor.getOrCreateNetwork() == driven.getOrCreateNetwork())
								helper.fail("fully released clutch continued transferring torque");
							setClutchSignal(helper, clutchPos, 7);
							stage[0] = 3;
						});
					});
				});
			}
			if (stage[0] == 3 && state.differentialcreate$isLocked() && motor.getOrCreateNetwork() == driven.getOrCreateNetwork()) {
				stage[0] = 4;
				setClutchSignal(helper, clutchPos, 0);
				helper.runAfterDelay(5, () -> {
					assertClutchSignal(helper, clutch, 0);
					if (!state.differentialcreate$isLocked() || motor.getOrCreateNetwork() != driven.getOrCreateNetwork())
						helper.fail("fully engaging an already synchronized clutch disconnected it");
					helper.succeed();
				});
			}
		});
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "clutch_load", timeoutTicks = 600)
	public static void lowInertiaClutchRegression(GameTestHelper helper) {
		prepareWorkingLoads(helper);
		// Reproduce a 2x geared wheel driving one 8-torque machine. There is no
		// passive wheel on the load side to conceal slip with extra inertia.
		for (int fixture = 0; fixture < 4; fixture++) {
			int z = 2 + fixture * 6;
			int signal = fixture < 2 ? 0 : 8;
			boolean reverse = fixture % 2 == 1;
			var motor = placeWheel(helper, new BlockPos(2, 2, z), reverse ? -1 : 1, null);
			helper.setBlock(new BlockPos(3, 2, z), shaftX());
			helper.setBlock(new BlockPos(4, 2, z), AllBlocks.LARGE_COGWHEEL.getDefaultState()
				.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			BlockPos cogPos = new BlockPos(4, 3, z + 1), clutchPos = cogPos.east(), loadPos = clutchPos.east();
			helper.setBlock(cogPos, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			helper.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			placeLoad(helper, loadPos);
			ClutchBlockEntity clutch = (ClutchBlockEntity) helper.getBlockEntity(clutchPos);
			// Either half can be the network reference; torque must work both ways.
			clutch.source = helper.absolutePos(reverse ? loadPos : cogPos);
			setClutchSignal(helper, clutchPos, signal);
			var cog = (KineticBlockEntity) helper.getBlockEntity(cogPos);
			var load = (KineticBlockEntity) helper.getBlockEntity(loadPos);
			helper.runAfterDelay(400, () -> {
				if (clutch.isRemoved()) helper.fail("loaded clutch broke during a start from rest");
				IClutchDynamics state = (IClutchDynamics) clutch;
				assertClutchSignal(helper, clutch, signal);
				if (!state.differentialcreate$isLocked() || motor.getOrCreateNetwork() != load.getOrCreateNetwork())
					helper.fail("8-torque load never synchronized: signal=" + signal + ", input=" + cog.getSpeed()
						+ ", output=" + load.getSpeed() + ", slip=" + state.differentialcreate$getLastDeltaOmega()
						+ ", torque=" + state.differentialcreate$getLastCouplingTorque());
				if (Math.abs(cog.getSpeed() - load.getSpeed()) > 0.01 || !closeTo(speedOf(load), 24, 0.05))
					helper.fail("loaded geared shafts did not settle together at 24 RPM");
				if (Math.abs(Math.abs(state.differentialcreate$getLastCouplingTorque()) - 8) > 0.05)
					helper.fail("synchronized clutch did not carry the 8-torque load");
			});
		}
		helper.runAfterDelay(401, helper::succeed);
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "windmill_rotation", timeoutTicks = 1400)
	public static void windmillRotationRegression(GameTestHelper helper) {
		prepareWorkingLoads(helper);
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
		BlockPos bearingPos = new BlockPos(12, 7, 10);
		helper.setBlock(bearingPos, AllBlocks.WINDMILL_BEARING.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		for (int y = -1; y <= 2; y++)
			for (int z = -3; z <= 4; z++)
				helper.setBlock(bearingPos.offset(1, y, z), AllBlocks.SAIL.getDefaultState()
					.setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(bearingPos.west(), shaftX());
		var windmill = (WindmillBearingBlockEntity) helper.getBlockEntity(bearingPos);
		BlockPos mechanicalPos = new BlockPos(4, 7, 24);
		helper.setBlock(mechanicalPos, AllBlocks.MECHANICAL_BEARING.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(mechanicalPos.east(), Blocks.OAK_PLANKS.defaultBlockState());
		var mechanical = (MechanicalBearingBlockEntity) helper.getBlockEntity(mechanicalPos);
		helper.runAfterDelay(5, () -> {
			windmill.assemble();
			mechanical.assemble();
			if (windmill.getMovedContraption() == null || mechanical.getMovedContraption() == null)
				helper.fail("bearing fixtures did not assemble: " + windmill.getLastAssemblyException());
			if (((BearingContraption) windmill.getMovedContraption().getContraption()).getSailBlocks() != 32)
				helper.fail("windmill fixture did not capture all 32 sails");
		});
		int[] stage = {0};
		helper.onEachTick(() -> {
			if (helper.getTick() < 10) return;
			if (windmill.isRemoved() || !windmill.isRunning() || windmill.getMovedContraption() == null)
				helper.fail("windmill stopped owning its assembled structure");
			if (stage[0] == 0) {
				// Exercise the actual bearing tick and contraption, independently of
				// the source command: positive, fractional, zero and back-driven RPM.
				for (float speed : new float[]{0.5f, 8, 3, 0, -2}) {
					assertBearingStep(helper, windmill, speed);
					assertBearingStep(helper, mechanical, speed);
				}
				if (windmill.getGeneratedSpeed() != 4
					|| com.diliupengyun.differential_create.kinetics.SourcePhysics.curve(windmill).noLoadSpeed() != 8)
					helper.fail("structure animation incorrectly changed the windmill's source characteristic");
				((INetworkDynamics) windmill.getOrCreateNetwork()).differentialcreate$setSpeed(0);
				stage[0] = 1;
			}
			if (stage[0] == 1 && closeTo(speedOf(windmill), 8, 0.05)) {
				placeLoad(helper, bearingPos.west(2));
				stage[0] = 2;
			} else if (stage[0] == 2 && closeTo(speedOf(windmill), 7, 0.05)) {
				// Add loads in two steps so hot connection stays below the normal
				// collision threshold while the windmill decelerates under real load.
				placeLoad(helper, bearingPos.west(3));
				placeLoad(helper, bearingPos.west(4));
				stage[0] = 3;
			} else if (stage[0] == 3 && closeTo(speedOf(windmill), 5, 0.05)) {
				for (int distance = 5; distance <= 10; distance++)
					placeLoad(helper, bearingPos.west(distance));
				stage[0] = 4;
			} else if (stage[0] == 4 && windmill.getSpeed() == 0) {
				stage[0] = 5;
				float angle = windmill.getMovedContraption().getAngle(1);
				helper.runAfterDelay(10, () -> {
					if (!windmill.isRunning() || windmill.getMovedContraption() == null
						|| windmill.getMovedContraption().getAngle(1) != angle || windmill.getAngularSpeed() != 0)
						helper.fail("overloaded stationary windmill continued rotating or lost its structure");
					helper.succeed();
				});
			}
			if (Math.abs(windmill.getAngularSpeed() - windmill.getSpeed() * 0.3f) > 1e-5)
				helper.fail("windmill structure did not track its shaft while the real load changed");
		});
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "windmill_engagement", timeoutTicks = 100)
	public static void windmillEngagementConservationRegression(GameTestHelper helper) {
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
		BlockPos bearingPos = new BlockPos(12, 8, 10);
		helper.setBlock(bearingPos, AllBlocks.WINDMILL_BEARING.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.UP));
		for (int x = 0; x <= 1; x++)
			for (int z = -3; z <= 3; z++)
				helper.setBlock(bearingPos.offset(x, 1, z), AllBlocks.SAIL.getDefaultState()
					.setValue(BlockStateProperties.FACING, Direction.UP));
		// Reproduce the screenshot's right-angle drive and four 2x stages.
		BlockState smallX = AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
		BlockState largeX = AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
		helper.setBlock(bearingPos.below(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
		helper.setBlock(bearingPos.below(2), smallX.setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
		helper.setBlock(bearingPos.below(3), largeX.setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
		helper.setBlock(bearingPos.offset(-1, -2, 0), largeX);
		helper.setBlock(bearingPos.offset(-1, -1, 1), smallX);
		helper.setBlock(bearingPos.offset(-2, -1, 1), largeX);
		helper.setBlock(bearingPos.offset(-2, -2, 2), smallX);
		helper.setBlock(bearingPos.offset(-3, -2, 2), largeX);
		helper.setBlock(bearingPos.offset(-3, -3, 1), smallX);
		helper.setBlock(bearingPos.offset(-4, -3, 1), largeX);
		BlockPos outputPos = bearingPos.offset(-4, -2, 0), pressPos = outputPos.west();
		helper.setBlock(outputPos, smallX);
		var windmill = (WindmillBearingBlockEntity) helper.getBlockEntity(bearingPos);
		helper.runAfterDelay(5, () -> {
			windmill.assemble();
			if (windmill.getMovedContraption() == null || InertiaRegistry.getSailCount(windmill) != 14)
				helper.fail("engagement fixture did not assemble all 14 sails: " + windmill.getLastAssemblyException());
		});
		double[] previousSpeed = {Double.NaN}, previousEnergy = {Double.NaN};
		long[] engagementTick = {-1};
		helper.runAfterDelay(10, () -> {
			assertNear(helper, 0.16, InertiaRegistry.getInertia(windmill), 1e-7, "14-sail inertia");
			assertNear(helper, 1.75, windmill.getGeneratedSpeed(), 1e-7, "14-sail source command");
			for (float start : new float[]{-3.5f, 3.5f}) {
				helper.setBlock(pressPos, Blocks.AIR.defaultBlockState());
				NetworkTopology.mark(windmill);
				NetworkTopology.flush();
				INetworkDynamics before = (INetworkDynamics) windmill.getOrCreateNetwork();
				before.differentialcreate$setSpeed(start / before.differentialcreate$getRatios().get(windmill));
				var output = (KineticBlockEntity) helper.getBlockEntity(outputPos);
				assertNear(helper, -16 * start, output.getSpeed(), 1e-6, "actual four-stage gear ratio");
				double energyBefore = networkEnergy(windmill);
				double momentumBefore = InertiaRegistry.getInertia(windmill) * (double) windmill.getSpeed();
				assertNear(helper, 0.98, energyBefore, 1e-7, "energy before hot placement");
				helper.setBlock(pressPos, AllBlocks.MECHANICAL_PRESS.getDefaultState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
				var press = (MechanicalPressBlockEntity) helper.getBlockEntity(pressPos);
				NetworkTopology.mark(press);
				NetworkTopology.flush(); // Isolate the instantaneous impact from external work during a tick.
				if (press.isRemoved() || helper.getBlockEntity(pressPos) != press
					|| press.getOrCreateNetwork() != windmill.getOrCreateNetwork())
					helper.fail("subthreshold windmill engagement broke or failed to connect the press");
				assertNear(helper, 0.01, InertiaRegistry.getInertia(press), 1e-8, "press inertia");
				assertNear(helper, start / 17.0, windmill.getSpeed(), 1e-6, "windmill pays for accelerating the press");
				assertNear(helper, -16 * start / 17.0, press.getSpeed(), 1e-6, "press starts at the merged speed");
				double momentumAfter = InertiaRegistry.getInertia(windmill) * (double) windmill.getSpeed()
					- 16 * InertiaRegistry.getInertia(press) * (double) press.getSpeed();
				assertNear(helper, momentumBefore, momentumAfter, 1e-7, "momentum in the windmill reference frame");
				double energyAfter = networkEnergy(windmill);
				assertNear(helper, energyBefore / 17, energyAfter, 1e-7, "actual merged network energy");
				assertNear(helper, 0.98 * 16 / 17, energyBefore - energyAfter, 1e-7, "actual dissipated energy");
				// Rebuilding the same topology must neither restore the old RPM nor charge the inertia twice.
				for (int i = 0; i < 3; i++) {
					NetworkTopology.mark(output);
					NetworkTopology.flush();
					assertNear(helper, momentumAfter, InertiaRegistry.getInertia(windmill) * (double) windmill.getSpeed()
						- 16 * InertiaRegistry.getInertia(press) * (double) press.getSpeed(), 1e-7, "rebuild preserves momentum");
					assertNear(helper, energyAfter, networkEnergy(windmill), 1e-7, "rebuild preserves energy");
				}
				DifferentialCreate.LOGGER.info("WINDMILL ENGAGEMENT start={} wind={} press={} momentum={}->{} energy={}->{}",
					start, windmill.getSpeed(), press.getSpeed(), momentumBefore, momentumAfter, energyBefore, energyAfter);
			}
			// Placement still pays the press's inertia, but its load begins only with a working cycle.
			var idlePress = (MechanicalPressBlockEntity) helper.getBlockEntity(pressPos);
			assertNear(helper, 0, com.diliupengyun.differential_create.kinetics.MachineActivity.impact(idlePress), 0,
				"new press has no standby torque");
			idlePress.getPressingBehaviour().start(com.simibubi.create.content.kinetics.press.PressingBehaviour.Mode.WORLD);
			previousSpeed[0] = windmill.getSpeed();
			previousEnergy[0] = networkEnergy(windmill);
			engagementTick[0] = helper.getTick();
		});
		helper.onEachTick(() -> {
			if (engagementTick[0] < 0 || helper.getTick() <= engagementTick[0]) return;
			var network = windmill.getOrCreateNetwork();
			var dynamics = (INetworkDynamics) network;
			var balance = NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios());
			double r = dynamics.differentialcreate$getRatios().get(windmill);
			assertNear(helper, 2.72, balance.j() / (r * r), 1e-6, "post-rebuild inertia includes the press once");
			assertNear(helper, 128, balance.l() / Math.abs(r), 1e-6, "press torque referred through 16x gears");
			assertNear(helper, 12.25, SourcePhysics.curve(windmill).stallTorque(), 1e-6, "wind torque remains external input");
			// Independent exact solution of J*w'=12.25-3.5*w-128 until it stops.
			// The wind cannot overcome the press's static load after reaching zero.
			double offset = (128 - 12.25) / 3.5;
			double expected = Math.max(0, (previousSpeed[0] + offset) * Math.exp(-3.5 / 2.72 / 1200) - offset);
			assertNear(helper, expected, windmill.getSpeed(), 1e-6, "next tick follows torque balance without restoring old speed");
			double energy = networkEnergy(windmill);
			if (!Double.isFinite(energy) || energy > previousEnergy[0] + 1e-7)
				helper.fail("loaded windmill created kinetic energy after engagement: " + previousEnergy[0] + " -> " + energy);
			previousSpeed[0] = windmill.getSpeed();
			previousEnergy[0] = energy;
			if (helper.getTick() >= engagementTick[0] + 20) {
				assertNear(helper, 0, windmill.getSpeed(), 0, "overloaded windmill stays stopped");
				helper.succeed();
			}
		});
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", batch = "idle_load", timeoutTicks = 1600)
	public static void idleLoadRegression(GameTestHelper helper) {
		var bounds = helper.getBounds();
		for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
			for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
				helper.getLevel().setChunkForced(x, z, true);
		boolean[] drive = {true};
		var motor = placeWheel(helper, new BlockPos(2, 3, 2), 1, drive);
		helper.setBlock(new BlockPos(3, 3, 2), shaftX());
		BlockPos pressPos = new BlockPos(4, 3, 2);
		for (int x = 4; x <= 11; x++) helper.setBlock(new BlockPos(x, 3, 2), workingPressX());
		helper.setBlock(pressPos.below(2), Blocks.STONE.defaultBlockState());
		var press = (MechanicalPressBlockEntity) helper.getBlockEntity(pressPos);
		BlockPos millPos = new BlockPos(3, 3, 10), mixerPos = new BlockPos(7, 3, 10);
		helper.setBlock(millPos, AllBlocks.MILLSTONE.getDefaultState());
		helper.setBlock(mixerPos, AllBlocks.MECHANICAL_MIXER.getDefaultState());
		helper.setBlock(mixerPos.below(2), AllBlocks.BASIN.getDefaultState());
		BlockPos sawPos = new BlockPos(11, 3, 10), drillPos = new BlockPos(15, 3, 10);
		helper.setBlock(sawPos, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
		helper.setBlock(drillPos, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		BlockPos deployerPos = new BlockPos(3, 3, 18), crafterPos = new BlockPos(7, 3, 18);
		helper.setBlock(deployerPos, AllBlocks.DEPLOYER.getDefaultState());
		helper.setBlock(crafterPos, AllBlocks.MECHANICAL_CRAFTER.getDefaultState());
		BlockPos armPos = new BlockPos(11, 3, 18), ejectorPos = new BlockPos(15, 3, 18);
		helper.setBlock(armPos, AllBlocks.MECHANICAL_ARM.getDefaultState());
		helper.setBlock(ejectorPos, AllBlocks.WEIGHTED_EJECTOR.getDefaultState());
		BlockPos crusherPos = new BlockPos(21, 3, 10), controlPos = crusherPos.east();
		helper.setBlock(crusherPos, AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		helper.setBlock(controlPos.east(), AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		BlockPos bearingPos = new BlockPos(21, 3, 18), pulleyPos = new BlockPos(25, 3, 18);
		helper.setBlock(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
		helper.setBlock(pulleyPos, AllBlocks.ROPE_PULLEY.getDefaultState());
		helper.runAfterDelay(5, () -> {
			assertMachineLoad(helper, press, 0, "empty press");
			var mill = (com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity) helper.getBlockEntity(millPos);
			assertMachineLoad(helper, mill, 0, "empty mill");
			mill.inputInv.setStackInSlot(0, new ItemStack(Items.BEDROCK));
			mill.timer = 100; // Create uses this timer even when recipe lookup fails.
			assertMachineLoad(helper, mill, 0, "invalid milling recipe is idle despite its retry timer");
			mill.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT));
			assertMachineLoad(helper, mill, 4, "valid milling cycle retains load at zero RPM");
			mill.outputInv.setStackInSlot(0, new ItemStack(Items.STONE, 64));
			assertMachineLoad(helper, mill, 0, "full milling output pauses the load");
			mill.outputInv.setStackInSlot(0, ItemStack.EMPTY);
			assertMachineLoad(helper, mill, 4, "clearing the output resumes the load");
			mill.inputInv.setStackInSlot(0, ItemStack.EMPTY);
			assertMachineLoad(helper, mill, 0, "removed milling input is idle");
			mill.timer = 0;
			var mixer = (com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity) helper.getBlockEntity(mixerPos);
			assertMachineLoad(helper, mixer, 0, "mixer without ingredients");
			mixer.running = true;
			mixer.runningTicks = 30;
			assertMachineLoad(helper, mixer, 4, "mixer return travel is still work at standstill");
			mixer.running = false;
			assertMachineLoad(helper, mixer, 0, "finished mixer");
			var saw = (com.simibubi.create.content.kinetics.saw.SawBlockEntity) helper.getBlockEntity(sawPos);
			saw.inventory.insertItem(0, new ItemStack(Items.BEDROCK), false);
			assertMachineLoad(helper, saw, 0, "saw without a matching recipe");
			saw.inventory.clear();
			saw.inventory.insertItem(0, new ItemStack(Items.OAK_LOG), false);
			assertMachineLoad(helper, saw, 4, "queued cutting recipe");
			saw.inventory.appliedRecipe = true;
			saw.inventory.remainingTime = 0;
			assertMachineLoad(helper, saw, 0, "finished saw waiting to export");
			saw.inventory.clear();
			var drill = (KineticBlockEntity) helper.getBlockEntity(drillPos);
			assertMachineLoad(helper, drill, 0, "drill facing air");
			helper.setBlock(drillPos.east(), Blocks.STONE.defaultBlockState());
			assertMachineLoad(helper, drill, 4, "drill against a breakable block");
			helper.setBlock(drillPos.east(), Blocks.BEDROCK.defaultBlockState());
			assertMachineLoad(helper, drill, 0, "drill with no breakable target");
			helper.setBlock(drillPos.east(), Blocks.AIR.defaultBlockState());
			var deployer = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity) helper.getBlockEntity(deployerPos);
			for (String phase : new String[]{"WAITING", "EXPANDING", "RETRACTING", "DUMPING"}) {
				setSavedPhase(helper, deployer, "State", phase);
				assertMachineLoad(helper, deployer, phase.equals("EXPANDING") || phase.equals("RETRACTING") ? 4 : 0,
					"deployer phase " + phase);
			}
			setSavedPhase(helper, deployer, "State", "WAITING");
			var crafter = (com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity) helper.getBlockEntity(crafterPos);
			for (String phase : new String[]{"IDLE", "ACCEPTING", "ASSEMBLING", "EXPORTING", "WAITING", "CRAFTING", "INSERTING"}) {
				setSavedPhase(helper, crafter, "Phase", phase);
				assertMachineLoad(helper, crafter, List.of("ASSEMBLING", "EXPORTING", "CRAFTING").contains(phase) ? 2 : 0,
					"crafter phase " + phase);
			}
			setSavedPhase(helper, crafter, "Phase", "IDLE");
			var arm = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity) helper.getBlockEntity(armPos);
			for (String phase : new String[]{"SEARCH_INPUTS", "MOVE_TO_INPUT", "SEARCH_OUTPUTS", "MOVE_TO_OUTPUT", "DANCING"}) {
				setSavedPhase(helper, arm, "Phase", phase);
				assertMachineLoad(helper, arm, phase.startsWith("SEARCH") ? 0 : 2, "arm phase " + phase);
			}
			setSavedPhase(helper, arm, "Phase", "SEARCH_INPUTS");
			var ejector = (com.simibubi.create.content.logistics.depot.EjectorBlockEntity) helper.getBlockEntity(ejectorPos);
			var ejectorTag = ejector.saveWithoutMetadata(helper.getLevel().registryAccess());
			ejectorTag.putString("State", "CHARGED");
			ejector.loadWithComponents(ejectorTag, helper.getLevel().registryAccess());
			// Its pre-tick state was winding up; allow the completed interval to retain its load.
			((com.diliupengyun.differential_create.kinetics.IMemberDynamics) ejector).differentialcreate$member().lastWorkingTick = Long.MIN_VALUE;
			assertMachineLoad(helper, ejector, 0, "charged ejector waiting for activation");
			helper.setBlock(controlPos, AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState());
			var controller = (com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity) helper.getBlockEntity(controlPos);
			var wheel = (KineticBlockEntity) helper.getBlockEntity(crusherPos);
			assertMachineLoad(helper, wheel, 0, "empty crushing wheels");
			controller.inventory.insertItem(0, new ItemStack(Items.COBBLESTONE), false);
			assertMachineLoad(helper, wheel, 8, "occupied crushing controller");
			controller.inventory.appliedRecipe = true;
			controller.inventory.remainingTime = 0;
			assertMachineLoad(helper, wheel, 0, "crusher waiting for output space");
			controller.inventory.clear();
			var bearing = (MechanicalBearingBlockEntity) helper.getBlockEntity(bearingPos);
			assertMachineLoad(helper, bearing, 0, "unassembled mechanical bearing");
			helper.setBlock(bearingPos.above(), Blocks.OAK_PLANKS.defaultBlockState());
			bearing.assemble();
			assertMachineLoad(helper, bearing, 4, "bearing with a driven structure");
			var pulley = (com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity) helper.getBlockEntity(pulleyPos);
			pulley.running = true;
			var pulleyTag = pulley.saveWithoutMetadata(helper.getLevel().registryAccess());
			pulleyTag.putBoolean("Waiting", false);
			pulley.loadWithComponents(pulleyTag, helper.getLevel().registryAccess());
			assertMachineLoad(helper, pulley, 4, "active pulley");
			pulleyTag.putBoolean("Waiting", true);
			pulley.loadWithComponents(pulleyTag, helper.getLevel().registryAccess());
			assertMachineLoad(helper, pulley, 0, "pulley waiting at its endpoint");
			pulley.running = false;
			mill.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT));
			mill.timer = 100;
			mill.outputInv.setStackInSlot(0, new ItemStack(Items.STONE, 64));
			((INetworkDynamics) mill.getOrCreateNetwork()).differentialcreate$setSpeed(128);
		});
		helper.runAfterDelay(15, () -> {
			var mill = (com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity) helper.getBlockEntity(millPos);
			assertNear(helper, 100, mill.timer, 0, "blocked mill does not advance for free while its shaft spins");
			if (!mill.inputInv.getStackInSlot(0).is(Items.WHEAT)) helper.fail("blocked mill consumed its input");
			assertMachineLoad(helper, mill, 0, "blocked mill remains idle through real ticks");
			mill.outputInv.setStackInSlot(0, ItemStack.EMPTY);
		});
		helper.runAfterDelay(25, () -> {
			var mill = (com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity) helper.getBlockEntity(millPos);
			if (mill.timer >= 100 || mill.timer <= 0) helper.fail("clearing the mill output did not resume its saved progress: " + mill.timer);
			assertMachineLoad(helper, mill, 4, "resumed milling charges torque");
		});
		int[] stage = {0};
		boolean[] sawReturn = {false};
		ItemEntity[] material = {null};
		helper.onEachTick(() -> {
			if (material[0] != null && material[0].isAlive() && material[0].getItem().is(Items.IRON_INGOT)) {
				var pos = helper.absolutePos(pressPos.below());
				material[0].setPos(pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5);
				material[0].setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				material[0].setOnGround(true);
			}
			if (stage[0] == 0 && closeTo(speedOf(motor), 16, 0.05)) {
				assertMachineLoad(helper, press, 0, "a row of eight ready presses stays idle");
				var dynamics = (INetworkDynamics) motor.getOrCreateNetwork();
				var balance = NetworkPhysics.aggregate(motor.getOrCreateNetwork(), dynamics.differentialcreate$getRatios());
				assertNear(helper, 0, balance.l(), 0, "ready machines exert no network load");
				assertNear(helper, 0.23, balance.j(), 1e-6, "all eight standby machines retain inertia");
				var pos = helper.absolutePos(pressPos.below());
				material[0] = new ItemEntity(helper.getLevel(), pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5,
					new ItemStack(Items.IRON_INGOT));
				material[0].setNoGravity(true);
				material[0].setOnGround(true);
				helper.getLevel().addFreshEntity(material[0]);
				stage[0] = 1;
			} else if (stage[0] == 1 && press.getPressingBehaviour().running) {
				assertMachineLoad(helper, press, 8, "real recipe starts the full press load");
				stage[0] = 2;
			} else if (stage[0] == 2) {
				if (press.getPressingBehaviour().running) {
					assertMachineLoad(helper, press, 8, "press keeps its load throughout the cycle");
					if (press.getPressingBehaviour().runningTicks >= 160) sawReturn[0] = true;
				} else if (!MachineActivity.active(press)) {
					if (!sawReturn[0]) helper.fail("press never completed its return travel");
					boolean sheet = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
						new net.minecraft.world.phys.AABB(helper.absolutePos(pressPos.below())).inflate(2)).stream()
						.anyMatch(item -> item.getItem().is(com.simibubi.create.AllItems.IRON_SHEET.get()));
					if (!sheet) helper.fail("full press cycle did not produce an iron sheet");
					assertMachineLoad(helper, press, 0, "completed press returns to zero standby torque");
					stage[0] = 3;
				}
			} else if (stage[0] == 3 && closeTo(speedOf(motor), 16, 0.05)) {
				stage[0] = 4;
				drive[0] = false;
				motor.setFlowScoreAndUpdate(0);
				NetworkTopology.flush();
				double speed = motor.getSpeed(), energy = networkEnergy(motor);
				helper.runAfterDelay(40, () -> {
					assertNear(helper, speed, motor.getSpeed(), 1e-5, "zero-load machines coast without free acceleration or braking");
					assertNear(helper, energy, networkEnergy(motor), 1e-5, "standby coasting conserves energy");
					helper.succeed();
				});
			}
		});
	}

	private static void setSavedPhase(GameTestHelper helper, KineticBlockEntity machine, String key, String phase) {
		var tag = machine.saveWithoutMetadata(helper.getLevel().registryAccess());
		tag.putString(key, phase);
		machine.loadWithComponents(tag, helper.getLevel().registryAccess());
	}

	private static void assertMachineLoad(GameTestHelper helper, KineticBlockEntity machine, double expected, String what) {
		// Assembly can detach a controller until the next topology flush. Its
		// activity and client packet must already describe the correct state.
		var network = machine.getOrCreateNetwork();
		if (network != null) MachineActivity.refresh(network);
		assertNear(helper, expected, MachineActivity.impact(machine), 1e-6, what);
		var packet = machine.getUpdateTag(helper.getLevel().registryAccess());
		if (!packet.contains("DCMachineWorking") || packet.getBoolean("DCMachineWorking") != (expected > 0))
			helper.fail(what + ": client activity packet does not match the actual load");
	}

	private static double networkEnergy(KineticBlockEntity member) {
		double energy = 0;
		for (KineticBlockEntity be : member.getOrCreateNetwork().members.keySet()) {
			double speed = be.getSpeed();
			energy += 0.5 * InertiaRegistry.getInertia(be) * speed * speed;
		}
		return energy;
	}

	private static void assertNear(GameTestHelper helper, double expected, double actual, double tolerance, String what) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
			helper.fail(what + ": expected " + expected + ", got " + actual);
	}

	private static void assertBearingStep(GameTestHelper helper, MechanicalBearingBlockEntity bearing, float localSpeed) {
		INetworkDynamics dynamics = (INetworkDynamics) bearing.getOrCreateNetwork();
		float ratio = dynamics.differentialcreate$getRatios().get(bearing);
		dynamics.differentialcreate$setSpeed(localSpeed / ratio);
		bearing.setAngle(120);
		bearing.tick();
		float expected = 120 + localSpeed * 0.3f;
		float actual = bearing.getMovedContraption().getAngle(1);
		if (Math.abs(actual - expected) > 1e-4)
			helper.fail(bearing.getBlockState().getBlock() + " structure angle at " + localSpeed
				+ " RPM: expected " + expected + ", got " + actual);
	}

	private static void setClutchSignal(GameTestHelper helper, BlockPos clutchPos, int signal) {
		BlockPos emitter = clutchPos.above();
		// A target emits the actual analogue signal. Set its initial state first
		// so onPlace's stale-arrow reset does not clear the test's controlled power.
		if (!helper.getBlockState(emitter).is(Blocks.TARGET)) helper.setBlock(emitter, Blocks.TARGET.defaultBlockState());
		helper.setBlock(emitter, Blocks.TARGET.defaultBlockState().setValue(BlockStateProperties.POWER, signal));
	}

	private static void assertClutchSignal(GameTestHelper helper, ClutchBlockEntity clutch, int signal) {
		IClutchDynamics state = (IClutchDynamics) clutch;
		if (state.differentialcreate$getRedstoneSignal() != signal) helper.fail("clutch did not sample signal " + signal);
		if (Math.abs(state.differentialcreate$getLastCouplingTorque()) > ClutchControl.torqueLimit(signal) + 0.002)
			helper.fail("clutch exceeded the torque limit for signal " + signal);
	}

	// Scenario anchors
	private static final BlockPos NOLOAD_WHEEL = new BlockPos(2, 2, 2);
	private static final BlockPos LOADED_WHEEL = new BlockPos(2, 2, 8);
	private static final BlockPos STALLED_WHEEL = new BlockPos(2, 2, 14);
	private static final BlockPos MERGE_WHEEL_A = new BlockPos(14, 2, 2);
	private static final BlockPos MERGE_WHEEL_B = new BlockPos(18, 2, 2);
	private static final BlockPos MERGE_CONNECTOR = new BlockPos(17, 2, 2);
	private static final BlockPos COAST_WHEEL = new BlockPos(2, 2, 18);
	private static final BlockPos COAST_SHAFT = new BlockPos(3, 2, 18);
	private static final BlockPos COAST_LOADED_WHEEL = new BlockPos(2, 2, 24);
	private static final BlockPos COAST_LOADED_SHAFT = new BlockPos(3, 2, 24);
	private static final BlockPos REVERSE_WHEEL_A = new BlockPos(14, 2, 18);
	private static final BlockPos REVERSE_WHEEL_B = new BlockPos(18, 2, 18);
	private static final BlockPos REVERSE_CONNECTOR = new BlockPos(17, 2, 18);
	private static final BlockPos WATER_WHEEL = new BlockPos(8, 2, 24);
	private static final BlockPos WATER_SHAFT = new BlockPos(9, 2, 24);
	private static final BlockPos PRESS_WHEEL = new BlockPos(2, 2, 20);
	private static final BlockPos PRESS = new BlockPos(4, 2, 20);
	private static final BlockPos UNIFORM_WHEEL = new BlockPos(8, 2, 20);

	@SubscribeEvent
	public static void register(RegisterGameTestsEvent event) {
		event.register(KineticPhysicsTests.class);
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", timeoutTicks = 4000)
	public static void physicsRegression(GameTestHelper helper) {
		prepareWorkingLoads(helper);
		// The GameTest server drops structures at random far coordinates where
		// no player ticket keeps chunks entity-ticking: without forcing them,
		// block entities tick late or never, networks form partially and the
		// physics become non-deterministic (flaky scenes 8/9/10). Pin every
		// chunk the structure touches (plus a one-chunk margin) for the run.
		var bounds = helper.getBounds();
		if (bounds.getXsize() < 24 || bounds.getZsize() < 26)
			helper.fail("invalid structure dimensions: size and block positions must be NBT lists");
		for (int cx = (((int) Math.floor(bounds.minX)) >> 4) - 1; cx <= (((int) Math.floor(bounds.maxX)) >> 4) + 1; cx++)
			for (int cz = (((int) Math.floor(bounds.minZ)) >> 4) - 1; cz <= (((int) Math.floor(bounds.maxZ)) >> 4) + 1; cz++)
				helper.getLevel().setChunkForced(cx, cz, true);

		// Scene 1: no-load water wheel -> converges to ω0 = 16 RPM
		WaterWheelBlockEntity noLoad = placeWheel(helper, NOLOAD_WHEEL, 1, null);
		helper.setBlock(new BlockPos(3, 2, 2), shaftX());
		helper.setBlock(new BlockPos(4, 2, 2), shaftX());

		// Scene 2: water wheel with τ = 24 load (3 working presses) -> 16 − 24/4 = 10 RPM
		WaterWheelBlockEntity loaded = placeWheel(helper, LOADED_WHEEL, 1, null);
		helper.setBlock(new BlockPos(3, 2, 8), shaftX());
		for (int i = 0; i < 3; i++)
			placeLoad(helper, new BlockPos(4 + i, 2, 8));

		// Scene 3: water wheel with τ = 80 load (10 working presses) > stall
		// torque 64 -> sticks at 0
		WaterWheelBlockEntity stalled = placeWheel(helper, STALLED_WHEEL, 1, null);
		helper.setBlock(new BlockPos(3, 2, 14), shaftX());
		for (int i = 0; i < 10; i++)
			placeLoad(helper, new BlockPos(4 + i, 2, 14));

		// Scene 4: gentle same-direction merge passes the energy check.
		// A: lone wheel (16 RPM, J = 0.15). B: wheel with τ = 8 (equilibrium
		// 14 RPM, J = 0.16). Δ = 2 → E ≈ 0.16 < E_BREAK, momentum merge:
		// (0.15×16 + 0.16×14)/0.31 ≈ 14.97 RPM — and no block is destroyed.
		WaterWheelBlockEntity wheelA = placeWheel(helper, MERGE_WHEEL_A, 1, null);
		helper.setBlock(new BlockPos(15, 2, 2), shaftX());
		helper.setBlock(new BlockPos(16, 2, 2), shaftX());
		WaterWheelBlockEntity wheelB = placeWheel(helper, MERGE_WHEEL_B, 1, null);
		helper.setBlock(new BlockPos(19, 2, 2), shaftX());
		placeLoad(helper, new BlockPos(20, 2, 2));

		// Scene 5+6: source removal — coasting on inertia, empty and loaded
		WaterWheelBlockEntity coast = placeWheel(helper, COAST_WHEEL, 1, null);
		helper.setBlock(COAST_SHAFT, shaftX());
		helper.setBlock(new BlockPos(4, 2, 18), shaftX());
		WaterWheelBlockEntity coastLoaded = placeWheel(helper, COAST_LOADED_WHEEL, 1, null);
		helper.setBlock(COAST_LOADED_SHAFT, shaftX());
		for (int i = 0; i < 3; i++)
			placeLoad(helper, new BlockPos(4 + i, 2, 24));

		// Scene 7: reverse connection — counter-rotating networks must destroy
		// the bridging block (E ≫ E_BREAK)
		WaterWheelBlockEntity reverseA = placeWheel(helper, REVERSE_WHEEL_A, 1, null);
		helper.setBlock(new BlockPos(15, 2, 18), shaftX());
		helper.setBlock(new BlockPos(16, 2, 18), shaftX());
		WaterWheelBlockEntity reverseB = placeWheel(helper, REVERSE_WHEEL_B, -1, null);
		helper.setBlock(new BlockPos(19, 2, 18), shaftX());

		// Scene 8: water removal — the deactivated wheel stays as an inert
		// member and the network coasts down with its inertia included
		boolean[] waterPinned = { true };
		WaterWheelBlockEntity water = placeWheel(helper, WATER_WHEEL, 1, waterPinned);
		helper.setBlock(WATER_SHAFT, shaftX());
		for (int i = 0; i < 3; i++)
			placeLoad(helper, new BlockPos(10 + i, 2, 24));

		// Scene 9: a mechanical press keeps working off the network's inertia
		// after the source is removed (consumes stored kinetic energy)
		boolean[] pressPinned = { true };
		WaterWheelBlockEntity pressWheel = placeWheel(helper, PRESS_WHEEL, 1, pressPinned);
		helper.setBlock(new BlockPos(3, 2, 20), shaftX());
		helper.setBlock(PRESS, AllBlocks.MECHANICAL_PRESS.get()
			.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		// The template's floor slab does not reliably survive structure
		// placement at the GameTest server's far coordinates (terrain replaces
		// it): place the block the pressed item rests on explicitly, or the
		// item lands one block below the press's scan box.
		helper.setBlock(new BlockPos(4, 0, 20), Blocks.STONE.defaultBlockState());

		// Scene 10: after deactivation, every member's speed is exactly
		// r_i × ω of the shared network state — all the way to zero
		boolean[] uniformPinned = { true };
		WaterWheelBlockEntity uniform = placeWheel(helper, UNIFORM_WHEEL, 1, uniformPinned);
		helper.setBlock(new BlockPos(9, 2, 20), shaftX());
		for (int i = 0; i < 3; i++)
			placeLoad(helper, new BlockPos(10 + i, 2, 20));

		int[] done = { 0 };
		boolean[] mergeBridged = { false };
		boolean[] coastDug = { false };
		boolean[] reverseConnected = { false };
		boolean[] waterRemoved = { false };
		boolean[] pressFed = { false };
		boolean[] uniformRemoved = { false };

		helper.onEachTick(() -> {
			if (helper.getTick() % 200 == 0)
				DifferentialCreate.LOGGER.info("REGRESSION tick={} done={} wheel={} loaded={} entityTick={} pressWheel={} press={}",
					helper.getTick(), Integer.toHexString(done[0]), noLoad.getSpeed(), loaded.getSpeed(),
					helper.getLevel().isPositionEntityTicking(noLoad.getBlockPos()), pressWheel.getSpeed(),
					((KineticBlockEntity) helper.getBlockEntity(PRESS)).getSpeed());
			// Scene 1+2: settle polls
			if ((done[0] & (1 << 0)) == 0 && closeTo(speedOf(noLoad), 16, 0.5)) {
				done[0] |= 1 << 0;
			}
			if ((done[0] & (1 << 1)) == 0 && closeTo(speedOf(loaded), 10, 0.5)) {
				done[0] |= 1 << 1;
			}

			// Scene 4: bridge once both settled, then check the gentle merge
			if (!mergeBridged[0] && closeTo(speedOf(wheelA), 16, 0.5) && closeTo(speedOf(wheelB), 14, 0.5)) {
				helper.setBlock(MERGE_CONNECTOR, shaftX());
				mergeBridged[0] = true;
				return;
			}
			if (mergeBridged[0] && (done[0] & (1 << 2)) == 0) {
				KineticNetwork netA = wheelA.getOrCreateNetwork();
				KineticNetwork netB = wheelB.getOrCreateNetwork();
				if (netA == null || netB == null || netA != netB)
					return;
				assertSpeed(wheelA, 14.97, 0.75, "momentum-averaged merge");
				if (!helper.getBlockState(MERGE_CONNECTOR)
					.isAir())
					done[0] |= 1 << 2;
				else
					helper.fail("gentle merge destroyed the bridge block");
			}

			// Scene 5+6: dig the sources once both coast networks settled
			// (no-load settles at 16, loaded at 10)
			if (!coastDug[0] && closeTo(speedOf(coast), 16, 0.2) && closeTo(speedOf(coastLoaded), 10, 0.2)) {
				coastDug[0] = true;
				helper.setBlock(COAST_WHEEL, Blocks.AIR.defaultBlockState());
				helper.setBlock(COAST_LOADED_WHEEL, Blocks.AIR.defaultBlockState());
				// No-load coast (L = 0): still 16 after 20 ticks
				helper.runAfterDelay(20, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(COAST_SHAFT), 16, 0.8,
						"coasting no-load network");
					done[0] |= 1 << 3;
				});
				// Loaded coast from 10 RPM: J = 3×0.01, decay L/J = 24/0.03
				// ≈ 0.667 RPM/tick -> 10 − 6.67 ≈ 3.33 after 10 ticks, 0 after 20
				helper.runAfterDelay(10, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(COAST_LOADED_SHAFT), 3.33, 0.75,
						"coasting loaded network (10 ticks)");
					done[0] |= 1 << 4;
				});
				helper.runAfterDelay(20, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(COAST_LOADED_SHAFT), 0, 0.01,
						"coasting loaded network stopped");
					done[0] |= 1 << 5;
				});
			}

			// Scene 7: connect the counter-rotating networks once settled
			if (!reverseConnected[0] && closeTo(speedOf(reverseA), 16, 0.5) && closeTo(speedOf(reverseB), 16, 0.5)) {
				reverseConnected[0] = true;
				helper.setBlock(REVERSE_CONNECTOR, shaftX());
				helper.runAfterDelay(15, () -> {
					if (!helper.getBlockState(REVERSE_CONNECTOR)
						.isAir())
						helper.fail("reverse connection should have destroyed the bridge block");
					done[0] |= 1 << 6;
				});
			}

			// Scene 8: deactivate the wheel once settled at 10 RPM — the network
			// coasts with the inert wheel included (J = 0.18, decay ≈ 0.111
			// RPM/tick: 10 − 2.22 ≈ 7.78 at +20, 10 − 4.44 ≈ 5.56 at +40)
			if (!waterRemoved[0] && closeTo(speedOf(water), 10, 0.2)) {
				waterRemoved[0] = true;
				waterPinned[0] = false;
				water.setFlowScoreAndUpdate(0);
				helper.runAfterDelay(20, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(WATER_SHAFT), 7.78, 0.5,
						"water removed coasting (20 ticks)");
					done[0] |= 1 << 7;
				});
				helper.runAfterDelay(40, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(WATER_SHAFT), 5.56, 0.5,
						"water removed coasting (40 ticks)");
					done[0] |= 1 << 8;
				});
			}

			// Scene 9: once settled at 14 RPM (16 − 8/4), deactivate the source
			// (water removed, keep-inert so the wheel's J stays) and drop an
			// iron ingot under the press — it must start and finish one
			// pressing cycle off the network's stored energy
			// The item must be spawned into a chunk that is already
			// entity-ticking: the structure sits at random far coordinates
			// where the forced-chunk promotion can lag behind the settle gate,
			// and an item spawned into a non-ticking chunk freezes mid-air
			// (the press's scan skips it unless it is onGround).
			BlockPos itemPos = helper.absolutePos(new BlockPos(4, 1, 20));
			if (!pressFed[0] && closeTo(speedOf(pressWheel), 14, 0.5)
				&& helper.getLevel().isPositionEntityTicking(itemPos)) {
				pressFed[0] = true;
				pressPinned[0] = false;
				pressWheel.setFlowScoreAndUpdate(0);
				ItemEntity dropped = new ItemEntity(helper.getLevel(),
					itemPos.getX() + 0.5, itemPos.getY() + 0.01, itemPos.getZ() + 0.5,
					new ItemStack(Items.IRON_INGOT));
				dropped.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				dropped.setOnGround(true);
				helper.getLevel().addFreshEntity(dropped);
				// Pin the supplied input in the press's scan volume. Ground-item
				// drifting is independent of the work/energy behaviour under test.
				helper.onEachTick(() -> {
					if (dropped.isAlive() && dropped.getItem().is(Items.IRON_INGOT)) {
						dropped.setPos(itemPos.getX() + 0.5, itemPos.getY() + 0.01, itemPos.getZ() + 0.5);
						dropped.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
						dropped.setOnGround(true);
					}
				});
				helper.runAfterDelay(40, () -> {
					MechanicalPressBlockEntity press = (MechanicalPressBlockEntity) helper.getBlockEntity(PRESS);
					if (!press.getPressingBehaviour().running)
						helper.fail("press did not start: speed=" + press.getSpeed() + ", item=" + dropped.getItem()
							+ ", position=" + dropped.position() + ", onGround=" + dropped.onGround());
					done[0] |= 1 << 10;
				});
				helper.runAfterDelay(220, () -> {
					MechanicalPressBlockEntity press = (MechanicalPressBlockEntity) helper.getBlockEntity(PRESS);
					boolean hasSheet = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
						helper.getBounds().inflate(1)).stream()
						.anyMatch(item -> item.getItem().is(com.simibubi.create.AllItems.IRON_SHEET.get()));
					if (!hasSheet) helper.fail("press produced no sheet: ticks=" + press.getPressingBehaviour().runningTicks
						+ ", item=" + dropped.getItem() + ", pos=" + dropped.position() + ", onGround=" + dropped.onGround());
					pressPinned[0] = true;
					pressWheel.setFlowScoreAndUpdate(1);
					helper.runAfterDelay(120, () -> {
						if (!press.getPressingBehaviour().finished)
							helper.fail("press did not finish its remaining travel after power was restored");
						done[0] |= 1 << 11;
					});
				});
			}

			// Scene 10: deactivate once settled at 10 RPM, then verify member
			// consistency during the coast-down and at standstill
			if (!uniformRemoved[0] && closeTo(speedOf(uniform), 10, 0.2)) {
				uniformRemoved[0] = true;
				uniformPinned[0] = false;
				uniform.setFlowScoreAndUpdate(0);
				helper.runAfterDelay(30, () -> {
					assertMembersConsistent(uniform, "coasting members at +30 ticks");
					done[0] |= 1 << 12;
				});
				helper.runAfterDelay(130, () -> {
					KineticNetwork network = uniform.getOrCreateNetwork();
					if (network == null) {
						// Dissolved is fine: every member must then read 0
						for (BlockPos pos : List.of(UNIFORM_WHEEL, new BlockPos(9, 2, 20), new BlockPos(10, 2, 20),
							new BlockPos(11, 2, 20), new BlockPos(12, 2, 20))) {
							float speed = ((KineticBlockEntity) helper.getBlockEntity(pos)).getSpeed();
							if (Math.abs(speed) > 0.01f)
								helper.fail("member at " + pos + " still shows " + speed + " RPM after standstill");
						}
					} else {
						float dcSpeed = ((INetworkDynamics) network).differentialcreate$getSpeed();
						if (Math.abs(dcSpeed) > 0.01f)
							helper.fail("coasting network did not stop: " + dcSpeed);
						for (KineticBlockEntity member : network.members.keySet()) {
							float speed = member.getSpeed();
							if (Math.abs(speed) > 0.01f)
								helper.fail("member " + member.getBlockPos() + " still shows " + speed
									+ " RPM at standstill");
						}
					}
					done[0] |= 1 << 13;
				});
			}

			if (helper.getTick() >= 150 && (done[0] & (1 << 9)) == 0 && stalled.hasNetwork()) {
				KineticNetwork network = stalled.getOrCreateNetwork();
				if (network == null || network.members.size() < 12)
					helper.fail("stalled network missing or incomplete: " + network);
				float speed = Math.abs(((INetworkDynamics) network).differentialcreate$getSpeed());
				if (speed > 0.01f)
					helper.fail("stalled water wheel network: expected 0 RPM but got " + speed);
				done[0] |= 1 << 9;
			}

			if (Integer.bitCount(done[0]) == TOTAL_CHECKS)
				helper.succeed();
		});
	}

	/**
	 * Places a water wheel and pins its flow score while {@code pinGate[0]} is
	 * true (null = pin forever). No water is placed: the score is the only
	 * input the physics need, and flowing water inside the structure makes
	 * the GameTest map display churn thousands of log lines per tick.
	 */
	private static WaterWheelBlockEntity placeWheel(GameTestHelper helper, BlockPos pos, int flowScore,
		boolean[] pinGate) {
		helper.setBlock(pos, AllBlocks.WATER_WHEEL.get()
			.defaultBlockState()
			.setValue(WaterWheelBlock.FACING, Direction.EAST));
		WaterWheelBlockEntity wheel = (WaterWheelBlockEntity) helper.getBlockEntity(pos);
		wheel.setFlowScoreAndUpdate(flowScore);
		helper.onEachTick(() -> {
			if ((pinGate == null || pinGate[0]) && !wheel.isRemoved() && wheel.flowScore != flowScore)
				wheel.setFlowScoreAndUpdate(flowScore);
		});
		return wheel;
	}

	private static BlockState shaftX() {
		return AllBlocks.SHAFT.get()
			.defaultBlockState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
	}

	private static BlockState workingPressX() {
		return AllBlocks.MECHANICAL_PRESS.getDefaultState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
	}

	private static final java.util.Map<GameTestHelper, java.util.List<MechanicalPressBlockEntity>> workingLoads = new java.util.WeakHashMap<>();

	private static void prepareWorkingLoads(GameTestHelper helper) {
		var loads = new java.util.ArrayList<MechanicalPressBlockEntity>();
		workingLoads.put(helper, loads);
		// Register once before the test starts. Registering onEachTick while a
		// scheduled callback runs mutates GameTest's task map during iteration.
		helper.onEachTick(() -> {
			for (var press : loads)
				if (!press.isRemoved() && !press.getPressingBehaviour().running)
					press.getPressingBehaviour().start(com.simibubi.create.content.kinetics.press.PressingBehaviour.Mode.WORLD);
		});
	}

	/** Hold a real mechanical cycle active to isolate the 8-torque load in physics fixtures. */
	private static void keepWorking(GameTestHelper helper, MechanicalPressBlockEntity press) {
		press.getPressingBehaviour().start(com.simibubi.create.content.kinetics.press.PressingBehaviour.Mode.WORLD);
		workingLoads.get(helper).add(press);
	}

	private static void placeLoad(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, workingPressX());
		keepWorking(helper, (MechanicalPressBlockEntity) helper.getBlockEntity(pos));
	}

	/** Physical network speed magnitude (RPM), or null while the network is absent. */
	private static Float speedOf(KineticBlockEntity kbe) {
		return kbe.hasNetwork() ? Math.abs(kbe.getSpeed()) : null;
	}

	private static boolean closeTo(Float actual, double expected, double tolerance) {
		return actual != null && Math.abs(actual - expected) <= tolerance;
	}

	/** Asserts every member of the wheel's network spins at exactly r_i × ω. */
	private static void assertMembersConsistent(KineticBlockEntity anyMember, String what) {
		KineticNetwork network = anyMember.getOrCreateNetwork();
		if (network == null)
			throw new GameTestAssertException(what + ": no network");
		INetworkDynamics dynamics = (INetworkDynamics) network;
		float omega = dynamics.differentialcreate$getSpeed();
		for (KineticBlockEntity member : network.members.keySet()) {
			Float ratio = dynamics.differentialcreate$getRatios()
				.get(member);
			if (ratio == null)
				throw new GameTestAssertException(what + ": missing ratio for " + member.getBlockPos());
			float expected = ratio * omega;
			float actual = member.getSpeed();
			if (Math.abs(actual - expected) > 0.01f)
				throw new GameTestAssertException(
					what + ": member " + member.getBlockPos() + " at " + actual + " RPM, expected " + expected);
		}
	}

	private static void assertSpeed(KineticBlockEntity kbe, double expected, double tolerance, String what) {
		Float actual = speedOf(kbe);
		if (actual == null || Math.abs(actual - expected) > tolerance)
			throw new GameTestAssertException(
				what + ": expected " + expected + " RPM but got " + (actual == null ? "no network" : actual));
	}

}
