package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllEnchantments;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.armor.BacktankBlock;
import com.simibubi.create.content.equipment.armor.BacktankBlockEntity;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class BacktankTests {
    private static final BlockPos POS = new BlockPos(6, 3, 6);

    static void motion(GameTestHelper h, boolean netherite, double speed) {
        fixture(h, netherite, tank -> {
            double inertia = InertiaRegistry.getInertia(tank), torque = tank.calculateStressApplied();
            double movingTime = Math.min(RotationMath.TICK_MINUTES, inertia * Math.abs(speed) / torque);
            double turns = Math.abs(speed) * movingTime - torque / (2 * inertia) * movingTime * movingTime;
            setSpeed(tank, speed);
            // Advance one real common-clock tick from this controlled initial condition.
            MachiningClock.advance(h.getLevel());
            near(h, tank.airLevel + fraction(tank), turns * 1200 / PhysicsTuning.BACKTANK_TRAVEL_PER_AIR,
                1e-7, "air equals paid absolute shaft travel, through a natural stop and at high speed");
            near(h, ElasticKinetics.state(tank).physicalSpeed,
                Math.copySign(Math.max(0, Math.abs(speed) - torque / inertia * RotationMath.TICK_MINUTES), speed),
                1e-7, "compressor torque is charged once");
            h.succeed();
        });
    }

    static void full(GameTestHelper h, boolean netherite, int direction) {
        fixture(h, netherite, tank -> {
            int capacity = BacktankUtil.maxAir(0);
            tank.setAirLevel(capacity - 1);
            var work = (ShaftWork) tank;
            work.differentialcreate$advanceTravel(96); // Three quarters already paid before this tick.
            near(h, work.differentialcreate$remainingTravel(), 32, 1e-10, "last quarter's exact work budget");
            double inertia = InertiaRegistry.getInertia(tank), torque = tank.calculateStressApplied();
            setSpeed(tank, direction * 128);
            MachiningClock.advance(h.getLevel());
            double expected = direction * Math.sqrt(128 * 128 - 2 * torque * (32d / 1200) / inertia);
            near(h, ElasticKinetics.state(tank).physicalSpeed, expected, 1e-6,
                "full tank releases torque at the boundary inside the tick");
            h.assertTrue(tank.airLevel == capacity && fraction(tank) == 0
                && !MachineActivity.active(tank) && MachineActivity.impact(tank) == 0, "full tank has no leftover work load or credit");
            h.assertTrue(tank.getComparatorOutput() == 15, "native full comparator output");
            MachiningClock.advance(h.getLevel());
            near(h, ElasticKinetics.state(tank).physicalSpeed, expected, 1e-6, "full tank coasts with retained inertia");
            tank.setAirLevel(capacity - 1);
            near(h, work.differentialcreate$remainingTravel(), 128, 1e-10, "used air cannot reuse old full-tank credit");
            h.succeed();
        });
    }

    static void states(GameTestHelper h, boolean netherite) {
        fixture(h, netherite, tank -> {
            var work = (ShaftWork) tank;
            double inertia = InertiaRegistry.getInertia(tank);
            h.assertTrue(MachineActivity.active(tank) && MachineActivity.impact(tank) == 4, "empty stationary tank retains startup resistance");
            setSpeed(tank, 128);
            tank.tick();
            h.assertTrue(tank.airLevel == 0 && fraction(tank) == 0, "native server tick grants no unpaid air");
            work.differentialcreate$advanceTravel(32);
            var wet = tank.getBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
            h.getLevel().setBlock(tank.getBlockPos(), wet, Block.UPDATE_ALL);
            h.assertTrue(!MachineActivity.active(tank) && MachineActivity.impact(tank) == 0
                && InertiaRegistry.getInertia(tank) == inertia, "waterlogging pauses work without removing inertia");
            tank.tick();
            work.differentialcreate$advanceTravel(128);
            near(h, fraction(tank), .25, 1e-10, "waterlogging preserves prepaid fraction and adds no work");
            h.getLevel().setBlock(tank.getBlockPos(), wet.setValue(BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
            setSpeed(tank, 0);
            tank.tick();
            near(h, fraction(tank), .25, 1e-10, "standstill preserves prepaid fraction");
            h.assertTrue(MachineActivity.active(tank) && MachineActivity.impact(tank) == 4, "drained tank resumes startup resistance");
            work.differentialcreate$advanceTravel(96);
            h.assertTrue(tank.airLevel == 1 && fraction(tank) == 0, "resume earns the remaining fraction exactly once");
            h.succeed();
        });
    }

    static void persistence(GameTestHelper h, boolean netherite) {
        fixture(h, netherite, tank -> {
            var level = h.getLevel();
            var registry = level.registryAccess();
            var item = netherite ? AllItems.NETHERITE_BACKTANK.asStack() : AllItems.COPPER_BACKTANK.asStack();
            item.enchant(level.holderOrThrow(AllEnchantments.CAPACITY), 2);
            item.set(AllDataComponents.BACKTANK_AIR, 41);
            item.set(DataComponents.CUSTOM_NAME, Component.literal("Compressor regression"));
            var other = new CompoundTag();
            other.putString("OtherModData", "preserved");
            item.set(DataComponents.CUSTOM_DATA, CustomData.of(other));
            item.applyComponents(DataComponentPatch.builder().remove(DataComponents.LORE).build());
            ((BacktankBlock) tank.getBlockState().getBlock()).setPlacedBy(level, tank.getBlockPos(), tank.getBlockState(), null, item);
            ((ShaftWork) tank).differentialcreate$advanceTravel(64);
            CompoundTag saved = tank.saveWithoutMetadata(registry);
            saved.putInt("Timer", 20); // Old saves cannot impose the old RPM-sampled cooldown.
            var restored = new BacktankBlockEntity(tank.getType(), tank.getBlockPos(), tank.getBlockState());
            restored.setLevel(level);
            restored.loadWithComponents(saved, registry);
            h.assertTrue(restored.airLevel == 41 && restored.airLevelTimer == 0, "block NBT retains whole air and discards legacy cooldown");
            near(h, fraction(restored), .5, 1e-10, "block NBT retains paid fractional air");
            near(h, ((ShaftWork) restored).differentialcreate$remainingTravel(),
                (BacktankUtil.maxAir(2) - 41.5) * 128, 1e-8, "capacity enchantment survives NBT");
            var patch = tank.getComponentPatch();
            h.assertTrue(patch.get(DataComponents.LORE) != null && patch.get(DataComponents.LORE).isEmpty(),
                "fraction export preserves explicit component removals");

            var block = (BacktankBlock) tank.getBlockState().getBlock();
            ItemStack clone = block.getCloneItemStack(level, tank.getBlockPos(), tank.getBlockState());
            checkItem(h, clone, .5);
            var drops = Block.getDrops(tank.getBlockState(), level, tank.getBlockPos(), tank);
            h.assertTrue(drops.size() == 1, "native loot produces one backtank");
            checkItem(h, drops.getFirst(), .5);
            ItemStack reloaded = ItemStack.parseOptional(registry, (CompoundTag) drops.getFirst().save(registry));
            checkItem(h, reloaded, .5);
            BlockPos replacedPos = POS.east(3);
            h.setBlock(replacedPos, tank.getBlockState());
            BacktankBlockEntity replaced = h.getBlockEntity(replacedPos);
            block.setPlacedBy(level, replaced.getBlockPos(), replaced.getBlockState(), null, reloaded);
            h.assertTrue(replaced.airLevel == 41, "replacement retains native integer air");
            near(h, fraction(replaced), .5, 1e-10, "replacement retains fraction");
            ((ShaftWork) replaced).differentialcreate$advanceTravel(64);
            h.assertTrue(replaced.airLevel == 42 && fraction(replaced) == 0, "replacement spends the retained credit once");

            var player = h.makeMockPlayer(GameType.SURVIVAL);
            tank.getBlockState().useItemOn(ItemStack.EMPTY, level, player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(tank.getBlockPos()), Direction.UP, tank.getBlockPos(), false));
            checkItem(h, player.getItemBySlot(EquipmentSlot.CHEST), .5);
            h.assertTrue(level.getBlockEntity(tank.getBlockPos()) == null, "native pickup equips and removes the block");
            replaced.setAirLevel(BacktankUtil.maxAir(2));
            ItemStack full = block.getCloneItemStack(level, replaced.getBlockPos(), replaced.getBlockState());
            h.assertTrue(!full.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(BacktankChargeData.KEY)
                && full.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("OtherModData").equals("preserved"),
                "full export clears only this mod's fractional credit");
            h.succeed();
        });
    }

    private static void checkItem(GameTestHelper h, ItemStack item, double fraction) {
        h.assertTrue(!item.isEmpty() && item.getOrDefault(AllDataComponents.BACKTANK_AIR, 0) == 41
            && item.getEnchantmentLevel(h.getLevel().holderOrThrow(AllEnchantments.CAPACITY)) == 2
            && item.getHoverName().getString().equals("Compressor regression")
            && item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("OtherModData").equals("preserved"),
            "native air, enchantment, name and unrelated item metadata survive conversion");
        near(h, BacktankChargeData.fraction(item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)), fraction,
            1e-10, "item carries paid fractional air");
    }
    private static double fraction(BacktankBlockEntity tank) {
        return tank.saveWithoutMetadata(tank.getLevel().registryAccess()).getDouble(BacktankChargeData.KEY);
    }
    private static void setSpeed(BacktankBlockEntity tank, double speed) {
        ElasticKinetics.state(tank).physicalSpeed = speed;
        ElasticKinetics.publishReference(tank.getOrCreateNetwork());
    }
    private static void fixture(GameTestHelper h, boolean netherite, Consumer<BacktankBlockEntity> test) {
        h.setBlock(POS, netherite ? AllBlocks.NETHERITE_BACKTANK.getDefaultState() : AllBlocks.COPPER_BACKTANK.getDefaultState());
        h.runAfterDelay(8, () -> {
            NetworkTopology.flush();
            BacktankBlockEntity tank = h.getBlockEntity(POS);
            ElasticKinetics.refresh(tank.getOrCreateNetwork());
            h.assertTrue(tank.getOrCreateNetwork().members.size() == 1, "fixture is an isolated physical rotor");
            test.accept(tank);
        });
    }
    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String message) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
            message + ": expected " + expected + ", got " + actual);
    }
}
