package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.content.BrassClutchBlockEntity;
import com.diliupengyun.differential_create.content.ClutchEngagementBehaviour;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.diliupengyun.differential_create.kinetics.IClutchDynamics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/** Real registered blocks, saved controls, loaded networks and the complete material recipe chain. */
final class BrassClutchTests {
	private BrassClutchTests() {}

	static void controls(GameTestHelper helper) {
		var clutches = new ArrayList<BrassClutchBlockEntity>();
		var drivers = new ArrayList<KineticBlockEntity>();
		var inputs = Direction.values();
		for (int i = 0; i < inputs.length; i++) {
			Direction input = inputs[i];
			BlockPos pos = new BlockPos(4 + 8 * (i % 3), 4, 4 + 12 * (i / 3));
			helper.setBlock(pos.relative(input, 2), AllBlocks.FLYWHEEL.getDefaultState()
				.setValue(BlockStateProperties.AXIS, input.getAxis()));
			helper.setBlock(pos.relative(input), AllBlocks.SHAFT.getDefaultState()
				.setValue(BlockStateProperties.AXIS, input.getAxis()));
			helper.setBlock(pos, ModContent.BRASS_CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, input.getAxis()));
			BrassClutchBlockEntity clutch = helper.getBlockEntity(pos);
			clutch.source = helper.absolutePos(pos.relative(input));
			NetworkTopology.mark(clutch);
			clutches.add(clutch);
			drivers.add(helper.getBlockEntity(pos.relative(input, 2)));
		}
		var player = helper.makeMockPlayer(GameType.CREATIVE);
		double[] released = new double[clutches.size()];
		helper.startSequence().thenExecuteAfter(5, () -> {
			for (int i = 0; i < clutches.size(); i++) {
				var clutch = clutches.get(i);
				near(helper, 1024, ClutchPhysics.torqueLimit(clutch), 0, "default brass rating");
				near(helper, 0, InertiaRegistry.getInertia(clutch), 0, "transmission inertia matches the starter clutch");
				setSpeed(drivers.get(i), i % 2 == 0 ? 16 : -16);
				var slot = (ValueBoxTransform.Sided) setting(clutch).getSlotPositioning();
				for (Direction face : Direction.values())
					helper.assertTrue(slot.fromSide(face).shouldRender(clutch.getLevel(), clutch.getBlockPos(), clutch.getBlockState())
						== (face.getAxis() != inputs[i].getAxis()), "control must avoid both shaft ends: " + inputs[i] + "/" + face);
				helper.assertFalse(setting(clutch).onlyVisibleWithWrench(), "control is visible and accepts interaction without a wrench");
			}
			setting(clutches.getFirst()).setValueSettings(player, new ValueSettings(0, 25), false);
			near(helper, 1024, ClutchPhysics.torqueLimit(clutches.get(1)), 0, "each clutch has its own setting");
		}).thenExecuteAfter(30, () -> {
			var first = clutches.getFirst();
			near(helper, 256, ClutchPhysics.torqueLimit(first), 0, "25 percent pressure");
			var board = setting(first).createBoard(player,
				new BlockHitResult(Vec3.atCenterOf(first.getBlockPos()), Direction.EAST, first.getBlockPos(), false));
			var text = board.formatter().format(new ValueSettings(0, 25));
			helper.assertTrue(text.getString().equals("25%"), "slider value is a compact percentage");
			var copy = new BrassClutchBlockEntity(first.getType(), first.getBlockPos(), first.getBlockState());
			copy.setLevel(helper.getLevel());
			copy.loadWithComponents(first.saveWithoutMetadata(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
			near(helper, .25, copy.manualEngagement(), 0, "pressure survives NBT");
			setting(copy).value = 100;
			copy.readClient(first.writeClient(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
			near(helper, .25, copy.manualEngagement(), 0, "pressure survives a client packet");
			var safe = new CompoundTag();
			first.writeSafe(safe, helper.getLevel().registryAccess());
			helper.assertTrue(safe.getInt(ClutchEngagementBehaviour.NBT_KEY) == 25, "schematics retain pressure");
			var clip = new CompoundTag();
			setting(first).writeToClipboard(helper.getLevel().registryAccess(), clip, Direction.EAST);
			setting(clutches.get(1)).readFromClipboard(helper.getLevel().registryAccess(), clip, player, Direction.EAST, false);
			near(helper, .25, clutches.get(1).manualEngagement(), 0, "clipboard transfers pressure");
			var invalid = new CompoundTag();
			invalid.putInt(ClutchEngagementBehaviour.NBT_KEY, 500);
			setting(copy).read(invalid, helper.getLevel().registryAccess(), false);
			near(helper, 1, copy.manualEngagement(), 0, "saved pressure cannot exceed its rating");
			invalid.putInt(ClutchEngagementBehaviour.NBT_KEY, -1);
			setting(copy).read(invalid, helper.getLevel().registryAccess(), false);
			near(helper, 0, copy.manualEngagement(), 0, "negative saved pressure is clamped");
			setting(copy).read(new CompoundTag(), helper.getLevel().registryAccess(), false);
			near(helper, 1, copy.manualEngagement(), 0, "missing setting defaults to fully engaged");
			for (var clutch : clutches) {
				helper.assertTrue(state(clutch).differentialcreate$isLocked(), "an unloaded partial clutch synchronizes");
				setting(clutch).setValue(25);
				signal(helper, clutch, 7);
			}
		}).thenExecuteAfter(4, () -> {
			for (var clutch : clutches) {
				near(helper, 256.0 * 8 / 15, ClutchPhysics.torqueLimit(clutch), 1e-9, "redstone multiplies stored pressure");
				assertCompactGoggles(helper, clutch);
				signal(helper, clutch, 15);
			}
		}).thenExecuteAfter(4, () -> {
			for (int i = 0; i < clutches.size(); i++) {
				var clutch = clutches.get(i);
				assertOpen(helper, clutch);
				near(helper, .25, clutch.manualEngagement(), 0, "redstone release preserves the slider");
				released[i] = state(clutch).differentialcreate$getFreeOutputSpeed();
				setSpeed(drivers.get(i), i % 2 == 0 ? -24 : 24);
				setting(clutch).setValue(50);
			}
		}).thenExecuteAfter(4, () -> {
			for (int i = 0; i < clutches.size(); i++) {
				var clutch = clutches.get(i);
				near(helper, released[i], state(clutch).differentialcreate$getFreeOutputSpeed(), 1e-6,
					"editing the slider while redstone holds it open cannot move the free output");
				setting(clutch).setValue(25);
				signal(helper, clutch, 0);
			}
		}).thenExecuteAfter(30, () -> {
			for (var clutch : clutches) {
				helper.assertTrue(state(clutch).differentialcreate$isLocked(), "removing redstone restores partial engagement");
				setting(clutch).setValue(0);
			}
		}).thenExecuteAfter(3, () -> {
			for (int i = 0; i < clutches.size(); i++) {
				var clutch = clutches.get(i);
				assertOpen(helper, clutch);
				released[i] = state(clutch).differentialcreate$getFreeOutputSpeed();
				setSpeed(drivers.get(i), i % 2 == 0 ? -32 : 32);
			}
		}).thenExecuteAfter(10, () -> {
			for (int i = 0; i < clutches.size(); i++) {
				var clutch = clutches.get(i);
				near(helper, released[i], state(clutch).differentialcreate$getFreeOutputSpeed(), 1e-6,
					"manual zero must retain the free output speed while input changes");
				signal(helper, clutch, 15);
			}
		}).thenExecuteAfter(4, () -> {
			for (int i = 0; i < clutches.size(); i++) {
				var clutch = clutches.get(i);
				near(helper, released[i], state(clutch).differentialcreate$getFreeOutputSpeed(), 1e-6,
					"a redstone release while manually open must not recapture input speed");
				signal(helper, clutch, 0);
				setting(clutch).setValue(5);
			}
		}).thenExecuteAfter(30, () -> {
			for (var clutch : clutches) {
				helper.assertTrue(state(clutch).differentialcreate$isLocked(), "manual re-engagement locks in every direction");
				near(helper, clutch.getSpeed(), state(clutch).differentialcreate$getFreeOutputSpeed(), 1e-6, "bare output resynchronizes");
			}
		}).thenSucceed();
	}

	static void load(GameTestHelper helper) {
		var clutches = new ArrayList<ClutchBlockEntity>();
		var motors = new ArrayList<CreativeMotorBlockEntity>();
		var outputs = new ArrayList<MechanicalPressBlockEntity>();
		var loads = new ArrayList<MechanicalPressBlockEntity>();
		for (int fixture = 0; fixture < 3; fixture++) {
			int z = 3 + 8 * fixture;
			motors.add(motor(helper, new BlockPos(2, 3, z), fixture == 1 ? -16 : 16));
			helper.setBlock(new BlockPos(3, 3, z), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			BlockPos pos = new BlockPos(4, 3, z);
			helper.setBlock(pos, (fixture == 2 ? AllBlocks.CLUTCH.getDefaultState() : ModContent.BRASS_CLUTCH.getDefaultState())
				.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			ClutchBlockEntity clutch = helper.getBlockEntity(pos);
			clutch.source = helper.absolutePos(pos.relative(fixture == 1 ? Direction.EAST : Direction.WEST));
			NetworkTopology.mark(clutch);
			clutches.add(clutch);
			for (int x = 5; x < 21; x++) {
				helper.setBlock(new BlockPos(x, 3, z), AllBlocks.MECHANICAL_PRESS.getDefaultState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
				MechanicalPressBlockEntity press = helper.getBlockEntity(new BlockPos(x, 3, z));
				press.getPressingBehaviour().start(PressingBehaviour.Mode.WORLD);
				loads.add(press);
				if (x == 5) outputs.add(press);
			}
		}
		// Keep actual press cycles active to represent 16 x 8 = 128 continuous torque per fixture.
		helper.onEachTick(() -> {
			for (var press : loads)
				if (!press.isRemoved() && (!press.getPressingBehaviour().running || press.getPressingBehaviour().runningTicks >= 80))
					press.getPressingBehaviour().start(PressingBehaviour.Mode.WORLD);
		});
		helper.startSequence().thenExecuteAfter(120, () -> {
			for (var clutch : clutches) assertCompactGoggles(helper, clutch);
			for (int i = 0; i < 2; i++) {
				assertLoaded(helper, clutches.get(i), motors.get(i), outputs.get(i));
				setting((BrassClutchBlockEntity) clutches.get(i)).setValue(25);
			}
			near(helper, 0, outputs.get(2).getSpeed(), 1e-6, "the same load stalls the starter clutch");
			near(helper, 32, Math.abs(state(clutches.get(2)).differentialcreate$getLastCouplingTorque()), .01, "starter capacity remains 32");
		}).thenExecuteAfter(10, () -> {
			for (int i = 0; i < 2; i++) {
				assertLoaded(helper, clutches.get(i), motors.get(i), outputs.get(i));
				setting((BrassClutchBlockEntity) clutches.get(i)).setValue(10);
			}
		}).thenExecuteAfter(10, () -> {
			for (int i = 0; i < 2; i++) {
				var clutch = clutches.get(i);
				helper.assertFalse(state(clutch).differentialcreate$isLocked(), "reducing pressure below load releases the lock");
				helper.assertFalse(motors.get(i).getOrCreateNetwork() == outputs.get(i).getOrCreateNetwork(), "overload splits networks");
				near(helper, 102.4, Math.abs(state(clutch).differentialcreate$getLastCouplingTorque()), .02, "slip obeys the manual ceiling");
				setting((BrassClutchBlockEntity) clutch).setValue(50);
				signal(helper, clutch, 15);
			}
		}).thenExecuteAfter(4, () -> {
			for (int i = 0; i < 2; i++) {
				assertOpen(helper, clutches.get(i));
				signal(helper, clutches.get(i), 7);
			}
		}).thenExecuteAfter(120, () -> {
			for (int i = 0; i < 2; i++) {
				assertLoaded(helper, clutches.get(i), motors.get(i), outputs.get(i));
				near(helper, 512.0 * 8 / 15, ClutchPhysics.torqueLimit(clutches.get(i)), 1e-9, "restored pressure retains analog redstone control");
				setting((BrassClutchBlockEntity) clutches.get(i)).setValue(0);
			}
		}).thenExecuteAfter(4, () -> {
			for (int i = 0; i < 2; i++) {
				assertOpen(helper, clutches.get(i));
				helper.assertFalse(motors.get(i).getOrCreateNetwork() == outputs.get(i).getOrCreateNetwork(), "zero slider disconnects loaded networks");
			}
		}).thenSucceed();
	}

	static void recipes(GameTestHelper helper) {
		BlockPos basinPos = new BlockPos(8, 1, 8), pressPos = basinPos.above(2);
		helper.setBlock(basinPos, AllBlocks.BASIN.getDefaultState());
		helper.setBlock(pressPos, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		motor(helper, pressPos.west(), 32);
		BasinBlockEntity basin = helper.getBlockEntity(basinPos);
		var ingredients = List.of(AllItems.IRON_SHEET.asStack(), new ItemStack(Items.LEATHER),
			AllItems.POWDERED_OBSIDIAN.asStack(), new ItemStack(Items.SLIME_BALL));
		for (int i = 0; i < ingredients.size(); i++) basin.getInputInventory().setStackInSlot(i, ingredients.get(i));
		basin.notifyChangeOfContents();
		helper.startSequence().thenExecuteAfter(5, () -> {
			var recipe = helper.getLevel().getRecipeManager().byKey(id("compacting/composite_friction_plate")).orElseThrow().value();
			helper.assertTrue(recipe instanceof BasinRecipe, "material is registered as a basin processing recipe");
			helper.assertTrue(BasinRecipe.match(basin, recipe), "all four existing ingredients match in an unheated basin");
		}).thenExecuteAfter(180, () -> {
			helper.assertTrue(count(basin.getOutputInventory(), ModContent.COMPOSITE_FRICTION_PLATE.asStack()) == 2,
				"a real powered press must produce two friction plates");
			for (int slot = 0; slot < basin.getInputInventory().getSlots(); slot++)
				helper.assertTrue(basin.getInputInventory().getStackInSlot(slot).isEmpty(), "press consumes exactly one of each ingredient");
			var recipe = (CraftingRecipe) helper.getLevel().getRecipeManager().byKey(id("crafting/brass_clutch")).orElseThrow().value();
			var grid = new ArrayList<ItemStack>(List.of(AllBlocks.CLUTCH.asStack(), AllBlocks.BRASS_CASING.asStack(),
				AllItems.PRECISION_MECHANISM.asStack(), ModContent.COMPOSITE_FRICTION_PLATE.asStack(),
				ModContent.COMPOSITE_FRICTION_PLATE.asStack(), ModContent.COMPOSITE_FRICTION_PLATE.asStack(),
				ModContent.COMPOSITE_FRICTION_PLATE.asStack(), ItemStack.EMPTY, ItemStack.EMPTY));
			CraftingInput input = CraftingInput.of(3, 3, grid);
			helper.assertTrue(recipe.matches(input, helper.getLevel()), "upgrade accepts the starter clutch and four plates");
			var result = recipe.assemble(input, helper.getLevel().registryAccess());
			helper.assertTrue(result.is(ModContent.BRASS_CLUTCH.asItem()) && result.getCount() == 1, "upgrade produces one brass clutch");
			grid.set(6, ItemStack.EMPTY);
			helper.assertFalse(recipe.matches(CraftingInput.of(3, 3, grid), helper.getLevel()), "three plates cannot satisfy the four-plate upgrade");
			BlockPos pos = new BlockPos(14, 2, 8);
			helper.setBlock(pos, ModContent.BRASS_CLUTCH.getDefaultState());
			var loot = net.minecraft.world.level.block.Block.getDrops(helper.getBlockState(pos), helper.getLevel(),
				helper.absolutePos(pos), helper.getBlockEntity(pos));
			helper.assertTrue(loot.size() == 1 && loot.getFirst().is(ModContent.BRASS_CLUTCH.asItem()), "breaking the block drops its own item");
		}).thenSucceed();
	}

	private static CreativeMotorBlockEntity motor(GameTestHelper helper, BlockPos pos, int speed) {
		helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
		CreativeMotorBlockEntity motor = helper.getBlockEntity(pos);
		motor.generatedSpeed.setValue(speed);
		return motor;
	}

	private static ClutchEngagementBehaviour setting(BrassClutchBlockEntity clutch) {
		return (ClutchEngagementBehaviour) clutch.getBehaviour(ScrollValueBehaviour.TYPE);
	}

	private static IClutchDynamics state(ClutchBlockEntity clutch) { return (IClutchDynamics) clutch; }

	private static void assertCompactGoggles(GameTestHelper helper, ClutchBlockEntity clutch) {
		var tooltip = new ArrayList<Component>();
		KineticGoggleInfo.append(clutch, tooltip, false);
		var clutchFields = tooltip.stream()
			.filter(line -> line.getContents() instanceof TranslatableContents)
			.map(line -> ((TranslatableContents) line.getContents()).getKey())
			.filter(key -> key.startsWith("gui.differentialcreate.goggles.clutch_")
				|| key.startsWith("gui.differentialcreate.clutch."))
			.toList();
		helper.assertTrue(clutchFields.equals(List.of("gui.differentialcreate.goggles.clutch_torque",
			"gui.differentialcreate.goggles.clutch_slip")), "both clutch types show only torque and slip among clutch-specific fields");
	}

	private static void signal(GameTestHelper helper, ClutchBlockEntity clutch, int signal) {
		Direction face = clutch.getBlockState().getValue(BlockStateProperties.AXIS) == Direction.Axis.Y ? Direction.EAST : Direction.UP;
		BlockPos pos = clutch.getBlockPos().relative(face);
		var level = helper.getLevel();
		if (!level.getBlockState(pos).is(Blocks.TARGET)) level.setBlockAndUpdate(pos, Blocks.TARGET.defaultBlockState());
		level.setBlockAndUpdate(pos, Blocks.TARGET.defaultBlockState().setValue(BlockStateProperties.POWER, signal));
		helper.assertTrue(clutch.getLevel().getBestNeighborSignal(clutch.getBlockPos()) == signal,
			"test emitter placement: expected " + signal + ", position=" + pos + ", actual="
				+ clutch.getLevel().getBlockState(clutch.getBlockPos().relative(face)) + ", best="
				+ clutch.getLevel().getBestNeighborSignal(clutch.getBlockPos()));
	}

	private static void setSpeed(KineticBlockEntity be, double speed) {
		INetworkDynamics dynamics = (INetworkDynamics) be.getOrCreateNetwork();
		dynamics.differentialcreate$setSpeed(speed / dynamics.differentialcreate$getRatios().get(be));
	}

	private static void assertOpen(GameTestHelper helper, ClutchBlockEntity clutch) {
		near(helper, 0, ClutchPhysics.torqueLimit(clutch), 0, "open clutch has zero capacity");
		near(helper, 0, state(clutch).differentialcreate$getLastCouplingTorque(), 0, "open clutch transfers no torque");
		helper.assertFalse(state(clutch).differentialcreate$isLocked(), "open clutch releases its lock");
	}

	private static void assertLoaded(GameTestHelper helper, ClutchBlockEntity clutch, KineticBlockEntity motor, KineticBlockEntity output) {
		helper.assertFalse(clutch.isRemoved(), "brass clutch survives engagement");
		helper.assertTrue(state(clutch).differentialcreate$isLocked(), "brass clutch locks under 128 torque");
		helper.assertTrue(motor.getOrCreateNetwork() == output.getOrCreateNetwork(), "loaded clutch connects both networks");
		helper.assertTrue(Math.abs(output.getSpeed()) > 10, "loaded factory reaches operating speed");
		near(helper, NetworkDynamics.preciseSpeed(motor), NetworkDynamics.preciseSpeed(output), .001, "partial pressure still permits full synchronization");
		near(helper, 128, Math.abs(state(clutch).differentialcreate$getLastCouplingTorque()), .2, "actual transmitted torque follows the load");
	}

	private static int count(IItemHandler inventory, ItemStack item) {
		int count = 0;
		for (int slot = 0; slot < inventory.getSlots(); slot++)
			if (inventory.getStackInSlot(slot).is(item.getItem())) count += inventory.getStackInSlot(slot).getCount();
		return count;
	}

	private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("differentialcreate", path); }

	private static void near(GameTestHelper helper, double expected, double actual, double tolerance, String message) {
		helper.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
			message + ": expected " + expected + ", got " + actual);
	}
}
