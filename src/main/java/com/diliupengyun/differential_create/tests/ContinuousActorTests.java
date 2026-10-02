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
			h.assertTrue(Math.signum(delta) == Math.signum(rpm) && Math.abs(delta) > 40, "structure keeps rotating while the drill cuts");
			for (var target : targets)
				h.assertTrue(h.getBlockState(target).isAir(), "high-speed drill visits and cuts intermediate target " + target);
			h.assertFalse(context.stall || root.getContraption().stalled || root.isStalled(), "cutting never sets the native global pause flag");
			be.tick();
			near(h, delta, root.getAngle(1), 2e-5, "native controller consumes the same integrated travel as its actors");
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
			near(h, progress, loaded.getContraption().getActors().getFirst().getRight().data.getDouble("DCActorTravel"), 1e-9, "NBT retains fractional cut energy");
			loaded.tickActors(); MachiningClock.advance(h.getLevel()); restored.tick();
			near(h, pending, loaded.getAngle(1), 2e-6, "zero-speed reload cannot replay the paid movement");
			speed(restored, 64); loaded.tickActors(); MachiningClock.advance(h.getLevel()); restored.tick();
			h.assertTrue(h.getBlockState(POS.east(2).above(4)).isAir(), "reloaded partial cutting stroke resumes normally");
			loaded.setAngle(0); restored.disassemble(); h.succeed();
		});
	}
}
