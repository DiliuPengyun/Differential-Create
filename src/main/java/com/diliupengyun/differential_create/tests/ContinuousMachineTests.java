package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.transmission.sequencer.*;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Actual Create callbacks, persistence and the production physics clock. */
final class ContinuousMachineTests {
	private ContinuousMachineTests() {}
	private static final double[][] SCHEDULES = {{16}, {512}, {0, 32, -64, 128}};
	private static void charge(KineticBlockEntity be, double rpm) {
		var dynamics = (INetworkDynamics) be.getOrCreateNetwork();
		dynamics.differentialcreate$setSpeed(rpm / dynamics.differentialcreate$getRatios().get(be));
	}
	private static int cycle(GameTestHelper helper, KineticBlockEntity machine, ShaftWork work, double[] schedule, double expected) {
		double spent = 0;
		int step = 0;
		while (Double.isFinite(work.differentialcreate$remainingTravel()) && step < 2000) {
			charge(machine, schedule[step % schedule.length]);
			double remaining = work.differentialcreate$remainingTravel();
			double before = ElasticKinetics.kineticEnergy(machine.getOrCreateNetwork());
			MachiningClock.advance(helper.getLevel());
			spent += before - ElasticKinetics.kineticEnergy(machine.getOrCreateNetwork());
			if (schedule[step % schedule.length] == 0)
				helper.assertTrue(Math.abs(work.differentialcreate$remainingTravel() - remaining) < 1e-9, "stopped machine keeps its progress");
			if (step == 3 && Double.isFinite(work.differentialcreate$remainingTravel())) {
				double saved = work.differentialcreate$remainingTravel();
				var tag = machine.saveWithFullMetadata(helper.getLevel().registryAccess());
				machine.loadWithComponents(tag, helper.getLevel().registryAccess());
				NetworkTopology.mark(machine); NetworkTopology.flush();
				helper.assertTrue(Math.abs(saved - work.differentialcreate$remainingTravel()) < 1e-9, "NBT retains fractional work");
			}
			step++;
		}
		helper.assertTrue(step < 2000, "machine must finish");
		helper.assertTrue(Math.abs(spent - expected) < 1e-7, "work: " + spent + " expected " + expected);
		DifferentialCreate.LOGGER.info("CONTINUOUS_WORK block={} rpm={} steps={} consumed={}",
			machine.getBlockState().getBlock(), java.util.Arrays.toString(schedule), step, spent);
		return step;
	}
	static void breaking(GameTestHelper helper) {
		BlockPos drillPos = new BlockPos(5, 3, 5), sawPos = new BlockPos(14, 3, 5);
		helper.setBlock(drillPos, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(sawPos, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.runAfterDelay(5, () -> {
			DrillBlockEntity drill = helper.getBlockEntity(drillPos);
			SawBlockEntity saw = helper.getBlockEntity(sawPos);
			NetworkTopology.mark(drill); NetworkTopology.mark(saw); NetworkTopology.flush();
			for (double[] schedule : SCHEDULES) {
				helper.setBlock(drillPos.east(), Blocks.STONE);
				cycle(helper, drill, MachineWork.of(drill), schedule, (double) drill.calculateStressApplied() * 1500 / 1200);
				helper.assertTrue(helper.getBlockState(drillPos.east()).isAir(), "drill breaks real stone");
				helper.setBlock(sawPos.east(), Blocks.OAK_LOG);
				cycle(helper, saw, MachineWork.of(saw), schedule, (double) saw.calculateStressApplied() * 2000 / 1200);
				helper.assertTrue(helper.getBlockState(sawPos.east()).isAir(), "horizontal saw retains real tree-cutting callback");
			}
			helper.setBlock(drillPos.east(), Blocks.STONE);
			var work = MachineWork.of(drill);
			charge(drill, 64); MachiningClock.advance(helper.getLevel());
			helper.setBlock(drillPos.east(), Blocks.DIRT);
			helper.assertTrue(Math.abs(work.differentialcreate$remainingTravel() - 500) < 1e-9, "replacing a target discards old work");
			helper.succeed();
		});
	}
	static void arm(GameTestHelper helper) {
		BlockPos pos = new BlockPos(10, 3, 10), inputPos = pos.west(2).below(), outputPos = pos.east(2).below();
		helper.setBlock(pos, AllBlocks.MECHANICAL_ARM.getDefaultState());
		helper.setBlock(inputPos, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(outputPos, AllBlocks.DEPOT.getDefaultState());
		helper.runAfterDelay(5, () -> {
			ArmBlockEntity arm = helper.getBlockEntity(pos);
			DepotBlockEntity input = helper.getBlockEntity(inputPos), output = helper.getBlockEntity(outputPos);
			ListTag points = new ListTag();
			var take = ArmInteractionPoint.create(helper.getLevel(), input.getBlockPos(), input.getBlockState());
			take.cycleMode();
			points.add(take.serialize(arm.getBlockPos()));
			points.add(ArmInteractionPoint.create(helper.getLevel(), output.getBlockPos(), output.getBlockState()).serialize(arm.getBlockPos()));
			int slow = 0, fast = 0;
			for (double[] schedule : SCHEDULES) {
				input.setHeldItem(new ItemStack(Items.IRON_INGOT));
				output.setHeldItem(ItemStack.EMPTY);
				var tag = arm.saveWithFullMetadata(helper.getLevel().registryAccess());
				tag.put("InteractionPoints", points);
				tag.putString("Phase", "SEARCH_INPUTS");
				tag.putFloat("MovementProgress", 1); tag.putDouble("DCArmProgress", 1);
				tag.putInt("TargetPointIndex", -1);
				arm.loadWithComponents(tag, helper.getLevel().registryAccess());
				NetworkTopology.mark(arm); NetworkTopology.flush();
				arm.tick();
				helper.assertTrue(MachinePhase.of(arm).equals("MOVE_TO_INPUT"), "arm selects its real input");
				int steps = cycle(helper, arm, (ShaftWork) arm, schedule, (double) arm.calculateStressApplied() * 1024 / 1200);
				if (schedule[0] == 16) slow = steps;
				if (schedule[0] == 512) fast = steps;
				helper.assertTrue(input.getHeldItem().isEmpty(), "arm collects the input exactly once");
				tag = arm.saveWithFullMetadata(helper.getLevel().registryAccess());
				tag.putFloat("MovementProgress", 1); tag.putDouble("DCArmProgress", 1);
				arm.loadWithComponents(tag, helper.getLevel().registryAccess());
				NetworkTopology.mark(arm); NetworkTopology.flush();
				arm.lazyTick();
				helper.assertTrue(MachinePhase.of(arm).equals("MOVE_TO_OUTPUT"), "arm selects its real output");
				cycle(helper, arm, (ShaftWork) arm, schedule, (double) arm.calculateStressApplied() * 1024 / 1200);
				helper.assertTrue(output.getHeldItem().is(Items.IRON_INGOT) && output.getHeldItem().getCount() == 1, "arm deposits exactly one ingot");
			}
			helper.assertTrue(fast < slow / 2, "arm motion responds above the old 256 RPM cap");
			helper.succeed();
		});
	}
	static void fan(GameTestHelper helper) {
		BlockPos pos = new BlockPos(5, 4, 5), depotPos = pos.east(2).below();
		helper.setBlock(pos, AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(pos.east(), Blocks.WATER);
		helper.setBlock(depotPos, AllBlocks.DEPOT.getDefaultState());
		helper.runAfterDelay(5, () -> {
			EncasedFanBlockEntity fan = helper.getBlockEntity(pos);
			DepotBlockEntity depot = helper.getBlockEntity(depotPos);
			NetworkTopology.mark(fan); NetworkTopology.flush();
			charge(fan, 64);
			int sign = fan.getAirFlowDirection() == Direction.EAST ? 1 : -1;
			var handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
			int slow = 0, reference = 0, fast = 0;
			for (int rpm : new int[]{16, 64, 128}) {
				charge(fan, sign * rpm); fan.airCurrent.rebuild();
				depot.setHeldItem(AllItems.WHEAT_FLOUR.asStack());
				depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE)
					.setCenteredHeldItem(new TransportedItemStack(AllItems.WHEAT_FLOUR.asStack()));
				int ticks = 0;
				while (depot.getHeldItem().is(AllItems.WHEAT_FLOUR.get()) && ticks < 1000) {
					fan.airCurrent.tickAffectedHandlers();
					if (ticks == 3) {
						double[] remaining = {0};
						handler.handleProcessingOnAllItems(stack -> {
							var progress = (IFanProcessingProgress) stack;
							remaining[0] = progress.differentialcreate$fanRemaining();
							var restored = TransportedItemStack.read(stack.serializeNBT(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
							helper.assertTrue(((IFanProcessingProgress) restored).differentialcreate$fanRemaining() == remaining[0], "belt/depot NBT retains fan exposure");
							helper.assertTrue(((IFanProcessingProgress) stack.copy()).differentialcreate$fanRemaining() == remaining[0], "transfers retain fan exposure");
							return TransportedResult.doNothing();
						});
						charge(fan, 0);
						for (int i = 0; i < 20; i++) fan.airCurrent.tickAffectedHandlers();
						handler.handleProcessingOnAllItems(stack -> {
							helper.assertTrue(((IFanProcessingProgress) stack).differentialcreate$fanRemaining() == remaining[0], "stopped fan grants no exposure");
							return TransportedResult.doNothing();
						});
						charge(fan, sign * rpm);
					}
					ticks++;
				}
				helper.assertTrue(depot.getHeldItem().is(AllItems.DOUGH.get()), "actual water airflow washes flour into dough");
				if (rpm == 16) slow = ticks;
				if (rpm == 64) reference = ticks;
				if (rpm == 128) fast = ticks;
			}
			helper.assertTrue(slow > reference && reference > fast && fast > reference / 2, "higher airflow improves throughput with diminishing returns");
			depot.setHeldItem(AllItems.WHEAT_FLOUR.asStack());
			helper.setBlock(pos.east(), Blocks.AIR); fan.airCurrent.rebuild();
			for (int i = 0; i < 300; i++) fan.airCurrent.tickAffectedHandlers();
			helper.assertTrue(depot.getHeldItem().is(AllItems.WHEAT_FLOUR.get()), "washing still requires its medium");
			helper.setBlock(pos.east(), Blocks.WATER); fan.airCurrent.rebuild();
			var absolute = Vec3.atCenterOf(helper.absolutePos(pos.east(2)));
			var item = new ItemEntity(helper.getLevel(), absolute.x, absolute.y, absolute.z, AllItems.WHEAT_FLOUR.asStack());
			helper.getLevel().addFreshEntity(item);
			fan.airCurrent.findEntities();
			for (int i = 0; i < 200 && item.getItem().is(AllItems.WHEAT_FLOUR.get()); i++) fan.airCurrent.tick();
			helper.assertTrue(item.getItem().is(AllItems.DOUGH.get()), "dropped items use actual fan speed too");
			DifferentialCreate.LOGGER.info("FAN_THROUGHPUT rpm16={} rpm64={} rpm128={}", slow, reference, fast);
			helper.succeed();
		});
	}
	static void sequence(GameTestHelper helper) {
		BlockPos pos = new BlockPos(6, 3, 6);
		helper.setBlock(pos, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
			.setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X).setValue(SequencedGearshiftBlock.VERTICAL, false));
		helper.setBlock(pos.west(), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(pos.east(), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.runAfterDelay(5, () -> {
			SequencedGearshiftBlockEntity seq = helper.getBlockEntity(pos);
			KineticBlockEntity output = helper.getBlockEntity(pos.east());
			NetworkTopology.mark(seq); NetworkTopology.flush();
			sequenceLimits(helper);
			for (var modifier : new InstructionSpeedModifiers[]{InstructionSpeedModifiers.FORWARD, InstructionSpeedModifiers.BACK_FAST}) {
				for (var mode : new SequencerInstructions[]{SequencerInstructions.TURN_ANGLE, SequencerInstructions.TURN_DISTANCE}) {
					int goal = mode == SequencerInstructions.TURN_ANGLE ? 90 : 3;
					double units = mode == SequencerInstructions.TURN_ANGLE ? .3 : 1.0 / 512;
					for (double[] schedule : SCHEDULES) {
						seq.getInstructions().clear();
						seq.getInstructions().add(new Instruction(mode, modifier, goal));
						seq.getInstructions().add(new Instruction(SequencerInstructions.DELAY, 10));
						seq.getInstructions().add(new Instruction(SequencerInstructions.END));
						seq.source = helper.absolutePos(pos.west());
						seq.run(0); NetworkTopology.flush();
						var work = (ShaftWork) seq;
						helper.assertTrue(work.differentialcreate$workShaft(seq) == output, "sequencer measures the actual output rotor: source="
							+ seq.source + " outputNetwork=" + output.hasNetwork() + " connection=" + NetworkTopology.connection(seq, output));
						double travelledUnits = 0;
						int ticks = 0;
						while (Double.isFinite(work.differentialcreate$remainingTravel()) && ticks < 1000) {
							charge(seq, schedule[ticks % schedule.length]);
							seq.tick();
							double before = work.differentialcreate$remainingTravel();
							double angleBefore = ElasticKinetics.state(output).angle;
							try { MachiningClock.advance(helper.getLevel()); }
							catch (RuntimeException failure) {
								DifferentialCreate.LOGGER.error("SEQUENCE_FAILURE mode={} modifier={} tick={} rpm={} remaining={} outputSpeed={} members={}",
									mode, modifier, ticks, schedule[ticks % schedule.length], before, output.getSpeed(),
									seq.getOrCreateNetwork().members.keySet().stream().map(be -> be.getBlockState() + "=" + ElasticKinetics.state(be).physicalSpeed).toList());
								throw failure;
							}
							double delta = Math.abs(ElasticKinetics.state(output).angle - angleBefore) * 1200 * units;
							if (Double.isFinite(work.differentialcreate$remainingTravel()))
								helper.assertTrue(Math.abs((before - work.differentialcreate$remainingTravel()) * units - delta) < 1e-5,
									"instruction progress matches actual output travel");
							else {
								helper.assertTrue(travelledUnits < goal + 1e-5 && travelledUnits + delta >= goal - 1e-5,
									"instruction completes on the first physical crossing");
								helper.assertTrue(Math.abs(output.getSpeed()) > 0, "instruction completion preserves output coasting");
							}
							travelledUnits += delta;
							if (schedule[ticks % schedule.length] == 0)
								helper.assertTrue(work.differentialcreate$remainingTravel() == before, "stopped motion instruction waits");
							if (ticks == 3 && Double.isFinite(work.differentialcreate$remainingTravel())) {
								double saved = work.differentialcreate$remainingTravel();
								var tag = seq.saveWithFullMetadata(helper.getLevel().registryAccess());
								NetworkTopology.mark(seq);
								seq = (SequencedGearshiftBlockEntity) BlockEntity.loadStatic(seq.getBlockPos(), seq.getBlockState(), tag,
									helper.getLevel().registryAccess());
								helper.getLevel().setBlockEntity(seq);
								NetworkTopology.mark(seq); NetworkTopology.flush();
								work = (ShaftWork) seq;
								work.differentialcreate$workShaft(seq);
								helper.assertTrue(Math.abs(saved - work.differentialcreate$remainingTravel()) < 1e-9, "sequencer preserves fractional progress");
							}
							ticks++;
						}
						helper.assertTrue(ticks < 1000, "motion instruction completes");
						charge(seq, 0);
						for (int i = 0; i < 11; i++) seq.tick();
						helper.assertTrue(seq.isIdle(), "DELAY remains game time while stopped");
					}
				}
			}
			var first = SequencedGearshiftBlockEntity.SequenceContext.fromGearshift(SequencerInstructions.TURN_ANGLE, 16, 90);
			var restored = SequencedGearshiftBlockEntity.SequenceContext.fromNBT(first.serializeNBT());
			var next = SequencedGearshiftBlockEntity.SequenceContext.fromGearshift(SequencerInstructions.TURN_ANGLE, 16, 90);
			helper.assertTrue(ISequenceContext.same(first, restored), "instruction identity survives reload");
			helper.assertFalse(ISequenceContext.same(first, next), "repeated identical instructions have distinct identities");
			seq.getInstructions().clear();
			seq.getInstructions().add(new Instruction(SequencerInstructions.TURN_ANGLE, 90));
			seq.getInstructions().add(new Instruction(SequencerInstructions.END));
			seq.source = helper.absolutePos(pos.west());
			seq.run(0); NetworkTopology.flush();
			var laggedWork = (ShaftWork) seq;
			helper.assertTrue(laggedWork.differentialcreate$workShaft(seq) == output, "lagged output remains the measured shaft");
			charge(seq, 16);
			ElasticKinetics.state(output).physicalSpeed = 15.999;
			double before = laggedWork.differentialcreate$remainingTravel(), angle = ElasticKinetics.state(output).angle;
			MachiningClock.advance(helper.getLevel());
			double travelled = before - laggedWork.differentialcreate$remainingTravel();
			helper.assertTrue(Math.abs(travelled - Math.abs(ElasticKinetics.state(output).angle - angle) * 1200) < 1e-6,
				"elastic output lag uses real integrated travel");
			helper.assertTrue(Math.abs(travelled - 16) > 1e-6, "nominal input travel does not substitute for lagged output");
			seq.run(-1); NetworkTopology.flush();
			seq.getInstructions().clear();
			seq.getInstructions().add(new Instruction(SequencerInstructions.AWAIT));
			seq.getInstructions().add(new Instruction(SequencerInstructions.END));
			seq.run(0);
			for (int i = 0; i < 20; i++) seq.tick();
			helper.assertFalse(seq.isIdle(), "AWAIT waits for a redstone pulse");
			seq.onRedstoneUpdate(false, true); seq.onRedstoneUpdate(true, true);
			helper.assertTrue(seq.isIdle(), "redstone pulse continues AWAIT");
			BlockPos verticalPos = new BlockPos(26, 6, 6);
			helper.setBlock(verticalPos, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState().setValue(SequencedGearshiftBlock.VERTICAL, true));
			helper.setBlock(verticalPos.below(), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
			helper.setBlock(verticalPos.above(), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
			SequencedGearshiftBlockEntity vertical = helper.getBlockEntity(verticalPos);
			vertical.source = helper.absolutePos(verticalPos.below());
			vertical.run(0); NetworkTopology.flush();
			helper.assertTrue(((ShaftWork) vertical).differentialcreate$workShaft(vertical) == helper.getBlockEntity(verticalPos.above()),
				"vertical installation measures its actual upper output");
			vertical.run(-1); NetworkTopology.flush();
			helper.succeed();
		});
	}
	private static void sequenceLimits(GameTestHelper helper) {
		BlockPos bearingPos = new BlockPos(15, 3, 15), pulleyPos = new BlockPos(21, 3, 15);
		helper.setBlock(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState());
		helper.setBlock(pulleyPos, AllBlocks.ROPE_PULLEY.getDefaultState());
		KineticBlockEntity bearing = helper.getBlockEntity(bearingPos), pulley = helper.getBlockEntity(pulleyPos);
		NetworkTopology.mark(bearing); NetworkTopology.mark(pulley); NetworkTopology.flush();
		for (var original : new KineticBlockEntity[]{bearing, pulley}) {
			KineticBlockEntity controller = original;
			boolean angular = controller instanceof MechanicalBearingBlockEntity;
			var mode = angular ? SequencerInstructions.TURN_ANGLE : SequencerInstructions.TURN_DISTANCE;
			String key = angular ? "SequencedAngleLimit" : "SequencedOffsetLimit";
			double remaining = angular ? 37.25 : 1.25;
			controller.sequenceContext = SequencedGearshiftBlockEntity.SequenceContext.fromGearshift(mode, controller.getTheoreticalSpeed(), 90);
			controller.onSpeedChanged(0);
			var tag = controller.saveWithFullMetadata(helper.getLevel().registryAccess());
			tag.putDouble(key, remaining);
			NetworkTopology.mark(controller);
			controller = (KineticBlockEntity) BlockEntity.loadStatic(controller.getBlockPos(), controller.getBlockState(), tag,
				helper.getLevel().registryAccess());
			helper.getLevel().setBlockEntity(controller);
			helper.assertTrue(controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key) == remaining,
				"actuator reload retains remaining instruction: " + controller.getBlockState().getBlock()
				+ " remaining=" + controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key));
			NetworkTopology.mark(controller); NetworkTopology.flush();
			helper.assertTrue(controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key) == remaining,
				"actuator topology retains remaining instruction: " + controller.getBlockState().getBlock()
				+ " remaining=" + controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key));
			for (float rpm : new float[]{16, 0, -32, 128}) {
				charge(controller, rpm); controller.onSpeedChanged(16);
				helper.assertTrue(controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key) == remaining,
					"actuator preserves remaining instruction through reload, stop and reversal: " + controller.getBlockState().getBlock()
					+ " rpm=" + rpm + " remaining=" + controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key)
					+ " sequence=" + controller.sequenceContext);
			}
			controller.sequenceContext = SequencedGearshiftBlockEntity.SequenceContext.fromGearshift(mode, controller.getTheoreticalSpeed(), 90);
			controller.onSpeedChanged(16);
			helper.assertTrue(controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getDouble(key) == 90,
				"repeating the same instruction refreshes the actuator limit");
		}
	}
}
