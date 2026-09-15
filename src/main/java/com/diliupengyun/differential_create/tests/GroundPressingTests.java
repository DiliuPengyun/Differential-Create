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
				helper.assertTrue(f.sheets() == 2 + processed, "non-bulk contact must produce exactly one sheet per cycle");
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
				helper.assertTrue(f.savedWork().getDouble("DCWorkFraction") == stoppedWork[0].getDouble("DCWorkFraction"),
					"stopped downstroke must retain fractional progress");
				f.count(Items.IRON_INGOT, 2);
				f.count(AllItems.IRON_SHEET.get(), 1);
			})
			.thenExecute(() -> {
				f.behaviour().read(stoppedWork[0], helper.getLevel().registryAccess(), false);
				f.setSpeed(32);
			})
			.thenWaitUntil(() -> helper.assertTrue(f.behaviour().runningTicks == 120, "resumed downstroke must reach contact"))
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

	private static final class Fixture {
		private final GameTestHelper helper;
		private final MechanicalPressBlockEntity press;
		private final Vec3 itemPos;
		private final AABB itemBounds;
		private final boolean bulk;
		private Boolean previousBulk;
		private double chargedEnergy;
		private double idleEnergy;

		private Fixture(GameTestHelper helper, boolean bulk) {
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
			BlockPos pressPos = new BlockPos(6, 3, 4);
			helper.setBlock(pressPos.west(2), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
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
			return press.getOrCreateNetwork().members.keySet().stream()
				.mapToDouble(member -> 0.5 * InertiaRegistry.getInertia(member) * member.getSpeed() * member.getSpeed()).sum();
		}
	}
}
