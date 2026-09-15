package com.diliupengyun.differential_create.tests;

import java.util.List;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/** Real Create recipe callbacks driven by the production clock; recharge between steps to test speed schedules. */
final class MachiningTests {
	private MachiningTests() {}
	static void run(GameTestHelper helper) {
		BlockPos millPos = new BlockPos(4, 3, 4), sawPos = new BlockPos(8, 3, 4), deployerPos = new BlockPos(12, 3, 4);
		BlockPos mixerPos = new BlockPos(16, 3, 4), crafterPos = new BlockPos(20, 3, 4), crusherPos = new BlockPos(5, 3, 16);
		helper.setBlock(millPos, AllBlocks.MILLSTONE.getDefaultState());
		helper.setBlock(sawPos, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
		helper.setBlock(deployerPos, AllBlocks.DEPLOYER.getDefaultState());
		helper.setBlock(mixerPos, AllBlocks.MECHANICAL_MIXER.getDefaultState());
		helper.setBlock(mixerPos.below(2), AllBlocks.BASIN.getDefaultState());
		helper.setBlock(crafterPos, AllBlocks.MECHANICAL_CRAFTER.getDefaultState());
		helper.setBlock(crusherPos.west(), AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		helper.setBlock(crusherPos.east(), AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		helper.setBlock(crusherPos, AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState());
		helper.runAfterDelay(5, () -> {
			NetworkTopology.flush();
			MillstoneBlockEntity mill = helper.getBlockEntity(millPos);
			SawBlockEntity saw = helper.getBlockEntity(sawPos);
			DeployerBlockEntity deployer = helper.getBlockEntity(deployerPos);
			MechanicalMixerBlockEntity mixer = helper.getBlockEntity(mixerPos);
			BasinBlockEntity basin = helper.getBlockEntity(mixerPos.below(2));
			MechanicalCrafterBlockEntity crafter = helper.getBlockEntity(crafterPos);
			CrushingWheelControllerBlockEntity crusher = helper.getBlockEntity(crusherPos);
			KineticBlockEntity left = helper.getBlockEntity(crusherPos.west()), right = helper.getBlockEntity(crusherPos.east());
			for (var shaft : List.of(mill, saw, deployer, mixer, crafter, left, right)) NetworkTopology.mark(shaft);
			NetworkTopology.flush();
			for (double[] schedule : new double[][]{{16}, {64}, {256}, {16, 256, 32, 128, 64}, {0, 16, 256, -32, 0, 128, -64}}) {
				mill.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT));
				java.util.Optional<net.minecraft.world.item.crafting.RecipeHolder<com.simibubi.create.content.kinetics.millstone.MillingRecipe>> recipe = AllRecipeTypes.MILLING.find(new RecipeWrapper(mill.inputInv), helper.getLevel());
				int duration = recipe.orElseThrow().value().getProcessingDuration();
				mill.timer = duration;
				cycle(helper, "mill", (ShaftWork) mill, List.of(mill), schedule, 4.0 * duration * 16 / 1200);
				helper.assertTrue(mill.inputInv.getStackInSlot(0).isEmpty() && mill.outputInv.getStackInSlot(0).is(AllItems.WHEAT_FLOUR.get()), "mill must execute its real recipe");
				saw.inventory.clear(); saw.inventory.insertItem(0, new ItemStack(Items.OAK_LOG), false);
				cycle(helper, "saw", (ShaftWork) saw, List.of(saw), schedule, 4.0 * (saw.inventory.remainingTime - 5 + 20) * 24 / 1200);
				helper.assertTrue(saw.inventory.isEmpty(), "saw must eject the processed output");
				var deployerTag = deployer.saveWithFullMetadata(helper.getLevel().registryAccess());
				deployerTag.putString("State", "EXPANDING"); deployerTag.putInt("Timer", 1000); deployerTag.putDouble("DCWorkFraction", 0);
				deployer.loadWithComponents(deployerTag, helper.getLevel().registryAccess());
				NetworkTopology.mark(deployer); NetworkTopology.flush();
				cycle(helper, "deployer", (ShaftWork) deployer, List.of(deployer), schedule, 4.0 * 1000 / 1200);
				helper.assertTrue(MachinePhase.of(deployer).equals("WAITING"), "deployer must complete extension and retraction");
				basin.getInputInventory().setStackInSlot(0, new ItemStack(Items.ANDESITE));
				basin.getInputInventory().setStackInSlot(1, new ItemStack(Items.IRON_NUGGET));
				mixer.startProcessingBasin();
				cycle(helper, "mixer", (ShaftWork) mixer, List.of(mixer), schedule, 4.0 * 85 * 64 / 1200);
				helper.assertTrue(!mixer.running && basin.getInputInventory().getStackInSlot(0).isEmpty(), "mixer must apply the basin recipe and return");
				charge(crafter, schedule[0] == 0 ? 16 : schedule[0]);
				crafter.getInventory().setStackInSlot(0, new ItemStack(Items.OAK_LOG)); crafter.checkCompletedRecipe(false);
				cycle(helper, "crafter", (ShaftWork) crafter, List.of(crafter), schedule, 2.0 * 2001 / 1200);
				crusher.inventory.clear(); crusher.inventory.insertItem(0, new ItemStack(Items.COBBLESTONE), false);
				double crushingDuration = crusher.inventory.remainingTime;
				cycle(helper, "crusher", (ShaftWork) crusher, List.of(left, right), schedule, 16.0 * Math.max(0, crushingDuration - 20) * 12.5 / 1200);
				helper.assertTrue(crusher.inventory.isEmpty(), "crusher must apply its recipe and eject the output");
			}
			crusher.inventory.insertItem(0, new ItemStack(Items.COBBLESTONE, 64), false);
			cycle(helper, "crusher64", (ShaftWork) crusher, List.of(left, right), new double[]{256},
				16.0 * Math.max(0, crusher.inventory.remainingTime - 20) * 64 * 12.5 / 1200);
			helper.assertTrue(crusher.inventory.isEmpty(), "crusher must complete the full batch");
			helper.succeed();
		});
	}

	private static void charge(KineticBlockEntity be, double speed) {
		INetworkDynamics dynamics = (INetworkDynamics) be.getOrCreateNetwork();
		if (dynamics == null) throw new IllegalStateException("No network for " + be.getBlockState() + " at " + be.getBlockPos());
		dynamics.differentialcreate$setSpeed(speed / dynamics.differentialcreate$getRatios().get(be));
	}
	private static double energy(KineticBlockEntity be) {
		INetworkDynamics dynamics = (INetworkDynamics) be.getOrCreateNetwork();
		if (dynamics == null) throw new IllegalStateException("No network for " + be.getBlockState() + " at " + be.getBlockPos());
		double j = dynamics.differentialcreate$getRatios().entrySet().stream().mapToDouble(e -> InertiaRegistry.getInertia(e.getKey()) * e.getValue() * e.getValue()).sum();
		return .5 * j * Math.pow(dynamics.differentialcreate$getSpeed(), 2);
	}
	private static void cycle(GameTestHelper helper, String name, ShaftWork work, List<KineticBlockEntity> shafts, double[] schedule, double expected) {
		helper.assertTrue(Double.isFinite(work.differentialcreate$remainingTravel()), name + " must start its work phase");
		double used = 0;
		int steps = 0;
		while (Double.isFinite(work.differentialcreate$remainingTravel()) && steps < 10000) {
			for (int i = 0; i < shafts.size(); i++)
				charge(shafts.get(i), schedule[steps % schedule.length] * (i == 0 ? 1 : -1));
			double before = shafts.stream().mapToDouble(MachiningTests::energy).sum();
			double remainingBefore = work.differentialcreate$remainingTravel();
			MachiningClock.advance(helper.getLevel());
			double debit = before - shafts.stream().mapToDouble(MachiningTests::energy).sum();
			if (name.equals("mill")) {
				double after = work.differentialcreate$remainingTravel();
				double earned = (remainingBefore - (Double.isFinite(after) ? after : 0)) * 4 / 1200;
				helper.assertTrue(Math.abs(debit - earned) < 1e-7, "mill tick=" + steps + " debit=" + debit + " earned=" + earned + " remaining=" + remainingBefore + " -> " + after);
			}
			used += debit;
			steps++;
			if (steps == 3 && work instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
				double savedRemaining = work.differentialcreate$remainingTravel();
				var tag = be.saveWithFullMetadata(helper.getLevel().registryAccess());
				be.loadWithComponents(tag, helper.getLevel().registryAccess());
				if (be instanceof KineticBlockEntity kinetic) {
					NetworkTopology.mark(kinetic); NetworkTopology.flush();
					long memberships = MachiningClock.networks(helper.getLevel()).stream().filter(network -> network.members.containsKey(kinetic)).count();
					helper.assertTrue(memberships == 1, "restored machine must belong to exactly one registered network");
				}
				helper.assertTrue(Math.abs(savedRemaining - work.differentialcreate$remainingTravel()) < 1e-9,
					name + " save changed remaining work: " + savedRemaining + " -> " + work.differentialcreate$remainingTravel() + " tag=" + tag);
			}
		}
		helper.assertTrue(steps < 10000, name + " phase must finish");
		helper.assertTrue(Math.abs(used - expected) < 1e-7, name + " work at " + java.util.Arrays.toString(schedule) + ": " + used + " expected " + expected);
		DifferentialCreate.LOGGER.info("MACHINE_WORK machine={} rpm={} consumed={} expected={}", name, java.util.Arrays.toString(schedule), used, expected);
	}
}
