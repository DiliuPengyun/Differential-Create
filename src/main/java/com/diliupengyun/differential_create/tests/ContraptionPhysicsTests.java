package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

final class ContraptionPhysicsTests {
	private ContraptionPhysicsTests() {}
	private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
		h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
			label + ": expected " + expected + ", got " + actual);
	}
	private static void speed(KineticBlockEntity be, double rpm) {
		var network = (INetworkDynamics) be.getOrCreateNetwork();
		network.differentialcreate$setSpeed(rpm / network.differentialcreate$getRatios().get(be));
	}
	static void strength(GameTestHelper h) {
		BlockState[] andesite = {AllBlocks.MECHANICAL_BEARING.getDefaultState(), AllBlocks.MECHANICAL_PRESS.getDefaultState(),
			AllBlocks.MECHANICAL_PISTON.getDefaultState(), AllBlocks.ROPE_PULLEY.getDefaultState(), AllBlocks.MECHANICAL_DRILL.getDefaultState(),
			AllBlocks.DEPLOYER.getDefaultState(), AllBlocks.WATER_WHEEL.getDefaultState(), AllBlocks.SPEEDOMETER.getDefaultState(),
			AllBlocks.SHAFT.getDefaultState(), AllBlocks.CLUTCH.getDefaultState()};
		BlockState[] brass = {AllBlocks.CLOCKWORK_BEARING.getDefaultState(), AllBlocks.ELEVATOR_PULLEY.getDefaultState(),
			AllBlocks.MECHANICAL_ARM.getDefaultState(), AllBlocks.MECHANICAL_CRAFTER.getDefaultState(), AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState(),
			AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState(), AllBlocks.POWERED_SHAFT.getDefaultState(), AllBlocks.FLYWHEEL.getDefaultState(),
			AllBlocks.BRASS_ENCASED_SHAFT.getDefaultState(), com.diliupengyun.differential_create.content.ModContent.BRASS_CLUTCH.getDefaultState(),
			AllBlocks.CRUSHING_WHEEL.getDefaultState()};
		for (int tier = 0; tier < 2; tier++) {
			var states = tier == 0 ? andesite : brass;
			for (int i = 0; i < states.length; i++) h.setBlock(new BlockPos(3 + (i % 6) * 4, 3, 3 + (i / 6) * 8 + tier * 16), states[i]);
		}
		BlockPos motorPos = new BlockPos(28, 3, 3);
		h.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState());
		CreativeMotorBlockEntity motor = h.getBlockEntity(motorPos);
		motor.generatedSpeed.setValue(0);
		h.runAfterDelay(5, () -> {
			for (int tier = 0; tier < 2; tier++) for (int i = 0; i < (tier == 0 ? andesite : brass).length; i++) {
				KineticBlockEntity be = h.getBlockEntity(new BlockPos(3 + (i % 6) * 4, 3, 3 + (i / 6) * 8 + tier * 16));
				double strength = tier == 0 ? 65_536 : 262_144;
				near(h, strength, ElasticKinetics.strengthOf(be), 0, "material strength of " + be.getBlockState().getBlock());
				// Create's wrappers format their headers with the client font. Exercise
				// the shared readout they use without loading client classes here.
				var tooltip = new ArrayList<Component>(); KineticGoggleInfo.append(be, tooltip, false);
				var rows = tooltip.stream().filter(c -> c.getContents() instanceof TranslatableContents t
					&& t.getKey().equals("gui.differentialcreate.goggles.strength")).toList();
				h.assertTrue(rows.size() == 1, "every kinetic machine supplies one strength readout: " + be.getBlockState().getBlock());
				near(h, strength, Double.parseDouble(((TranslatableContents) rows.getFirst().getContents()).getArgs()[0].toString()), 0, "displayed strength");
				if (AllBlocks.CRUSHING_WHEEL.has(be.getBlockState()))
					near(h, .1, InertiaRegistry.getInertia(be), 1e-8, "each crushing wheel carries ten times the ordinary machine inertia");
			}
			h.assertTrue(ElasticKinetics.strengthOf(motor) == Double.POSITIVE_INFINITY, "stopped creative motor retains infinite structural strength");
			var tooltip = new ArrayList<Component>(); KineticGoggleInfo.append(motor, tooltip, false);
			var rows = tooltip.stream().filter(c -> c.getContents() instanceof TranslatableContents t
				&& t.getKey().equals("gui.differentialcreate.goggles.strength")).toList();
			h.assertTrue(rows.size() == 1, "creative motor supplies one strength readout");
			h.assertTrue(((TranslatableContents) rows.getFirst().getContents()).getArgs()[0].equals("∞"), "creative motor displays infinite strength as ∞");
			h.succeed();
		});
	}
	static void mass(GameTestHelper h) {
		BlockPos piston = new BlockPos(9, 9, 5), pulley = new BlockPos(9, 10, 14), elevator = new BlockPos(20, 10, 14), clock = new BlockPos(5, 8, 26);
		h.setBlock(piston, AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		for (int i = 1; i <= 3; i++) h.setBlock(piston.west(i), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(piston.east(), Blocks.SLIME_BLOCK); h.setBlock(piston.east().above(), Blocks.OAK_PLANKS);
		h.setBlock(pulley, AllBlocks.ROPE_PULLEY.getDefaultState());
		h.setBlock(elevator, AllBlocks.ELEVATOR_PULLEY.getDefaultState());
		for (var pos : new BlockPos[]{pulley, elevator}) {
			h.setBlock(pos.below(), Blocks.SLIME_BLOCK); h.setBlock(pos.below().east(), Blocks.OAK_PLANKS);
		}
		h.setBlock(elevator.below().west(), AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH));
		h.setBlock(clock, AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		h.setBlock(clock.east(), Blocks.SLIME_BLOCK); h.setBlock(clock.east().above(), Blocks.OAK_PLANKS);
		h.runAfterDelay(5, () -> {
			for (var pos : new BlockPos[]{piston, pulley, elevator}) {
				LinearActuatorBlockEntity be = h.getBlockEntity(pos);
				be.setSpeed(pos.equals(piston) ? -16 : 16); speed(be, pos.equals(piston) ? -16 : 16);
				try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); }
				catch (Exception e) { throw new AssertionError("native actuator assembly", e); }
				h.assertTrue(be.running && be.movedContraption != null, "native operation assembles " + be.getBlockState().getBlock());
				NetworkTopology.mark(be); NetworkTopology.flush(); ContraptionPhysics.checkAttachments(be); ElasticKinetics.refresh(be.getOrCreateNetwork());
				var blocks = be.movedContraption.getContraption().getBlocks();
				long count = blocks.values().stream().filter(b -> !b.state().isAir()).count();
				double gear = pos.equals(elevator) ? 2 : 1;
				double expected = .01 + count * .0006 * Math.pow(gear * 1200 / (512 * 2 * Math.PI), 2);
				near(h, expected, InertiaRegistry.getInertia(be), 1e-7, "translation folds total mass into shaft inertia");
				near(h, 0, MachineActivity.impact(be), 0, "empty moving structure has no fixed work torque");
				speed(be, pos.equals(piston) ? -16 : 16);
				if (pos.equals(elevator)) be.movedContraption.setContraptionMotion(new Vec3(0, -32.0 / 512, 0));
				double drag = ContraptionPhysics.airDrag(be);
				h.assertTrue(drag > 0, "moving actuator has air drag: " + be.getBlockState().getBlock());
				near(h, drag * 64 * 64, MachineActivity.loadTorque(be, -64), 1e-12, "air torque is even in speed and quadratic");
				var entry = blocks.entrySet().stream().filter(e -> e.getValue().state().is(Blocks.OAK_PLANKS)).findFirst().orElseThrow();
				be.movedContraption.setBlock(entry.getKey(), new StructureBlockInfo(entry.getKey(), AllBlocks.FLYWHEEL.getDefaultState(), null));
				near(h, expected, InertiaRegistry.getInertia(be), 1e-7, "mounted flywheel has the same mass as a plank");
				near(h, drag, ContraptionPhysics.airDrag(be), 1e-12, "material does not change air geometry");
				var tag = be.saveWithFullMetadata(h.getLevel().registryAccess());
				var clone = (KineticBlockEntity) be.getType().create(be.getBlockPos(), be.getBlockState());
				clone.setLevel(h.getLevel()); clone.loadWithComponents(tag, h.getLevel().registryAccess());
				near(h, expected, InertiaRegistry.getInertia(clone), 1e-7, "late entity loading preserves actuator inertia");
				near(h, drag, ContraptionPhysics.airDrag(clone), 1e-12, "late entity loading preserves air drag");
				var legacy = tag.copy(); legacy.remove("DCStructureCalibration");
				legacy.putFloat("DCStructureInertia", (float) (.01 + (expected - .01) * 100));
				legacy.putDouble("DCStructureDrag", drag * 100);
				clone.loadWithComponents(legacy, h.getLevel().registryAccess());
				near(h, expected, InertiaRegistry.getInertia(clone), 1e-7, "legacy translating structures retain the fixed machine rotor during migration");
				near(h, drag, ContraptionPhysics.airDrag(clone), 1e-12, "legacy actuator air summaries use the trial coefficient");
				be.disassemble(); ContraptionPhysics.checkAttachments(be);
				near(h, .01, InertiaRegistry.getInertia(be), 1e-8, "native disassembly releases attached mass");
				near(h, 0, ContraptionPhysics.airDrag(be), 0, "native disassembly releases air load");
			}
			ClockworkBearingBlockEntity be = h.getBlockEntity(clock); be.setSpeed(16); speed(be, 16); be.assemble();
			h.assertTrue(((ClockworkBearingAccessor) be).differentialcreate$hour() != null, "clock keeps native hand assembly");
			ContraptionPhysics.checkAttachments(be);
			near(h, .0102, InertiaRegistry.getInertia(be), 1e-8, "hour hand's half-speed gearing folds trial inertia by one quarter");
			be.disassemble(); near(h, .01, InertiaRegistry.getInertia(be), 1e-8, "clock disassembly removes hand inertia");
			h.succeed();
		});
	}
	static void actors(GameTestHelper h) {
		BlockPos[] positions = {new BlockPos(6, 6, 5), new BlockPos(6, 6, 17), new BlockPos(17, 6, 5), new BlockPos(17, 6, 17)};
		BlockState[] actorStates = {AllBlocks.MECHANICAL_DRILL.getDefaultState(), AllBlocks.MECHANICAL_DRILL.getDefaultState(),
			AllBlocks.MECHANICAL_HARVESTER.getDefaultState(), AllBlocks.MECHANICAL_PLOUGH.getDefaultState()};
		for (int i = 0; i < 4; i++) {
			var pos = positions[i]; h.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
			int radius = i == 1 ? 4 : 1;
			for (int y = 0; y < radius; y++) h.setBlock(pos.east().above(y), Blocks.SLIME_BLOCK);
			var property = actorStates[i].hasProperty(BlockStateProperties.FACING) ? BlockStateProperties.FACING : BlockStateProperties.HORIZONTAL_FACING;
			h.setBlock(pos.east().above(radius), actorStates[i].setValue(property, Direction.EAST));
		}
		h.runAfterDelay(5, () -> {
			for (int i = 0; i < 4; i++) {
				MechanicalBearingBlockEntity be = h.getBlockEntity(positions[i]); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
				var contraption = be.getMovedContraption().getContraption();
				var pair = contraption.getActors().getFirst(); MovementContext[] context = {pair.getRight()};
				BlockPos target = h.absolutePos(positions[i].east(3).above(i == 1 ? 4 : 1));
				context[0].position = Vec3.atCenterOf(target); context[0].relativeMotion = new Vec3(1, 0, 0); context[0].motion = context[0].relativeMotion;
				MovementBehaviour behaviour = MovementBehaviour.REGISTRY.get(context[0].state);
				for (double[] schedule : new double[][]{{16}, {512}, {0, 32, -64, 128}}) {
					if (i < 2) h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
					else if (i == 2) {
						h.getLevel().setBlockAndUpdate(target.below(), Blocks.FARMLAND.defaultBlockState());
						h.getLevel().setBlockAndUpdate(target, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
					} else {
						h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
						h.getLevel().setBlockAndUpdate(target.below(), Blocks.DIRT.defaultBlockState());
					}
					behaviour.visitNewPosition(context[0], target); contraption.stalled = true;
					var work = ContraptionActorWork.of(context[0]);
					var beforeState = h.getLevel().getBlockState(i == 3 ? target.below() : target);
					double remaining = work.differentialcreate$remainingTravel();
					h.assertTrue(Double.isFinite(remaining) && remaining > 0, "real actor callback queues physical work");
					for (int repeat = 0; repeat < 10; repeat++) { speed(be, 0); behaviour.tick(context[0]); MachiningClock.advance(h.getLevel()); }
					near(h, remaining, work.differentialcreate$remainingTravel(), 1e-9, "zero shaft speed freezes an actor despite stale movement vectors");
					h.assertTrue(beforeState == h.getLevel().getBlockState(i == 3 ? target.below() : target), "no world operation occurs at zero speed");
					double spent = 0; int step = 0;
					while (Double.isFinite(work.differentialcreate$remainingTravel()) && step < 2000) {
						speed(be, schedule[step % schedule.length]);
						double before = ElasticKinetics.kineticEnergy(be.getOrCreateNetwork());
						MachiningClock.advance(h.getLevel()); spent += before - ElasticKinetics.kineticEnergy(be.getOrCreateNetwork());
						if (step++ == 3 && Double.isFinite(work.differentialcreate$remainingTravel())) {
							double saved = work.differentialcreate$remainingTravel();
							context[0] = MovementContext.readNBT(h.getLevel(), pair.getLeft(), context[0].writeToNBT(new CompoundTag()), contraption);
							pair.setRight(context[0]); work = ContraptionActorWork.of(context[0]);
							near(h, saved, work.differentialcreate$remainingTravel(), 1e-9, "actor reload retains fractional work");
						}
					}
					h.assertTrue(step < 2000, "actor finishes through the production physics scheduler");
					near(h, i < 2 ? 5 : 2 * 1000.0 / 1200, spent, 1e-7, "actor work is independent of RPM and radius");
					if (i < 2) h.assertTrue(h.getLevel().getBlockState(target).isAir(), "drill destroys real stone");
					else if (i == 2) h.assertTrue(h.getLevel().getBlockState(target).getValue(BlockStateProperties.AGE_7) == 0, "harvester retains native replanting");
					else h.assertTrue(h.getLevel().getBlockState(target.below()).is(Blocks.FARMLAND), "plough retains native hoe operation");
				}
				if (i == 3) {
					h.getLevel().setBlockAndUpdate(target.below(), Blocks.DIRT.defaultBlockState());
					behaviour.visitNewPosition(context[0], target);
					h.getLevel().setBlockAndUpdate(target.below(), Blocks.BEDROCK.defaultBlockState());
					h.assertFalse(Double.isFinite(ContraptionActorWork.of(context[0]).differentialcreate$remainingTravel()),
						"changed ground cancels plough work even when the block above remains air");
					near(h, 0, MachineActivity.impact(be), 0, "invalid plough target releases its work torque");
				}
				be.disassemble();
			}
			h.succeed();
		});
	}
	static void gantry(GameTestHelper h) {
		BlockPos shaftPos = new BlockPos(8, 5, 8), carriagePos = shaftPos.above();
		for (int x = -3; x <= 3; x++) {
			h.setBlock(shaftPos.offset(x, 0, 0), AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(GantryShaftBlock.FACING, Direction.EAST));
			// The shared fixture contains barriers; this carriage needs a clear travel corridor.
			for (int y = 1; y <= 3; y++) h.setBlock(shaftPos.offset(x, y, 0), Blocks.AIR);
		}
		h.setBlock(carriagePos.above(), Blocks.SLIME_BLOCK); h.setBlock(carriagePos.above(2), Blocks.OAK_PLANKS);
		h.runAfterDelay(5, () -> {
			KineticBlockEntity shaft = h.getBlockEntity(shaftPos); shaft.setSpeed(-16); speed(shaft, -16);
			h.setBlock(carriagePos, AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
			GantryCarriageBlockEntity carriage = h.getBlockEntity(carriagePos); carriage.queueAssembly(); carriage.tick();
			// A freshly spawned contraption's collision bounds are initialized on
			// its first entity tick. Select by anchor, without relying on those bounds.
			var entities = java.util.stream.StreamSupport.stream(h.getLevel().getAllEntities().spliterator(), false)
				.filter(e -> e instanceof GantryContraptionEntity).map(e -> (GantryContraptionEntity) e)
				.filter(e -> e.getAnchorVec().distanceTo(Vec3.atLowerCornerOf(h.absolutePos(carriagePos))) < 1).toList();
			h.assertTrue(entities.size() == 1, "native carriage assembly creates one moving structure: count=" + entities.size()
				+ ", exception=" + carriage.getLastAssemblyException() + ", shaft=" + shaft.getBlockState());
			var entity = entities.getFirst(); entity.tick();
			h.assertFalse(entity.isRemoved(), "zero physical startup speed does not dismantle a powered carriage");
			double expected = 3 * .0006 * Math.pow(1200 / (512 * 2 * Math.PI), 2);
			near(h, expected, InertiaRegistry.getInertia(shaft), 1e-7, "carriage mass belongs to the supporting world shaft");
			speed(shaft, -64); double energy = ElasticKinetics.kineticEnergy(shaft.getOrCreateNetwork());
			entity.setPos(entity.getX() + 1, entity.getY(), entity.getZ()); ContraptionPhysics.bindGantry(entity, Double.NaN);
			KineticBlockEntity next = h.getBlockEntity(shaftPos.east());
			near(h, 0, InertiaRegistry.getInertia(shaft), 0, "previous shaft releases carriage mass");
			near(h, expected, InertiaRegistry.getInertia(next), 1e-7, "next shaft acquires carriage mass");
			near(h, -64, next.getSpeed(), 0, "pinion handover preserves velocity");
			near(h, energy, ElasticKinetics.kineticEnergy(next.getOrCreateNetwork()), 1e-8, "pinion handover preserves kinetic energy");
			near(h, -64, entity.saveWithoutId(new CompoundTag()).getDouble("DCGantrySpeed"), 0, "carriage reload stores its momentum");
			entity.tick(); next.setRemoved();
			near(h, -64, entity.saveWithoutId(new CompoundTag()).getDouble("DCGantrySpeed"), 0, "unloading the supporting shaft first retains carriage momentum");
			next.clearRemoved();
			entity.disassemble();
			near(h, 0, InertiaRegistry.getInertia(next), 0, "native carriage disassembly releases mass");
			h.succeed();
		});
	}
	private static int stones(MovementContext context) {
		var inventory = context.contraption.getStorage().getAllItems();
		int count = 0;
		for (int slot = 0; slot < inventory.getSlots(); slot++) {
			var stack = inventory.getStackInSlot(slot);
			if (stack.is(Items.STONE)) count += stack.getCount();
		}
		return count;
	}
	private static double finishAction(GameTestHelper h, KineticBlockEntity shaft, MovementContext context, double[] schedule) {
		var work = ContraptionActorWork.of(context);
		double spent = 0; int step = 0;
		while (Double.isFinite(work.differentialcreate$remainingTravel()) && step < 2000) {
			speed(shaft, schedule[step++ % schedule.length]);
			double energy = ElasticKinetics.kineticEnergy(shaft.getOrCreateNetwork());
			MachiningClock.advance(h.getLevel());
			spent += energy - ElasticKinetics.kineticEnergy(shaft.getOrCreateNetwork());
		}
		h.assertTrue(step < 2000, "queued action completes through the physical scheduler");
		return spent;
	}
	static void storageActors(GameTestHelper h) {
		BlockPos[] bearings = {new BlockPos(6, 6, 5), new BlockPos(6, 6, 20)};
		for (int i = 0; i < 2; i++) {
			var pos = bearings[i];
			h.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
			h.setBlock(pos.east(), Blocks.SLIME_BLOCK);
			h.setBlock(pos.east().south(), Blocks.CHEST);
			if (i == 1) ((ChestBlockEntity) h.getBlockEntity(pos.east().south())).setItem(0, new ItemStack(Items.STONE, 16));
			h.setBlock(i == 0 ? pos.east(2) : pos.east().above(), i == 0
				? AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST)
				: AllBlocks.MECHANICAL_ROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		}
		h.runAfterDelay(5, () -> {
			for (int i = 0; i < 2; i++) {
				MechanicalBearingBlockEntity shaft = h.getBlockEntity(bearings[i]); shaft.assemble();
				NetworkTopology.mark(shaft); NetworkTopology.flush();
				var contraption = shaft.getMovedContraption().getContraption();
				var pair = contraption.getActors().getFirst(); var context = pair.getRight();
				var behaviour = MovementBehaviour.REGISTRY.get(context.state);
				if (i == 0) { MovingDeployerTests.paidLifecycle(h, shaft, pair); shaft.disassemble(); continue; }
				BlockPos target = h.absolutePos(bearings[i].east(3).offset(0, -1, 0));
				context.position = Vec3.atCenterOf(target); context.motion = context.relativeMotion = new Vec3(1, 0, 0);

					context.blockEntityData.putInt("ScrollValue", 1);
					context.blockEntityData.put("Filter", new ItemStack(Items.STONE).save(h.getLevel().registryAccess()));
					h.getLevel().setBlockAndUpdate(target.below(), Blocks.BEDROCK.defaultBlockState());

				for (double[] schedule : new double[][]{{16}, {512}, {0, 32, -64, 128}}) {
					h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
					int beforeMaterials = stones(context);
					behaviour.visitNewPosition(context, target); contraption.stalled = true;
					var work = ContraptionActorWork.of(context);
					double remaining = work.differentialcreate$remainingTravel();
					h.assertTrue(work.pending() && Double.isFinite(remaining), "native roller callback queues work");
					if (i == 1) near(h, beforeMaterials - 1, stones(context), 0, "roller reserves exactly one real inventory item");
					for (int repeat = 0; repeat < 25; repeat++) { speed(shaft, 0); behaviour.tick(context); MachiningClock.advance(h.getLevel()); }
					near(h, remaining, work.differentialcreate$remainingTravel(), 1e-9, "roller makes no free progress at zero speed");
					h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.AIR), "world operation waits for physical work");
					speed(shaft, 16);
					double energy = ElasticKinetics.kineticEnergy(shaft.getOrCreateNetwork()); MachiningClock.advance(h.getLevel());
					double spent = energy - ElasticKinetics.kineticEnergy(shaft.getOrCreateNetwork());
					remaining = work.differentialcreate$remainingTravel();
					behaviour.writeExtraData(context);
					var saved = context.writeToNBT(new CompoundTag());
					if (context.temporaryData instanceof DeployerFakePlayer player) player.discard();
					context = MovementContext.readNBT(h.getLevel(), pair.getLeft(), saved, contraption); pair.setRight(context);
					work = ContraptionActorWork.of(context);
					near(h, remaining, work.differentialcreate$remainingTravel(), 1e-9, "reload retains fractional action travel and reserved material");
					spent += finishAction(h, shaft, context, schedule);
					near(h, 8 * 1000.0 / 1200, spent, 1e-7, "roller cost is independent of RPM");

					h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "paid action executes native paving: actor=" + i
						+ ", state=" + h.getLevel().getBlockState(target) + ", data=" + context.data + ", be=" + context.blockEntityData
						+ ", held=" + (context.temporaryData instanceof DeployerFakePlayer player ? player.getMainHandItem() : null));
					if (i == 1) near(h, beforeMaterials - 1, stones(context), 0, "successful paving consumes only its reserved item");
				}
				if (i == 1) {
					int before = stones(context);
					h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); behaviour.visitNewPosition(context, target);
					h.getLevel().setBlockAndUpdate(target, Blocks.DIORITE.defaultBlockState());
					h.assertTrue(!Double.isFinite(ContraptionActorWork.of(context).differentialcreate$remainingTravel()), "changed paving target cancels queued work");
					near(h, before, stones(context), 0, "cancelled paving returns the reserved material exactly once");
					h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); behaviour.visitNewPosition(context, target);
					context.disabled = true;
					h.assertFalse(Double.isFinite(ContraptionActorWork.of(context).differentialcreate$remainingTravel()), "disabled roller cancels queued work");
					near(h, before, stones(context), 0, "disabled roller refunds material");
					h.assertFalse(context.stall, "disabled roller releases the structure");
					context.disabled = false; behaviour.visitNewPosition(context, target);
					shaft.disassemble();
					ChestBlockEntity chest = h.getBlockEntity(bearings[i].east().south());
					near(h, before, chest.getItem(0).getCount(), 0, "disassembly returns pending material before restoring storage");
					continue;
				}
				shaft.disassemble();
			}
			h.succeed();
		});
	}
}
