package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;

final class ContinuousActorTests {
	private static final BlockPos POS = new BlockPos(6, 8, 12);
	private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
		h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": expected " + expected + ", got " + actual);
	}
	private static void speed(MechanicalBearingBlockEntity be, double rpm) {
		var network = (INetworkDynamics) be.getOrCreateNetwork();
		network.differentialcreate$setSpeed(rpm / network.differentialcreate$getRatios().get(be));
	}
	private static void setup(GameTestHelper h, BlockState tool) {
		h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		for (int y = 0; y < 4; y++) h.setBlock(POS.east().above(y), Blocks.SLIME_BLOCK);
		h.setBlock(POS.east().above(4), tool);
	}
	static void movingDrill(GameTestHelper h) {
		movingDrill(h, Direction.SOUTH, 512);
	}
	static void reverseDrill(GameTestHelper h) {
		movingDrill(h, Direction.NORTH, -512);
	}
	static void latePlacedTarget(GameTestHelper h) {
		h.runAfterDelay(5, () -> {
			for (int kind = 0; kind < 2; kind++) for (int sign : new int[]{-1, 1}) {
				setup(h, (kind == 0 ? AllBlocks.MECHANICAL_DRILL : AllBlocks.MECHANICAL_SAW).getDefaultState()
					.setValue(BlockStateProperties.FACING, Direction.EAST));
				MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
				var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
				var behaviour = MovementBehaviour.REGISTRY.get(context.state);
				BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
				h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
				speed(be, sign * 16); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
				BlockPos after = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
				h.assertTrue(after.equals(target) && context.data.getLong("DCActorCell") == target.asLong(), "tool already entered and moved within its empty contact cell");
				h.assertTrue(Math.abs(root.getAngle(1)) > 4, "late placement happens after actual platform movement");
				h.getLevel().setBlockAndUpdate(target, (kind == 0 ? Blocks.STONE : Blocks.OAK_LOG).defaultBlockState());
				speed(be, sign * 64); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
				h.assertTrue(h.getLevel().getBlockState(target).isAir(), "drill/saw detects a newly placed target without re-entering its cell, kind=" + kind + ", direction=" + sign);
				h.assertFalse(context.stall || root.isStalled(), "refreshing a target does not pause the platform");
				root.setAngle(0); be.disassemble();
			}
			h.succeed();
		});
	}
	static void stationaryContactRefresh(GameTestHelper h) {
		h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(POS.east(), Blocks.SLIME_BLOCK);
		h.setBlock(POS.east(2), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
			var target = h.absolutePos(POS.east(3));
			h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
			speed(be, 0); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(context.data.contains("BreakingPos"), "stationary contact acquires a late target");
			near(h, 0, context.data.getDouble("DCActorTravel"), 0, "acquiring a target at zero speed does not grant free cutting work");
			speed(be, 128); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getLevel().getBlockState(target).isAir(), "power finishes the late coaxial target");
			// Replace the completed target without moving the contact point to a new voxel.
			h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			speed(be, 1); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(context.data.getDouble("DCActorTravel") > 0, "same-cell replacement starts a new partial cut");
			h.getLevel().setBlockAndUpdate(target, Blocks.BEDROCK.defaultBlockState());
			speed(be, 0); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertFalse(context.data.contains("BreakingPos"), "an unbreakable replacement cancels the previous cut");
			h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			context.disabled = true; root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertFalse(context.data.contains("BreakingPos"), "disabled drill does not acquire a new target");
			context.disabled = false; root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(context.data.contains("BreakingPos"), "reenabling the drill reacquires the same contact cell");
			near(h, 0, context.data.getDouble("DCActorTravel"), 0, "replacement does not inherit a previous block's cutting energy");
			speed(be, 128); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getLevel().getBlockState(target).isAir(), "reenabled drill cuts the replacement normally");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	private static void movingDrill(GameTestHelper h, Direction travelDirection, double rpm) {
		setup(h, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption();
			var context = root.getContraption().getActors().getFirst().getRight();
			var targets = new BlockPos[]{POS.east(2).above(4), POS.east(2).above(4).relative(travelDirection), POS.east(2).above(3).relative(travelDirection, 2)};
			for (var target : targets) h.setBlock(target, Blocks.STONE);
			speed(be, rpm); root.tickActors();
			h.assertTrue(ContraptionActorWork.of(context).motionManaged(), "native actor tick opts into the physical sweep");
			double before = ElasticKinetics.state(be).angle;
			MachiningClock.advance(h.getLevel());
			double delta = (ElasticKinetics.state(be).angle - before) * 360;
			var pose = (com.diliupengyun.differential_create.kinetics.IBearingMotion) be;
			near(h, delta, pose.differentialcreate$syncedAngle(), 2e-5, "snapshot includes the actor's paid travel before the native tick");
			near(h, delta, pose.differentialcreate$syncedAngle(), 2e-5, "repeated snapshots leave pending travel untouched");
			h.assertTrue(Math.signum(delta) == Math.signum(rpm) && Math.abs(delta) > 40, "structure keeps rotating while the drill cuts");
			for (var target : targets)
				h.assertTrue(h.getBlockState(target).isAir(), "high-speed drill visits and cuts intermediate target " + target);
			h.assertFalse(context.stall || root.getContraption().stalled || root.isStalled(), "cutting never sets the native global pause flag");
			be.tick();
			near(h, delta, root.getAngle(1), 2e-5, "native controller consumes the same integrated travel as its actors");
			near(h, delta, pose.differentialcreate$syncedAngle(), 2e-5, "snapshot cannot replay the travel after its native consumption");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	static void stalledDrill(GameTestHelper h) {
		setup(h, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
			h.setBlock(POS.east(2).above(4), Blocks.STONE);
			speed(be, 1); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			near(h, 0, NetworkDynamics.preciseSpeed(be), 0, "cutting exhausts a weak rotor's kinetic energy");
			double progress = context.data.getDouble("DCActorTravel");
			h.assertTrue(progress > 0 && progress < 1500, "physical stall retains a partial cutting stroke");
			for (int tick = 0; tick < 5; tick++) { root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick(); }
			near(h, progress, context.data.getDouble("DCActorTravel"), 1e-9, "a stopped structure cannot continue machining");
			h.assertFalse(context.stall, "zero physical speed needs no artificial actor pause");
			speed(be, 64); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getBlockState(POS.east(2).above(4)).isAir(), "restoring power finishes the retained cut");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	static void completionTolerance(GameTestHelper h) {
		setup(h, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
			var work = ContraptionActorWork.of(context);
			var target = h.absolutePos(POS.east(2).above(4));
			h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState());
			speed(be, 0); root.tickActors();
			ContraptionMotion.begin(h.getLevel());
			try {
				h.assertTrue(Double.isFinite(work.differentialcreate$remainingTravel()), "stopped drill acquires the obsidian contact");
			} finally { ContraptionMotion.end(h.getLevel()); }
			double required = Blocks.OBSIDIAN.defaultBlockState().getDestroySpeed(h.getLevel(), target) * ContraptionActorWork.CYCLE_TRAVEL;
			// These feed/progress values came from the world that exhausted the event loop.
			double feed = 6.780285517591736, savedProgress = 49999.99997250285;
			double ratio = required / feed, unfinished = required - 4 * MachineWork.EPSILON * ratio;
			context.data.putDouble("DCActorFeed", feed);
			context.data.putDouble("DCActorTravel", unfinished);
			MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.OBSIDIAN), "work outside the shaft tolerance cannot finish at zero speed");
			near(h, unfinished, context.data.getDouble("DCActorTravel"), 0, "zero speed cannot pay an unfinished cutting stroke");
			context.data.putDouble("DCActorTravel", savedProgress);
			h.assertTrue(required - savedProgress > MachineWork.EPSILON
				&& (required - savedProgress) / ratio < MachineWork.EPSILON, "saved cut separates work units from shaft travel units");
			MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getLevel().getBlockState(target).isAir(), "the saved near-complete cut resolves without repeating a zero-time event");
			h.assertFalse(context.data.contains("BreakingPos") || context.data.contains("DCActorTravel"), "completion clears the retained cutting job");
			near(h, 0, root.getAngle(1), 0, "completion tolerance cannot move a stopped structure");
			h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState());
			root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.OBSIDIAN), "the replacement block needs a new paid stroke");
			near(h, 0, context.data.getDouble("DCActorTravel"), 0, "a new contact cannot inherit the completed cut's work");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	static void mixedActorNativeStall(GameTestHelper h) {
		setup(h, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(POS.east().above().south(), AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption();
			var drill = root.getContraption().getActors().stream().filter(a -> AllBlocks.MECHANICAL_DRILL.has(a.getLeft().state())).findFirst().orElseThrow().getRight();
			var hand = root.getContraption().getActors().stream().filter(a -> AllBlocks.DEPLOYER.has(a.getLeft().state())).findFirst().orElseThrow().getRight();
			var deployer = MovementBehaviour.REGISTRY.get(hand.state);
			hand.disabled = true;
			hand.blockEntityData.putString("Mode", "PUNCH");
			hand.data.put("HeldItem", new ItemStack(Items.DIAMOND_PICKAXE).save(h.getLevel().registryAccess()));
			speed(be, 64); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(ContraptionActorWork.of(drill).motionManaged(), "mixed fixture binds its continuous drill sweep");
			double held = root.getAngle(1);
			h.assertTrue(held > 0, "fixture reaches a nonzero structure pose before the dwell");
			var pose = (IBearingMotion) be;
			BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(hand.localPos).add(deployer.getActiveAreaOffset(hand)), 1));
			h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState());
			hand.disabled = false;
			root.tickActors();
			h.assertTrue(ContraptionActorWork.of(hand).pending() && hand.stall && root.isStalled() && root.getContraption().stalled,
				"a real deployer visit pauses the mixed structure for its paid action");
			ContraptionMotion.begin(h.getLevel());
			try {
				ContraptionActorWork.of(drill).differentialcreate$remainingTravel();
				h.assertTrue(ContraptionActorWork.of(drill).differentialcreate$wakeDirection() == 0, "native actor dwell never requests a physical feed wake-up");
			} finally { ContraptionMotion.end(h.getLevel()); }
			for (int tick = 0; tick < 3; tick++) {
				speed(be, 16); root.tickActors();
				double shaftAngle = ElasticKinetics.state(be).angle;
				MachiningClock.advance(h.getLevel());
				h.assertTrue(ElasticKinetics.state(be).angle > shaftAngle && ContraptionActorWork.of(hand).pending(),
					"the paused structure's shaft still turns to pay for its dwelling deployer");
				near(h, 0, ContraptionMotion.angular(root), 0, "native pause generates no pending output rotation");
				near(h, held, pose.differentialcreate$syncedAngle(), 2e-5, "the authoritative snapshot stays at the paused server pose");
				near(h, held, pose.differentialcreate$syncedAngle(), 2e-5, "repeated paused snapshots cannot accumulate shaft travel");
				if (tick == 2) {
					var saved = root.saveWithoutId(new CompoundTag());
					near(h, 0, saved.getCompound("DCOutputMotion").getDouble("Angle"), 0, "paused NBT contains no unconsumed shaft rotation");
					ContraptionMotion.forget(root); ContraptionMotion.load(root, saved); be.attach(root);
					near(h, held, root.getAngle(1), 2e-5, "restoring a paused output cannot advance the structure");
				} else be.tick();
				near(h, held, root.getAngle(1), 2e-5, "the native paused controller and its snapshot remain at the same pose");
				near(h, held, pose.differentialcreate$syncedAngle(), 2e-5, "consuming a paused output cannot publish phantom motion");
			}
			hand.disabled = true; deployer.cancelStall(hand); root.tickActors();
			h.assertFalse(root.isStalled() || root.getContraption().stalled, "disabling the dwelling actor releases the native pause");
			speed(be, 0); be.tick();
			near(h, held, root.getAngle(1), 2e-5, "releasing the pause cannot replay any of its shaft travel");
			speed(be, 64); root.tickActors(); MachiningClock.advance(h.getLevel());
			double paid = ContraptionMotion.angular(root);
			h.assertTrue(paid > 0, "new unpaused movement still produces real paid feed");
			near(h, held + paid, pose.differentialcreate$syncedAngle(), 2e-5, "the resumed snapshot includes only newly paid feed");
			var saved = root.saveWithoutId(new CompoundTag());
			near(h, paid, saved.getCompound("DCOutputMotion").getDouble("Angle"), 0, "unpaused NBT retains its real pending feed");
			ContraptionMotion.forget(root); ContraptionMotion.load(root, saved); be.attach(root);
			near(h, held + paid, root.getAngle(1), 2e-5, "restoring resumed feed consumes its displacement exactly once");
			speed(be, 0); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			near(h, held + paid, root.getAngle(1), 2e-5, "a later native tick cannot replay resumed or paused feed");
			near(h, root.getAngle(1), pose.differentialcreate$syncedAngle(), 2e-5, "the final snapshot agrees with the native server pose");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	private static void farmSetup(GameTestHelper h, BlockState tool) {
		h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
		for (int x = 0; x < 4; x++) h.setBlock(POS.above().east(x), Blocks.SLIME_BLOCK);
		h.setBlock(POS.above().east(4), tool);
	}
	static void movingFarmTools(GameTestHelper h) {
		h.runAfterDelay(5, () -> {
			for (int kind = 0; kind < 2; kind++) {
				farmSetup(h, (kind == 0 ? AllBlocks.MECHANICAL_HARVESTER : AllBlocks.MECHANICAL_PLOUGH).getDefaultState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
				MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
				var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
				var behaviour = MovementBehaviour.REGISTRY.get(context.state);
				BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
				h.getLevel().setBlockAndUpdate(target.below(), (kind == 0 ? Blocks.FARMLAND : Blocks.DIRT).defaultBlockState());
				h.getLevel().setBlockAndUpdate(target, kind == 0 ? Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7) : Blocks.AIR.defaultBlockState());
				speed(be, 64); root.tickActors(); MachiningClock.advance(h.getLevel());
				h.assertTrue(kind == 0 ? h.getLevel().getBlockState(target).getValue(BlockStateProperties.AGE_7) == 0
					: h.getLevel().getBlockState(target.below()).is(Blocks.FARMLAND), "moving farm tool executes native harvesting/tilling");
				h.assertFalse(context.stall || root.isStalled(), "farm work leaves the platform moving");
				be.tick(); h.assertTrue(Math.abs(root.getAngle(1)) > 10, "farm platform actually advances while processing its target");
				root.setAngle(0); be.disassemble();
			}
			h.succeed();
		});
	}
	static void movingRoller(GameTestHelper h) {
		farmSetup(h, AllBlocks.MECHANICAL_ROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
		h.setBlock(POS.above().north(), Blocks.CHEST);
		((ChestBlockEntity) h.getBlockEntity(POS.above().north())).setItem(0, new ItemStack(Items.STONE, 16));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
			context.blockEntityData.putInt("ScrollValue", 1);
			context.blockEntityData.put("Filter", new ItemStack(Items.STONE).save(h.getLevel().registryAccess()));
			var behaviour = MovementBehaviour.REGISTRY.get(context.state);
			BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
			h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); h.getLevel().setBlockAndUpdate(target.below(), Blocks.BEDROCK.defaultBlockState());
			speed(be, 64); root.tickActors(); MachiningClock.advance(h.getLevel());
			h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "moving roller places its paid material");
			h.assertFalse(context.stall || root.isStalled(), "paving removes the native waiting-tick pause");
			Double pending = ContraptionMotion.angular(root);
			h.assertTrue(pending != null && pending > 0, "roller's platform has real pending rotation");
			CompoundTag saved = new CompoundTag(); ContraptionMotion.save(root, saved); ContraptionMotion.forget(root); ContraptionMotion.load(root, saved);
			near(h, pending, ContraptionMotion.angular(root), 0, "saving retains the paid output motion before the next controller tick");
			be.tick(); near(h, pending, root.getAngle(1), 2e-5, "roller controller consumes its paid displacement once");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	static void movingPiston(GameTestHelper h) {
		h.setBlock(POS, AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		for (int x = 1; x <= 4; x++) h.setBlock(POS.west(x), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(POS.east(), Blocks.SLIME_BLOCK);
		h.setBlock(POS.east().above(), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			LinearActuatorBlockEntity be = h.getBlockEntity(POS);
			NetworkTopology.mark(be); NetworkTopology.flush();
			var network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(-128);
			try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); }
			h.assertTrue(be.running && be.movedContraption != null, "native piston assembles its drill");
			NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.movedContraption; var context = root.getContraption().getActors().getFirst().getRight();
			var behaviour = MovementBehaviour.REGISTRY.get(context.state);
			var point = root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1);
			BlockPos target = BlockPos.containing(point); h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			for (int tick = 0; tick < 3; tick++) {
				network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(-128 / network.differentialcreate$getRatios().get(be));
				root.tickActors(); Vec3 start = root.position(); MachiningClock.advance(h.getLevel());
				Vec3 planned = ContraptionMotion.linear(root); h.assertTrue(planned != null && planned.x > 0, "cutting piston retains forward feed");
				be.tick(); near(h, start.x + planned.x, root.position().x, 2e-5, "physical speed notifications do not consume a tick without moving the carriage");
				h.assertFalse(root.isStalled() || context.stall, "piston drilling leaves the native pause flag clear");
			}
			h.assertTrue(h.getLevel().getBlockState(target).isAir(), "piston cuts stone while its carriage advances");
			be.disassemble(); h.succeed();
		});
	}
	static void pistonHardTargetContinuity(GameTestHelper h) {
		h.setBlock(POS, AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		for (int x = 1; x <= 4; x++) h.setBlock(POS.west(x), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(POS.east(), Blocks.SLIME_BLOCK);
		h.setBlock(POS.east().above(), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			LinearActuatorBlockEntity be = h.getBlockEntity(POS);
			NetworkTopology.mark(be); NetworkTopology.flush();
			var network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(-128);
			try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); }
			h.assertTrue(be.running && be.movedContraption != null, "native piston assembles its drilling fixture");
			NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.movedContraption; var context = root.getContraption().getActors().getFirst().getRight();
			var behaviour = MovementBehaviour.REGISTRY.get(context.state);
			BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
			h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
			network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(-128 / network.differentialcreate$getRatios().get(be));
			root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(be.offset > .2 && be.offset < .3, "fixture starts at a real fractional piston extension");
			h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState());
			network.differentialcreate$setSpeed(-32 / network.differentialcreate$getRatios().get(be));
			root.tickActors(); Vec3 start = root.position(); float offset = be.offset; MachiningClock.advance(h.getLevel());
			Vec3 planned = ContraptionMotion.linear(root);
			near(h, 0, NetworkDynamics.preciseSpeed(be), 0, "obsidian exhausts the weak rotor and causes a physical stop");
			h.assertTrue(context.data.getDouble("DCActorTravel") > 0 && h.getLevel().getBlockState(target).is(Blocks.OBSIDIAN), "hard target retains a partly paid cutting stroke");
			be.tick();
			near(h, offset + planned.x, be.offset, 2e-6, "stall notification retains the fractional extension instead of rounding it");
			near(h, start.x + planned.x, root.position().x, 2e-5, "stall notification cannot move the drilling carriage backward");
			network.differentialcreate$setSpeed(-512 / network.differentialcreate$getRatios().get(be));
			root.tickActors(); start = root.position(); MachiningClock.advance(h.getLevel()); planned = ContraptionMotion.linear(root); be.tick();
			h.assertTrue(h.getLevel().getBlockState(target).isAir(), "restoring energy finishes the obsidian cut");
			near(h, start.x + planned.x, root.position().x, 2e-5, "breaking obsidian leaves the carriage on its physically integrated path");
			// An actual reversal still retracts, but by its signed integrated displacement.
			network.differentialcreate$setSpeed(64 / network.differentialcreate$getRatios().get(be));
			root.tickActors(); start = root.position(); offset = be.offset; MachiningClock.advance(h.getLevel()); planned = ContraptionMotion.linear(root);
			h.assertTrue(planned.x < 0, "reversing the shaft produces real backward movement"); be.tick();
			near(h, offset + planned.x, be.offset, 2e-6, "direction change preserves the existing fractional extension");
			near(h, start.x + planned.x, root.position().x, 2e-5, "true retraction uses the signed physical travel without a position jump");
			network.differentialcreate$setSpeed(0); root.tickActors(); start = root.position(); offset = be.offset; MachiningClock.advance(h.getLevel()); be.tick();
			near(h, offset, be.offset, 0, "stopping after the cut does not round the piston extension");
			near(h, start.x, root.position().x, 2e-5, "stopping after the cut does not retract the drilling carriage");
			be.disassemble(); h.succeed();
		});
	}
	private static void westPistonSetup(GameTestHelper h) {
		h.setBlock(POS, AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
		for (int x = 1; x <= 4; x++) h.setBlock(POS.east(x), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
		h.setBlock(POS.west(), Blocks.SLIME_BLOCK);
		h.setBlock(POS.west().above(), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
	}
	static void pistonDirectionalEndpoint(GameTestHelper h) {
		westPistonSetup(h);
		h.runAfterDelay(5, () -> {
			LinearActuatorBlockEntity be = h.getBlockEntity(POS);
			NetworkTopology.mark(be); NetworkTopology.flush();
			var network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(128);
			try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); }
			h.assertTrue(be.running && be.movedContraption != null, "west-facing piston assembles its real drilling carriage");
			NetworkTopology.mark(be); NetworkTopology.flush();
			network = (INetworkDynamics) be.getOrCreateNetwork();
			var root = be.movedContraption; var context = root.getContraption().getActors().getFirst().getRight();
			var work = ContraptionActorWork.of(context);
			var behaviour = MovementBehaviour.REGISTRY.get(context.state);
			BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
			h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			near(h, 0, be.offset, 0, "newly assembled fixture starts at the native lower endpoint");
			Vec3 origin = root.position();
			network.differentialcreate$setSpeed(0); root.tickActors(); ContraptionMotion.begin(h.getLevel());
			try {
				double budget = work.differentialcreate$remainingTravel();
				var snapshot = ElasticKinetics.snapshot(java.util.List.of(be.getOrCreateNetwork()));
				var boundary = work.differentialcreate$boundary(budget);
				h.assertTrue(boundary.positive() > 0 && Double.isFinite(boundary.positive()), "the lower endpoint still permits positive inward feed");
				near(h, 0, boundary.negative(), 0, "the true actor exposes a zero outward directional boundary");
				double horizon = RotationMath.TICK_MINUTES / 8;
				var trial = snapshot.advanceToEvent(horizon, java.util.List.of(new ElasticKinetics.WorkEvent(be, boundary)));
				near(h, horizon, trial.result.duration(), 0, "zero net motion cannot trigger the opposite zero boundary");
				near(h, 0, trial.travel(be), 0, "the nonzero horizon gives a stopped drill no free cutting stroke");
			} finally { ContraptionMotion.end(h.getLevel()); }
			MachiningClock.advance(h.getLevel());
			near(h, 0, ContraptionMotion.linear(root).lengthSqr(), 0, "the real scheduler advances a stopped endpoint without an immediate-event loop");
			near(h, 0, context.data.getDouble("DCActorTravel"), 0, "the stationary endpoint keeps its newly acquired target unpaid");
			// At the initial endpoint Create can place the carriage on a native
			// tick. Check the shared clock before its first inward controller tick.
			network.differentialcreate$setSpeed(-128 / network.differentialcreate$getRatios().get(be)); root.tickActors();
			double shaftAngle = ElasticKinetics.state(be).angle;
			double progress = context.data.getDouble("DCActorTravel");
			MachiningClock.advance(h.getLevel());
			h.assertTrue(ElasticKinetics.state(be).angle < shaftAngle, "an outward shaft still rotates against its blocked linear endpoint");
			near(h, 0, ContraptionMotion.linear(root).lengthSqr(), 0, "outward rotation cannot create feed beyond the lower endpoint");
			near(h, progress, context.data.getDouble("DCActorTravel"), 0, "blocked feed cannot charge the existing drilling job");
			h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "blocked drilling leaves its real target intact");
			near(h, origin.x, root.position().x, 0, "neither stopped nor outward clock steps move the lower-endpoint carriage");
			near(h, 0, be.offset, 0, "blocked clock steps preserve the controller's lower endpoint");
			network.differentialcreate$setSpeed(128 / network.differentialcreate$getRatios().get(be)); root.tickActors();
			MachiningClock.advance(h.getLevel());
			Vec3 planned = ContraptionMotion.linear(root);
			h.assertTrue(planned.x < 0, "reversing the blocked shaft restores real inward feed");
			be.tick();
			near(h, origin.x + planned.x, root.position().x, 2e-5, "native movement consumes only the inward integrated stroke");
			h.assertTrue(be.offset > 0 && be.offset < ((LinearActuatorAccessor) be).differentialcreate$extensionRange(), "inward feed stays within the full native extension range");
			h.assertTrue(context.data.getDouble("DCActorTravel") > progress || h.getLevel().getBlockState(target).isAir(), "the retained cutting job can resume after reversing away from the endpoint");
			float offset = be.offset; Vec3 stopped = root.position(); progress = context.data.getDouble("DCActorTravel");
			network.differentialcreate$setSpeed(0); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			near(h, offset, be.offset, 0, "zero RPM after leaving the endpoint preserves the fractional controller position");
			near(h, stopped.x, root.position().x, 2e-5, "zero RPM cannot replay the blocked outward shaft travel");
			near(h, progress, context.data.getDouble("DCActorTravel"), 0, "stopping after the reversal grants no further drilling work");
			h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			root.tickActors(); MachiningClock.advance(h.getLevel());
			h.assertTrue(context.data.contains("BreakingPos"), "the stopped carriage retains a real unfinished job for both blocked-state checks");
			var controller = be.saveWithFullMetadata(h.getLevel().registryAccess());
			double originalLimit = ((LinearActuatorAccessor) be).differentialcreate$offsetLimit();
			for (boolean waiting : new boolean[]{false, true}) {
				be.loadWithComponents(controller, h.getLevel().registryAccess());
				NetworkTopology.mark(be); NetworkTopology.flush();
				h.assertTrue(be.movedContraption == root, "controller NBT preserves the actual drilling carriage attachment");
				var drive = ContraptionPhysics.drive(context);
				h.assertTrue(drive != null && drive.shaft() == be, "the blocked-state check still resolves the real attached shaft");
				network = (INetworkDynamics) be.getOrCreateNetwork();
				network.differentialcreate$setSpeed(16 / network.differentialcreate$getRatios().get(be));
				// Topology's native speed notification resets these controller
				// fields. Enter the real blocked state after rebuilding its network.
				((LinearActuatorAccessor) be).differentialcreate$setWaiting(waiting);
				((LinearActuatorAccessor) be).differentialcreate$setOffsetLimit(waiting ? originalLimit : 0);
				h.assertTrue(((LinearActuatorAccessor) be).differentialcreate$waiting() == waiting
					&& (waiting || ((LinearActuatorAccessor) be).differentialcreate$offsetLimit() == 0),
					"the fixture retains the intended native waiting or exhausted instruction state");
				root.tickActors(); ContraptionMotion.begin(h.getLevel());
				try {
					h.assertTrue(Double.isInfinite(work.differentialcreate$remainingTravel()), "waiting or an exhausted instruction blocks both feed directions");
					h.assertTrue(work.differentialcreate$wakeDirection() == 0, "waiting or a zero instruction limit cannot create a direction wake-up");
				} finally { ContraptionMotion.end(h.getLevel()); }
				progress = context.data.getDouble("DCActorTravel");
				double before = ElasticKinetics.state(be).angle;
				MachiningClock.advance(h.getLevel());
				h.assertTrue(ElasticKinetics.state(be).angle > before, "waiting or instruction exhaustion does not invent a shaft brake");
				near(h, 0, ContraptionMotion.linear(root).lengthSqr(), 0, "the blocked controller emits no tool feed while its shaft turns");
				near(h, progress, context.data.getDouble("DCActorTravel"), 0, "waiting or instruction exhaustion grants no free work to the unfinished drill job");
			}
			be.loadWithComponents(controller, h.getLevel().registryAccess());
			NetworkTopology.mark(be); NetworkTopology.flush();
			be.disassemble(); h.succeed();
		});
	}
	static void pistonPoweredEndpointReturn(GameTestHelper h) {
		westPistonSetup(h);
		h.runAfterDelay(5, () -> {
			LinearActuatorBlockEntity be = h.getBlockEntity(POS);
			NetworkTopology.mark(be); NetworkTopology.flush();
			var network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(128);
			try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); }
			h.assertTrue(be.running && be.movedContraption != null && be.offset == 0, "powered endpoint fixture assembles its native drilling carriage at zero extension");
			NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.movedContraption; var context = root.getContraption().getActors().getFirst().getRight();
			var behaviour = MovementBehaviour.REGISTRY.get(context.state);
			var work = ContraptionActorWork.of(context);
			BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
			h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
			((INetworkDynamics) be.getOrCreateNetwork()).differentialcreate$setSpeed(0); root.tickActors(); MachiningClock.advance(h.getLevel());
			h.assertTrue(context.data.contains("BreakingPos") && context.data.getDouble("DCActorTravel") == 0, "stopped contact establishes a real unpaid cutting job");
			double feed = context.data.getDouble("DCActorFeed");
			h.assertTrue(feed > 0 && Double.isFinite(feed), "the captured cutting stroke has a finite physical feed");
			double cost = Blocks.STONE.defaultBlockState().getDestroySpeed(h.getLevel(), target) * ContraptionActorWork.CYCLE_TRAVEL;
			Direction input = Direction.get(Direction.AxisDirection.POSITIVE, ((IRotate) be.getBlockState().getBlock()).getRotationAxis(be.getBlockState()));
			h.setBlock(POS.relative(input), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, input.getAxis()));
			h.setBlock(POS.relative(input, 2), AllBlocks.WATER_WHEEL.getDefaultState().setValue(WaterWheelBlock.FACING, input.getOpposite()));
			WaterWheelBlockEntity wheel = h.getBlockEntity(POS.relative(input, 2)); wheel.setFlowScoreAndUpdate(1);
			NetworkTopology.mark(wheel); NetworkTopology.mark((KineticBlockEntity) h.getBlockEntity(POS.relative(input))); NetworkTopology.mark(be); NetworkTopology.flush();
			h.assertTrue(be.getOrCreateNetwork() == wheel.getOrCreateNetwork(), "a real connected water wheel supplies the return torque");
			network = (INetworkDynamics) be.getOrCreateNetwork(); network.differentialcreate$setSpeed(-1e-6 / network.differentialcreate$getRatios().get(be));
			root.tickActors(); ContraptionMotion.begin(h.getLevel());
			double freeSpeed, freeTravel;
			try {
				h.assertTrue(Double.isInfinite(work.differentialcreate$remainingTravel()), "the outward cutting phase is initially blocked at the real lower endpoint");
				h.assertTrue(work.differentialcreate$wakeDirection() == 1, "the blocked actor watches the inward physical speed direction");
				context.disabled = true;
				h.assertTrue(work.differentialcreate$wakeDirection() == 0, "a disabled blocked tool does not request a direction wake-up");
				context.disabled = false;
				var free = ElasticKinetics.snapshot(java.util.List.of(be.getOrCreateNetwork())).sample(RotationMath.TICK_MINUTES);
				freeSpeed = free.result.speed()[free.members.indexOf(be)]; freeTravel = free.signedTravel(be);
			} finally { ContraptionMotion.end(h.getLevel()); }
			Vec3 start = root.position();
			MachiningClock.advance(h.getLevel());
			Vec3 pending = ContraptionMotion.linear(root);
			double progress = context.data.getDouble("DCActorTravel");
			h.assertTrue(NetworkDynamics.preciseSpeed(be) > 0 && pending.x < 0, "external torque reverses the blocked shaft and restores actual inward feed within the same tick");
			h.assertTrue(progress > 0 && h.getLevel().getBlockState(target).is(Blocks.STONE), "the resumed contact charges its real cutting job before the tick ends");
			h.assertTrue(NetworkDynamics.preciseSpeed(be) < freeSpeed && -pending.x * 512 < freeTravel,
				"refreshing the cutting load at the wake event reduces actual speed and feed below the unloaded prediction");
			h.assertTrue(progress + 1e-5 >= -pending.x * 512 * cost / feed, "every resumed input displacement pays the captured cutting work instead of moving for free");
			be.tick();
			near(h, start.x + pending.x, root.position().x, 2e-5, "the native controller consumes the paid source-driven return displacement");
			h.assertTrue(be.offset > 0 && be.offset < ((LinearActuatorAccessor) be).differentialcreate$extensionRange(), "the resumed controller remains within its real extension range");
			be.disassemble(); h.succeed();
		});
	}
	static void coaxialDrill(GameTestHelper h) {
		h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(POS.east(), Blocks.SLIME_BLOCK);
		h.setBlock(POS.east(2), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption(); var context = root.getContraption().getActors().getFirst().getRight();
			h.setBlock(POS.east(3), Blocks.STONE); speed(be, 128); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
			h.assertTrue(h.getBlockState(POS.east(3)).isAir(), "coaxial drill pays for cutting through its real blade rotation");
			h.assertFalse(context.stall, "coaxial cutting needs no global pause");
			h.assertTrue(root.getAngle(1) > 20, "coaxial tool and its structure actually turn during cutting");
			root.setAngle(0); be.disassemble(); h.succeed();
		});
	}
	static void reloadFeed(GameTestHelper h) {
		setup(h, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
			var root = be.getMovedContraption(); h.setBlock(POS.east(2).above(4), Blocks.STONE);
			speed(be, 1); root.tickActors(); MachiningClock.advance(h.getLevel());
			double pending = ContraptionMotion.angular(root);
			double progress = root.getContraption().getActors().getFirst().getRight().data.getDouble("DCActorTravel");
			h.assertTrue(pending > 0 && progress > 0, "reload fixture contains paid displacement and a partial cut");
			var entityTag = root.saveWithoutId(new CompoundTag()); entityTag.putString("id", EntityType.getKey(root.getType()).toString());
			root.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
			var blockTag = be.saveWithFullMetadata(h.getLevel().registryAccess());
			var restored = (MechanicalBearingBlockEntity) BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), blockTag, h.getLevel().registryAccess());
			h.getLevel().setBlockEntity(restored); NetworkTopology.mark(restored); NetworkTopology.flush();
			var loaded = (ControlledContraptionEntity) EntityType.loadEntityRecursive(entityTag, h.getLevel(), e -> e);
			h.getLevel().addFreshEntity(loaded); restored.attach(loaded);
			near(h, pending, loaded.getAngle(1), 2e-6, "late controller attachment recovers paid travel once");
			near(h, pending, ((com.diliupengyun.differential_create.kinetics.IBearingMotion) restored).differentialcreate$syncedAngle(),
				2e-6, "a reloaded entity starts its animation at the recovered pose without replaying pending travel");
			near(h, progress, loaded.getContraption().getActors().getFirst().getRight().data.getDouble("DCActorTravel"), 1e-9, "NBT retains fractional cut energy");
			loaded.tickActors(); MachiningClock.advance(h.getLevel()); restored.tick();
			near(h, pending, loaded.getAngle(1), 2e-6, "zero-speed reload cannot replay the paid movement");
			speed(restored, 64); loaded.tickActors(); MachiningClock.advance(h.getLevel()); restored.tick();
			h.assertTrue(h.getBlockState(POS.east(2).above(4)).isAir(), "reloaded partial cutting stroke resumes normally");
			loaded.setAngle(0); restored.disassemble(); h.succeed();
		});
	}
}
