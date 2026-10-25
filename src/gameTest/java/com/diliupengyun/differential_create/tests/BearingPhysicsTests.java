package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;

import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.MechanicalBearingAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.IControlContraption.RotationMode;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Right-click assembly, finite-torque startup, immediate disassembly, and hot engagement. */
final class BearingPhysicsTests {

	private BearingPhysicsTests() {}

	private static MechanicalBearingBlockEntity bearing(GameTestHelper h, BlockPos pos, RotationMode mode) {
		return bearing(h, pos, Direction.EAST, mode);
	}

	private static MechanicalBearingBlockEntity bearing(GameTestHelper h, BlockPos pos, Direction facing, RotationMode mode) {
		h.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
		MechanicalBearingBlockEntity bearing = h.getBlockEntity(pos);
		((MechanicalBearingAccessor) bearing).differentialcreate$getMovementMode().setValue(mode.ordinal());
		h.assertFalse(bearing.getAllBehaviours().contains(((MechanicalBearingAccessor) bearing).differentialcreate$getMovementMode()),
			"manual bearings do not expose an inactive automatic placement setting");
		NetworkTopology.mark(bearing);
		return bearing;
	}

	private static WaterWheelBlockEntity wheel(GameTestHelper h, BlockPos pos) {
		h.setBlock(pos, AllBlocks.WATER_WHEEL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		WaterWheelBlockEntity wheel = h.getBlockEntity(pos);
		wheel.setFlowScoreAndUpdate(1);
		h.onEachTick(() -> { if (wheel.flowScore != 1) wheel.setFlowScoreAndUpdate(1); });
		NetworkTopology.mark(wheel);
		return wheel;
	}

	private static void shaft(GameTestHelper h, BlockPos pos) {
		h.setBlock(pos, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		NetworkTopology.mark((KineticBlockEntity) h.getBlockEntity(pos));
	}

	private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
		h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
			label + ": expected " + expected + ", got " + actual);
	}

	private static void outputReadout(GameTestHelper h, MechanicalBearingBlockEntity bearing, double expected) {
		var lines = new ArrayList<Component>();
		h.assertTrue(bearing.addToGoggleTooltip(lines, false), "bearing supplies a goggle tooltip");
		var speeds = lines.stream().filter(c -> c.getContents() instanceof TranslatableContents text
			&& text.getKey().equals("gui.differentialcreate.goggles.bearing_output_speed")).toList();
		h.assertTrue(speeds.size() == 1, "bearing goggles label the output speed exactly once");
		var value = (TranslatableContents) speeds.getFirst().getContents();
		near(h, expected, Double.parseDouble(value.getArgs()[0].toString()), .051, "goggles display current output RPM");
		h.assertFalse(lines.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents text
			&& text.getKey().equals("gui.differentialcreate.goggles.speed")), "no obsolete shaft speed remains in the bearing readout");
	}

	static void lifecycle(GameTestHelper h) {
		BlockPos pos = new BlockPos(4, 3, 5);
		wheel(h, pos.west(2));
		shaft(h, pos.west());
		var bearing = bearing(h, pos, RotationMode.ROTATE_PLACE);
		for (int x = 1; x <= 3; x++) h.setBlock(pos.east(x), Blocks.SLIME_BLOCK.defaultBlockState());
		h.setBlock(pos.east(4), Blocks.OAK_PLANKS.defaultBlockState());
		h.useBlock(pos);
		bearing.tick();
		h.assertTrue(bearing.isRunning(), "right-click assembles a stationary structure before the drive starts");
		near(h, 0, ElasticKinetics.state(bearing).physicalSpeed, 0, "cold assembly starts the output at exactly zero RPM");
		int[] phase = {0}, coastingTicks = {0}, standbyTicks = {0};
		double[] cutSpeed = {0};
		boolean[] rampSeen = {false}, coastingSeen = {false};
		h.onEachTick(() -> {
			if (h.getTick() < 5) return;
			h.assertFalse(bearing.isRemoved(), "finite-torque cold startup preserves the bearing");
			if (phase[0] == 0 && bearing.isRunning()) {
				h.assertTrue(bearing.getMovedContraption().getContraption().getBlocks().size() == 4, "all four structural blocks assembled");
				near(h, .0104, InertiaRegistry.getInertia(bearing), 1e-7, "plain structure contributes trial-balanced inertia");
				near(h, .3 * bearing.getSpeed(), bearing.getAngularSpeed(), 1e-5, "structure follows physical shaft RPM during startup");
				if (bearing.getSpeed() > 0 && bearing.getSpeed() < 10) rampSeen[0] = true;
				double equilibrium = RotationMath.equilibrium(64, 4, 0, MachineActivity.quadraticDrag(bearing));
				if (Math.abs(bearing.getSpeed() - equilibrium) < .02) {
					h.assertTrue(rampSeen[0], "startup accelerates through intermediate speeds");
					h.setBlock(pos.west(), Blocks.AIR.defaultBlockState());
					NetworkTopology.flush();
					h.assertTrue(bearing.getTheoreticalSpeed() != 0, "coasting topology retains its nominal reference");
					phase[0] = 1;
					cutSpeed[0] = bearing.getSpeed();
				}
			} else if (phase[0] == 1) {
				h.assertTrue(bearing.getSpeed() > 0 && bearing.isRunning() && bearing.getMovedContraption() != null,
					"air-only coasting preserves the manual assembly without a dry-friction stopping threshold");
				if (bearing.getSpeed() < cutSpeed[0] - .0001) coastingSeen[0] = true;
				if (++coastingTicks[0] >= 50) {
					h.assertTrue(coastingSeen[0], "cutting the drive loses speed and energy to air");
					h.useBlock(pos);
					h.assertTrue(bearing.getMovedContraption() == null && h.getBlockState(pos.east()).is(Blocks.SLIME_BLOCK),
						"right-click immediately restores a moving structure to world blocks");
					h.assertFalse(bearing.isRunning() || MachineActivity.active(bearing), "manual disassembly returns to standby");
					near(h, 0, MachineActivity.impact(bearing), 0, "standby torque is zero");
					near(h, .01, InertiaRegistry.getInertia(bearing), 1e-8, "disassembly immediately removes structure inertia");
					outputReadout(h, bearing, 0);
					NetworkTopology.flush();
					phase[0] = 2;
				}
			} else if (phase[0] == 2) {
				h.assertFalse(bearing.isRunning(), "standby remains stable after stopping");
				if (++standbyTicks[0] >= 10) h.succeed();
			}
		});
	}

	static void manualControl(GameTestHelper h) {
		BlockPos neverPos = new BlockPos(4, 3, 4), returnedPos = new BlockPos(4, 3, 12), stalledPos = new BlockPos(4, 3, 20);
		var never = bearing(h, neverPos, RotationMode.ROTATE_NEVER_PLACE);
		var returned = bearing(h, returnedPos, RotationMode.ROTATE_PLACE_RETURNED);
		wheel(h, stalledPos.west(2)); shaft(h, stalledPos.west());
		var stalled = bearing(h, stalledPos, RotationMode.ROTATE_PLACE);
		var controllers = new MechanicalBearingBlockEntity[]{never, returned, stalled};
		var positions = new BlockPos[]{neverPos, returnedPos, stalledPos};
		for (var position : positions) h.setBlock(position.east(), Blocks.OAK_PLANKS.defaultBlockState());
		BlockPos emptyPos = new BlockPos(4, 3, 28);
		var empty = bearing(h, emptyPos, RotationMode.ROTATE_PLACE);
		h.runAfterDelay(5, () -> {
			NetworkTopology.flush();
			h.assertTrue(stalled.getSpeed() > 0 && !stalled.isRunning(), "a powered input does not automatically assemble the structure");
			for (int index = 0; index < controllers.length; index++) {
				var controller = controllers[index];
				h.useBlock(positions[index]);
				// A network notification between right-click and the next tick must
				// neither erase the user's request nor create a second automatic one.
				NetworkTopology.mark(controller); NetworkTopology.flush();
				controller.tick();
				h.assertTrue(controller.isRunning(), "right-click assembles at zero or nonzero input RPM in every saved placement mode");
				near(h, 0, controller.getSpeed(), 0, "new output starts at exactly zero RPM");
				controller.setAngle(120);
				ElasticKinetics.state(controller).physicalSpeed = 0;
				controller.onSpeedChanged(1);
			}
			for (int i = 0; i < 4; i++) { never.tick(); returned.tick(); stalled.tick(); }
			h.assertTrue(never.isRunning() && never.getMovedContraption() != null, "never-place retains a stopped structure");
			h.assertTrue(returned.isRunning(), "a stopped structure remains assembled away from home");
			h.assertTrue(stalled.isRunning() && MachineActivity.loadTorque(stalled, 0) == 0,
				"an idle assembled structure has no fixed starting resistance");
			returned.setAngle(10);
			returned.tick(); returned.tick();
			h.assertTrue(returned.isRunning(), "returning home cannot automatically disassemble a stopped structure");
			ElasticKinetics.state(stalled).physicalSpeed = .0001;
			stalled.tick();
			h.assertTrue(stalled.isRunning(), "very slow motion remains assembled");
			ElasticKinetics.state(empty).physicalSpeed = 8;
			h.useBlock(emptyPos); empty.tick();
			h.assertFalse(empty.isRunning(), "an empty face fails assembly normally");
			near(h, 8, empty.getSpeed(), 1e-6, "failed assembly cannot reset an existing rotor");
		});
		h.runAfterDelay(15, () -> {
			h.assertTrue(stalled.getSpeed() > 0 && stalled.isRunning(), "the manually assembled powered output accelerates");
			for (int index = 0; index < controllers.length; index++) {
				var controller = controllers[index];
				double before = controller.getSpeed();
				outputReadout(h, controller, before);
				var mirror = new MechanicalBearingBlockEntity(controller.getType(), controller.getBlockPos(), controller.getBlockState());
				mirror.setLevel(h.getLevel());
				mirror.readClient(controller.writeClient(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
				ElasticKinetics.state(mirror).physicalSpeed = before;
				h.assertTrue(mirror.isRunning(), "client update carries the assembled state");
				h.useBlock(positions[index]);
				h.assertFalse(controller.isRunning(), "right-click immediately disassembles both moving and stopped structures");
				h.assertTrue(h.getBlockState(positions[index].east()).is(Blocks.OAK_PLANKS), "manual disassembly restores world blocks");
				near(h, before, controller.getSpeed(), 1e-6, "disassembly preserves the remaining input-side rotor speed");
				h.assertFalse(MachineActivity.active(controller), "manual disassembly immediately removes the working load");
				near(h, .01, InertiaRegistry.getInertia(controller), 1e-8, "manual disassembly immediately removes the attachment inertia");
				outputReadout(h, controller, 0);
				var stopped = controller.writeClient(new CompoundTag(), h.getLevel().registryAccess());
				h.assertFalse(stopped.getBoolean("Running") || stopped.getBoolean("DCMachineWorking"),
					"disassembly immediately synchronizes standby");
				mirror.readClient(stopped, h.getLevel().registryAccess());
				near(h, before, ElasticKinetics.state(mirror).physicalSpeed, 0, "old rotor speed can arrive before its next physical update");
				outputReadout(h, mirror, 0);
			}
			NetworkTopology.flush();
		});
		h.runAfterDelay(35, () -> {
			h.assertTrue(stalled.getSpeed() > 0, "the powered input keeps turning after manual disassembly");
			for (int index = 0; index < controllers.length; index++) {
				h.assertFalse(controllers[index].isRunning(), "speed changes and topology rebuilds cannot reassemble a stopped bearing");
				h.assertTrue(h.getBlockState(positions[index].east()).is(Blocks.OAK_PLANKS), "restored blocks remain in the world");
				outputReadout(h, controllers[index], 0);
			}
			h.succeed();
		});
	}

	static void radiusInertia(GameTestHelper h) {
		var facings = Direction.values();
		var controllers = new MechanicalBearingBlockEntity[facings.length];
		var positions = new BlockPos[facings.length];
		for (int index = 0; index < facings.length; index++) {
			positions[index] = new BlockPos(8 + index % 3 * 12, 8, 8 + index / 3 * 16);
			controllers[index] = bearing(h, positions[index], facings[index], RotationMode.ROTATE_NEVER_PLACE);
		}
		h.runAfterDelay(5, () -> {
			for (int index = 0; index < facings.length; index++) {
				var facing = facings[index];
				var controller = controllers[index];
				var pos = positions[index];
				var anchor = pos.relative(facing);
				Direction radial = facing.getAxis() == Direction.Axis.X ? Direction.UP : Direction.EAST;
				Direction second = facing.getAxis() == Direction.Axis.Z ? Direction.UP : Direction.SOUTH;
				BlockState[] materials = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState(),
					AllBlocks.FLYWHEEL.getDefaultState()};
				for (var material : materials) {
					h.setBlock(anchor, Blocks.SLIME_BLOCK.defaultBlockState());
					h.setBlock(anchor.relative(facing), Blocks.SLIME_BLOCK.defaultBlockState());
					h.setBlock(anchor.relative(facing, 2), material);
					for (int sign : new int[]{-1, 1}) {
						h.setBlock(anchor.relative(radial, sign), Blocks.SLIME_BLOCK.defaultBlockState());
						h.setBlock(anchor.relative(radial, 2 * sign), material);
					}
					h.setBlock(anchor.relative(second), Blocks.SLIME_BLOCK.defaultBlockState());
					h.setBlock(anchor.relative(second, 2), material);
					h.setBlock(anchor.relative(radial).relative(second), material);
					h.useBlock(pos); controller.tick(); NetworkTopology.flush();
					ElasticKinetics.refresh(controller.getOrCreateNetwork());
					h.assertTrue(controller.isRunning(), "radius fixture assembles for " + facing + " with " + material);
					h.assertTrue(controller.getMovedContraption().getContraption().getBlocks().size() == 10,
						"radius fixture contains ten equal-mass blocks");
					// Ten cubes, sum of squared perpendicular distances = 17.
					// The three cubes on the axis add no distance-dependent inertia.
					near(h, .0212, InertiaRegistry.getInertia(controller), 1e-7,
						"both radial coordinates, their signs and their squares contribute; axial depth/material do not");
					near(h, .0212, ElasticKinetics.state(controller).lastInertia, 1e-7, "assembly immediately installs radial inertia");
					near(h, .0212, ((INetworkDynamics) controller.getOrCreateNetwork()).differentialcreate$data().balance.j(), 1e-7,
						"the solver receives the structure's radial inertia");
					near(h, 0, controller.getSpeed(), 0, "radial inertia keeps the assembly output at rest");
					h.useBlock(pos);
					near(h, .01, InertiaRegistry.getInertia(controller), 1e-8, "disassembly removes the entire radial contribution");
				}
			}
			h.succeed();
		});
	}

	static void hotAssembly(GameTestHelper h) {
		var fast = ElasticKineticsTests.flywheel(h, 2, 5);
		var gentle = ElasticKineticsTests.flywheel(h, 2, 15);
		BlockPos fastPos = new BlockPos(4, 3, 5), gentlePos = new BlockPos(4, 3, 15);
		shaft(h, fastPos.west()); shaft(h, gentlePos.west());
		var fastBearing = bearing(h, fastPos, RotationMode.ROTATE_NEVER_PLACE);
		var gentleBearing = bearing(h, gentlePos, RotationMode.ROTATE_NEVER_PLACE);
		h.runAfterDelay(5, () -> {
			NetworkTopology.flush();
			var controllers = new MechanicalBearingBlockEntity[]{fastBearing, gentleBearing};
			var positions = new BlockPos[]{fastPos, gentlePos};
			for (int index = 0; index < controllers.length; index++) {
				var controller = controllers[index];
				double speed = controller == fastBearing ? 512 : -16;
				KineticBlockEntity rotor = controller == fastBearing ? fast : gentle;
				ElasticKinetics.state(rotor).physicalSpeed = speed;
				ElasticKinetics.state(h.getBlockEntity(positions[index].west())).physicalSpeed = speed;
				ElasticKinetics.state(controller).physicalSpeed = speed;
				// Also exercise assembly before a member's first inertia refresh.
				ElasticKinetics.state(controller).lastInertia = Double.NaN;
				h.setBlock(positions[index].east(), Blocks.OAK_PLANKS.defaultBlockState());
				h.useBlock(positions[index]); controller.tick();
				h.assertTrue(controller.isRunning(), "right-click assembles a structure onto a rotating input");
				near(h, 0, controller.getSpeed(), 0, "hot assembly resets only the output to exactly zero RPM");
				near(h, 0, controller.getInterpolatedAngle(0), 0, "the new structure does not turn during the assembly tick");
				near(h, speed, rotor.getSpeed(), 1e-6, "assembly cannot instantly synchronize the upstream rotor");
				NetworkTopology.flush();
				near(h, 0, controller.getSpeed(), 0, "network rebuild preserves the stopped output");
				near(h, speed, rotor.getSpeed(), 1e-6, "network rebuild preserves the upstream rotor speed");
				var network = (INetworkDynamics) controller.getOrCreateNetwork();
				h.assertTrue(network.differentialcreate$data().connections.stream().allMatch(edge -> edge.clutch == null
					&& edge.stiffness() == PhysicsTuning.TORSIONAL_STIFFNESS),
					"bearing uses the normal high-stiffness contact without a built-in friction clutch");
			}
		});
		h.runAfterDelay(10, () -> {
			h.assertTrue(h.getBlockState(fastPos.west()).isAir(), "large hot engagement fractures the weaker shaft");
			h.assertFalse(fast.isRemoved() || fastBearing.isRemoved(), "stronger rotors survive the shock");
			h.assertFalse(h.getBlockState(gentlePos.west()).isAir(), "gentle reverse hot engagement remains connected");
			h.assertTrue(gentleBearing.getSpeed() < 0, "the stationary output accelerates in the reverse input direction");
			near(h, .3 * gentleBearing.getSpeed(), gentleBearing.getAngularSpeed(), 1e-5, "hot-engaged structure tracks actual speed");
			h.succeed();
		});
	}

	static void reload(GameTestHelper h) {
		BlockPos pos = new BlockPos(4, 3, 5);
		var original = bearing(h, pos, RotationMode.ROTATE_NEVER_PLACE);
		h.runAfterDelay(5, () -> {
			h.setBlock(pos.east(), Blocks.SLIME_BLOCK.defaultBlockState());
			h.setBlock(pos.east().above(), Blocks.SLIME_BLOCK.defaultBlockState());
			h.setBlock(pos.east().above(2), Blocks.OAK_PLANKS.defaultBlockState());
			original.assemble(); NetworkTopology.flush();
			ElasticKinetics.state(original).physicalSpeed = 7;
			original.setAngle(123);
			var entity = original.getMovedContraption();
			var tag = original.saveWithFullMetadata(h.getLevel().registryAccess());
			near(h, .0133, tag.getFloat("DCStructureInertia"), 1e-8, "running assembly saves its radial inertia");
			for (var saved : new CompoundTag[]{tag}) {
				var candidate = (MechanicalBearingBlockEntity) BlockEntity.loadStatic(pos, original.getBlockState(), saved, h.getLevel().registryAccess());
				candidate.setLevel(h.getLevel());
				near(h, .0133, InertiaRegistry.getInertia(candidate), 1e-8, "current save restores the structure contribution");
				near(h, 7, ElasticKinetics.state(candidate).physicalSpeed, 0, "reload preserves saved RPM");
				near(h, 123, candidate.getInterpolatedAngle(0), 1e-6, "reload preserves the assembly angle");
				if (saved.contains("DCStructureDrag"))
					near(h, tag.getDouble("DCStructureDrag"), ContraptionPhysics.airDrag(candidate), 1e-12, "reload preserves drag independently of inertia");
				var rewritten = new CompoundTag(); ContraptionPhysics.write(candidate, rewritten);
				ContraptionPhysics.read(candidate, rewritten, false);
				near(h, .0133, ElasticKinetics.state(candidate).pendingStructureInertia, 1e-8, "resaving and reading cannot apply the reduction twice");
			}
			NetworkTopology.mark(original);
			var restored = (MechanicalBearingBlockEntity) BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), tag,
				h.getLevel().registryAccess());
			h.getLevel().setBlockEntity(restored);
			NetworkTopology.mark(restored); NetworkTopology.flush();
			// A block entity can load and participate in physics before its moving entity.
			ElasticKinetics.refresh(restored.getOrCreateNetwork());
			near(h, .0133, InertiaRegistry.getInertia(restored), 1e-8, "late entity reattachment retains the saved radial inertia");
			near(h, 7, restored.getSpeed(), 1e-6, "loading the controller keeps physical RPM");
			restored.attach(entity); InertiaRegistry.checkAttachments(restored);
			ElasticKinetics.refresh(restored.getOrCreateNetwork());
			near(h, 7, restored.getSpeed(), 1e-6, "entity reattachment cannot slow the same saved rotor a second time");
			near(h, 123, restored.getInterpolatedAngle(0), 1e-6, "reattachment retains the saved structure angle");
			h.succeed();
		});
	}
}
