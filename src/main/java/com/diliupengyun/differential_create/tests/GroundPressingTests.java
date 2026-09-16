package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Real ground-item recipes and complete press cycles, powered by a charged flywheel with no source. */
final class GroundPressingTests {

	private GroundPressingTests() {}

	static void single(GameTestHelper helper) {
		Fixture f = new Fixture(helper, false);
		GameTestSequence sequence = f.chargedSequence(32)
			.thenExecute(() -> f.drop(Items.IRON_INGOT, 1));
		f.cycle(sequence).thenExecute(() -> f.count(AllItems.IRON_SHEET.get(), 1));
		// Keep the actual first output entity, then supply the user's second ingot.
		sequence.thenExecute(() -> f.drop(Items.IRON_INGOT, 1));
		f.cycle(sequence).thenExecute(() -> {
			f.count(AllItems.IRON_SHEET.get(), 2);
			f.count(Items.IRON_INGOT, 0);
			f.drop(Items.BEDROCK, 3);
			f.drop(Items.IRON_INGOT, 3);
			f.drop(Items.COPPER_INGOT, 2);
			f.drop(Items.GOLD_INGOT, 1);
		});
		for (int i = 1; i <= 6; i++) {
			int processed = i;
			f.cycle(sequence).thenExecute(() -> {
				helper.assertTrue(f.sheets() == 2 + processed, "non-bulk contact must produce exactly one sheet per cycle: expected=" + (2 + processed) + ", actual=" + f.sheets());
				helper.assertTrue(f.ingots() == 6 - processed, "non-bulk contact must consume exactly one ingot across all stacks");
				f.count(Items.BEDROCK, 3);
			});
		}
		sequence.thenExecute(() -> {
			f.count(AllItems.IRON_SHEET.get(), 5);
			f.count(AllItems.COPPER_SHEET.get(), 2);
			f.count(AllItems.GOLDEN_SHEET.get(), 1);
		});
		f.finishIdle(sequence);
	}

	static void bulk(GameTestHelper helper) {
		Fixture f = new Fixture(helper, true);
		GameTestSequence sequence = f.chargedSequence(32).thenExecute(() -> {
			f.drop(AllItems.IRON_SHEET.get(), 1);
			f.drop(Items.BEDROCK, 3);
			f.drop(Items.IRON_INGOT, 3);
			f.drop(Items.COPPER_INGOT, 2);
			f.drop(Items.GOLD_INGOT, 1);
		});
		f.cycle(sequence).thenExecute(() -> {
			f.count(AllItems.IRON_SHEET.get(), 4);
			f.count(AllItems.COPPER_SHEET.get(), 2);
			f.count(AllItems.GOLDEN_SHEET.get(), 1);
			helper.assertTrue(f.ingots() == 0, "bulk contact must process every eligible stack in one cycle");
			f.count(Items.BEDROCK, 3);
		});
		f.finishIdle(sequence);
	}

	static void stall(GameTestHelper helper) {
		Fixture f = new Fixture(helper, false);
		int[] stoppedTicks = {0};
		CompoundTag[] stoppedWork = {null};
		GameTestSequence sequence = f.chargedSequence(32).thenExecute(() -> {
			f.drop(AllItems.IRON_SHEET.get(), 1);
			f.drop(Items.BEDROCK, 3);
			f.drop(Items.IRON_INGOT, 2);
		});
		sequence.thenWaitUntil(() -> helper.assertTrue(f.behaviour().runningTicks >= 80, "press must start its downstroke"))
			.thenExecute(() -> f.setSpeed(1))
			.thenWaitUntil(() -> helper.assertTrue(f.press.getSpeed() == 0, "low-energy flywheel must naturally stall"))
			.thenExecute(() -> {
				stoppedTicks[0] = f.behaviour().runningTicks;
				helper.assertTrue(stoppedTicks[0] >= 80 && stoppedTicks[0] < 100,
					"sub-integer shaft travel must not advance one unit every tick");
				stoppedWork[0] = f.savedWork();
			})
			.thenExecuteFor(20, () -> {
				helper.assertTrue(f.behaviour().running, "a stalled cycle must remain active");
				helper.assertTrue(f.behaviour().runningTicks == stoppedTicks[0], "stopped downstroke must retain integer progress");
				helper.assertTrue(f.savedWork().getDouble("DCPressPosition") == stoppedWork[0].getDouble("DCPressPosition"),
					"stopped downstroke must retain fractional progress");
				f.count(Items.IRON_INGOT, 2);
				f.count(AllItems.IRON_SHEET.get(), 1);
			})
			.thenExecute(() -> {
				f.behaviour().read(stoppedWork[0], helper.getLevel().registryAccess(), false);
				f.setSpeed(32);
			})
			.thenWaitUntil(() -> helper.assertTrue(f.behaviour().runningTicks >= 120, "resumed downstroke must reach contact"))
			.thenExecute(() -> f.setSpeed(0.5f))
			.thenExecuteAfter(1, () -> {
				f.count(AllItems.IRON_SHEET.get(), 2);
				f.count(Items.IRON_INGOT, 1);
			})
			.thenWaitUntil(() -> helper.assertTrue(f.press.getSpeed() == 0, "flywheel must stall again during return travel"))
			.thenExecute(() -> stoppedTicks[0] = f.behaviour().runningTicks)
			.thenExecuteFor(20, () -> {
				helper.assertTrue(f.behaviour().runningTicks == stoppedTicks[0], "stalled return travel must not repeat contact");
				f.count(AllItems.IRON_SHEET.get(), 2);
				f.count(Items.IRON_INGOT, 1);
			})
			.thenExecute(() -> f.setSpeed(32))
			.thenWaitUntil(() -> helper.assertFalse(f.behaviour().running, "resumed press must complete its return"));
		f.cycle(sequence).thenExecute(() -> {
			f.count(AllItems.IRON_SHEET.get(), 3);
			f.count(Items.IRON_INGOT, 0);
			f.count(Items.BEDROCK, 3);
		});
		f.finishIdle(sequence);
	}

	static void energyInvariant(GameTestHelper helper) {
		Fixture f = new Fixture(helper, false);
		GameTestSequence sequence = f.chargedSequence(16);
		double[] before = {0};
		for (float speed : new float[]{8, 16, 32, 64, 128, 256}) {
			sequence.thenExecute(() -> { f.setSpeed(speed); before[0] = f.energy(); f.drop(Items.IRON_INGOT, 1); });
			f.cycle(sequence).thenExecute(() -> {
				double work = before[0] - f.energy();
				// Small extra work is passive contact damping; elastic storage is included by energy().
				helper.assertTrue(Math.abs(work - 12.8) < 1e-4, "full press work plus contact loss at " + speed + " RPM: " + work + " expected approximately 12.8");
				com.diliupengyun.differential_create.DifferentialCreate.LOGGER.info("PRESS_WORK initialRpm={} consumed={} expected=12.8", speed, work);
			});
		}
		sequence.thenExecute(() -> {
			f.count(AllItems.IRON_SHEET.get(), 6);
			BlockPos depotPos = new BlockPos(6, 1, 4);
			helper.setBlock(depotPos, AllBlocks.DEPOT.getDefaultState());
			com.simibubi.create.content.logistics.depot.DepotBlockEntity depot = helper.getBlockEntity(depotPos);
			depot.setHeldItem(new ItemStack(Items.IRON_INGOT));
			f.setSpeed(256); before[0] = f.energy();
		});
		f.cycle(sequence).thenExecute(() -> {
			helper.assertTrue(Math.abs(before[0] - f.energy() - 12.8) < 1e-4, "depot pressing must use the same cycle work with small contact loss");
			com.simibubi.create.content.logistics.depot.DepotBlockEntity depot = helper.getBlockEntity(new BlockPos(6, 1, 4));
			var inventory = depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).itemHandler;
			int sheets = 0;
			for (int slot = 0; slot < inventory.getSlots(); slot++)
				if (inventory.getStackInSlot(slot).is(AllItems.IRON_SHEET.get())) sheets += inventory.getStackInSlot(slot).getCount();
			helper.assertTrue(sheets == 1, "belt-style contact must apply exactly one recipe on the depot");
		}).thenSucceed();
	}

	static void largeInertia(GameTestHelper helper) {
		Fixture f = new Fixture(helper, false, true);
		var sequence = f.chargedSequence(256);
		double[] before = {0};
		sequence.thenExecute(() -> {
			var network = f.press.getOrCreateNetwork();
			INetworkDynamics dynamics = (INetworkDynamics) network;
			double ratio = dynamics.differentialcreate$getRatios().get(f.press);
			double inertia = com.diliupengyun.differential_create.kinetics.NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios()).j() / (ratio * ratio);
			helper.assertTrue(Math.abs(inertia - 896.01) < 1e-5, "seven 16x flywheels must reproduce the high-inertia case: " + inertia);
			double precise = dynamics.differentialcreate$getSpeed() - 1e-8;
			dynamics.differentialcreate$setSpeed(precise);
			var tag = f.press.saveWithoutMetadata(helper.getLevel().registryAccess());
			helper.assertTrue(tag.getCompound("Network").getDouble("DCSpeed") == precise, "saved network speed must retain double precision");
			var clone = (MechanicalPressBlockEntity) f.press.getType().create(f.press.getBlockPos(), f.press.getBlockState());
			clone.setLevel(helper.getLevel()); clone.loadWithComponents(tag, helper.getLevel().registryAccess());
			var restored = new com.simibubi.create.content.kinetics.KineticNetwork();
			com.diliupengyun.differential_create.kinetics.NetworkDynamics.restoreMember(clone, restored);
			helper.assertTrue(((INetworkDynamics) restored).differentialcreate$getSpeed() == precise, "NBT restore must retain the exact double speed");
			NetworkTopology.mark(f.press); NetworkTopology.flush();
			dynamics = (INetworkDynamics) f.press.getOrCreateNetwork();
			helper.assertTrue(Math.abs(dynamics.differentialcreate$getSpeed() - precise) < 1e-11, "topology rebuild must not round the physical speed to float");
			before[0] = f.energy(); f.drop(Items.IRON_INGOT, 1);
		});
		f.cycle(sequence).thenExecute(() -> {
			double work = before[0] - f.energy();
			// Seven geared flywheels also dissipate work in their shaft contacts (below 0.01% of the cycle).
			helper.assertTrue(work >= 12.8 - 2e-6 && work < 12.801, "large-inertia press must debit a full cycle including contact loss: " + work);
			com.diliupengyun.differential_create.DifferentialCreate.LOGGER.info("PRESS_WORK seven16xFlywheels consumed={} expected=12.8", work);
			f.count(AllItems.IRON_SHEET.get(), 1);
		}).thenSucceed();
	}

	private static final class Fixture {
		private final GameTestHelper helper;
		private final MechanicalPressBlockEntity press;
		private final Vec3 itemPos;
		private final AABB itemBounds;
		private final boolean bulk;
		private Boolean previousBulk;
		private double chargedEnergy;
		private double idleEnergy;

		private Fixture(GameTestHelper helper, boolean bulk) { this(helper, bulk, false); }

		private Fixture(GameTestHelper helper, boolean bulk, boolean geared) {
			this.helper = helper;
			this.bulk = bulk;
			var config = AllConfigs.server().recipes.bulkPressing;
			// Each fixture has its own batch. Restore the process-wide setting on failure as well as success.
			helper.testInfo.addListener(new GameTestListener() {
				@Override public void testStructureLoaded(GameTestInfo info) {}
				private void restore() { if (previousBulk != null) config.set(previousBulk); }
				@Override public void testPassed(GameTestInfo info, GameTestRunner runner) { restore(); }
				@Override public void testFailed(GameTestInfo info, GameTestRunner runner) { restore(); }
				@Override public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { restore(); }
			});
			BlockPos pressPos = geared ? new BlockPos(14, 3, 14) : new BlockPos(6, 3, 4);
			if (geared) {
				BlockPos controller = pressPos.west(2);
				helper.setBlock(controller, AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
				((com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity) helper.getBlockEntity(controller)).targetSpeed.setValue(1);
				helper.setBlock(controller.above(), AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
				for (int i = 1; i <= 7; i++)
					helper.setBlock(controller.above().north(i), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
			} else helper.setBlock(pressPos.west(2), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			helper.setBlock(pressPos.west(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			helper.setBlock(pressPos, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
			helper.setBlock(pressPos.below(2), Blocks.STONE.defaultBlockState());
			press = helper.getBlockEntity(pressPos);
			BlockPos ground = helper.absolutePos(pressPos.below());
			itemPos = new Vec3(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
			itemBounds = new AABB(ground).inflate(1);
			helper.onEachTick(() -> {
				// Isolate selection and energy use from item drift, including newly spawned recipe outputs.
				for (ItemEntity item : items()) pin(item);
				if (behaviour().running) {
					helper.assertTrue(press.canProcessInBulk() == bulk, "fixture must retain its selected bulk pressing setting");
					helper.assertTrue(MachineActivity.impact(press) == 8, "downstroke, contact, return and stalled cycles must retain working torque");
				}
			});
		}

		private GameTestSequence chargedSequence(float speed) {
			return helper.startSequence().thenExecuteAfter(5, () -> {
				// A preceding batch's completion listeners can run after this fixture is constructed.
				var config = AllConfigs.server().recipes.bulkPressing;
				previousBulk = config.get();
				config.set(bulk);
				NetworkTopology.flush();
				helper.assertTrue(press.getOrCreateNetwork().sources.isEmpty(), "fixture must run entirely on stored energy");
				setSpeed(speed);
				chargedEnergy = energy();
			});
		}

		private GameTestSequence cycle(GameTestSequence sequence) {
			return sequence.thenWaitUntil(() -> helper.assertTrue(behaviour().running, "press must discover the next eligible ground item"))
				.thenWaitUntil(() -> helper.assertFalse(behaviour().running, "press must finish its full cycle"));
		}

		private void finishIdle(GameTestSequence sequence) {
			sequence.thenExecuteAfter(2, () -> {
				helper.assertTrue(energy() > 0 && energy() < chargedEnergy, "completed cycles must consume flywheel energy");
				idleEnergy = energy();
			}).thenExecuteFor(25, () -> {
				helper.assertFalse(behaviour().running, "only finished sheets and junk remain; press must stay idle");
				helper.assertTrue(MachineActivity.impact(press) == 0, "completed press must return to zero standby torque");
				helper.assertTrue(Math.abs(energy() - idleEnergy) < 1e-6, "idle press must not drain the remaining flywheel energy");
			}).thenSucceed();
		}

		private void setSpeed(float speed) {
			INetworkDynamics dynamics = (INetworkDynamics) press.getOrCreateNetwork();
			dynamics.differentialcreate$setSpeed(speed / dynamics.differentialcreate$getRatios().get(press));
		}

		private PressingBehaviour behaviour() { return press.getPressingBehaviour(); }

		private CompoundTag savedWork() {
			CompoundTag tag = new CompoundTag();
			behaviour().write(tag, helper.getLevel().registryAccess(), false);
			return tag;
		}

		private void drop(Item type, int count) {
			ItemEntity item = new ItemEntity(helper.getLevel(), itemPos.x, itemPos.y, itemPos.z, new ItemStack(type, count));
			pin(item);
			helper.getLevel().addFreshEntity(item);
		}

		private void pin(ItemEntity item) {
			item.setPos(itemPos);
			item.setDeltaMovement(Vec3.ZERO);
			// Let gravity establish real floor contact before the block entity ticks.
			item.setNoGravity(false);
			item.setOnGround(true);
		}

		private List<ItemEntity> items() {
			return helper.getLevel().getEntitiesOfClass(ItemEntity.class, itemBounds, ItemEntity::isAlive);
		}

		private int count(Item type) {
			return items().stream().filter(item -> item.getItem().is(type)).mapToInt(item -> item.getItem().getCount()).sum();
		}

		private void count(Item type, int expected) {
			int actual = count(type);
			helper.assertTrue(actual == expected, type + ": expected " + expected + ", got " + actual);
		}

		private int sheets() { return count(AllItems.IRON_SHEET.get()) + count(AllItems.COPPER_SHEET.get()) + count(AllItems.GOLDEN_SHEET.get()); }
		private int ingots() { return count(Items.IRON_INGOT) + count(Items.COPPER_INGOT) + count(Items.GOLD_INGOT); }

		private double energy() {
			double springs = ((INetworkDynamics) press.getOrCreateNetwork()).differentialcreate$data().connections.stream()
				.mapToDouble(edge -> .5 * edge.stiffness() * edge.twist * edge.twist).sum();
			return springs + press.getOrCreateNetwork().members.keySet().stream()
				.mapToDouble(member -> { double speed = com.diliupengyun.differential_create.kinetics.NetworkDynamics.preciseSpeed(member); return 0.5 * InertiaRegistry.getInertia(member) * speed * speed; }).sum();
		}
	}
}
