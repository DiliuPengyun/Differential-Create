package com.diliupengyun.differential_create.tests;

import java.util.Collection;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native fan callbacks at controlled speeds, independently of shaft acceleration or item travel. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class FanProcessingTests {
    private FanProcessingTests() {}

    @GameTestGenerator public static Collection<TestFunction> cases() {
        String name = "fan_native_processing";
        if (!name.matches(System.getProperty("differentialcreate.motion.select", ".*"))) return List.of();
        return List.of(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 100, 0, true, h -> {
            KineticPhysicsTests.forceImpactChunks(h);
            run(h);
        }));
    }

    static void run(GameTestHelper h) {
        BlockPos firstPos = new BlockPos(5, 4, 5), workPos = firstPos.east(2);
        BlockPos secondPos = workPos.south(2), depotPos = workPos.below();
        h.setBlock(firstPos, AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(secondPos, AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH));
        h.setBlock(firstPos.east(), Blocks.WATER);
        h.setBlock(secondPos.north(), Blocks.WATER);
        h.setBlock(depotPos, AllBlocks.DEPOT.getDefaultState());
        h.runAfterDelay(5, () -> {
            EncasedFanBlockEntity first = h.getBlockEntity(firstPos), second = h.getBlockEntity(secondPos);
            DepotBlockEntity depot = h.getBlockEntity(depotPos);
            NetworkTopology.mark(first); NetworkTopology.mark(second); NetworkTopology.flush();
            int base = AllConfigs.server().kinetics.fanProcessingTime.get();
            int[] speeds = {1, 16, 64, 128, 256, 512};
            float lowRange = 0;
            for (int rpm : speeds) {
                drive(first, rpm);
                if (rpm == 1) lowRange = first.airCurrent.maxDistance;
                if (rpm == 512) h.assertTrue(first.airCurrent.maxDistance > lowRange, "speed still extends airflow range");
                for (int count : new int[]{1, 16, 17, 64}) {
                    int ticks = transported(h, depot, count, rpm, first);
                    int expected = base * ((count - 1) / 16 + 1) + 3;
                    h.assertTrue(ticks == expected, "native depot duration at " + rpm + " RPM, count=" + count + ": " + ticks + " != " + expected);
                }
            }
            drive(first, 64); drive(second, 128);
            int shared = transported(h, depot, 1, 64, first, second);
            h.assertTrue(shared == (base + 4) / 2, "two actual air currents retain native processing stacking: " + shared);
            h.assertTrue(MachineActivity.loadTorque(first, 128) == 4 * MachineActivity.loadTorque(first, 64),
                "restoring processing does not remove quadratic fan drag");

            drive(second, 0);
            setInput(depot, 1);
            h.setBlock(firstPos.east(), Blocks.AIR);
            drive(first, 128);
            for (int tick = 0; tick < base + 10; tick++) first.tick();
            h.assertTrue(depot.getHeldItem().is(AllItems.WHEAT_FLOUR.get()), "washing still requires water");
            h.setBlock(firstPos.east(), Blocks.WATER);
            depot.setHeldItem(ItemStack.EMPTY);
            Vec3 location = Vec3.atCenterOf(h.absolutePos(workPos));
            for (int rpm : speeds) {
                drive(first, rpm);
                ItemEntity item = new ItemEntity(h.getLevel(), location.x, location.y, location.z, AllItems.WHEAT_FLOUR.asStack());
                h.getLevel().addFreshEntity(item);
                first.airCurrent.findEntities();
                int ticks = 0;
                while (item.getItem().is(AllItems.WHEAT_FLOUR.get()) && ticks < base + 10) {
                    first.tick();
                    if (ticks == 3) {
                        int remaining = item.getPersistentData().getCompound("CreateData").getCompound("Processing").getInt("Time");
                        item.getPersistentData().getCompound("CreateData").getCompound("Processing").putDouble("DCRemaining", remaining - .5);
                        CompoundTag saved = item.saveWithoutId(new CompoundTag());
                        item.load(saved);
                        drive(first, 0);
                        for (int paused = 0; paused < 20; paused++) first.tick();
                        h.assertTrue(item.getPersistentData().getCompound("CreateData").getCompound("Processing").getInt("Time") == remaining,
                            "stopped fan and item reload preserve native remaining time");
                        drive(first, rpm);
                    }
                    ticks++;
                }
                h.assertTrue(item.getItem().is(AllItems.DOUGH.get()) && ticks == base + 1,
                    "native dropped-item duration at " + rpm + " RPM: " + ticks);
                item.discard();
            }
            DifferentialCreate.LOGGER.info("FAN_NATIVE_PASS: 24 depot speed/batch cases, 6 dropped-item speeds, stop/reload/transfer, changing RPM, missing medium, two-fan stacking, range and drag");
            h.succeed();
        });
    }

    private static int transported(GameTestHelper h, DepotBlockEntity depot, int count, int rpm, EncasedFanBlockEntity... fans) {
        setInput(depot, count);
        int ticks = 0;
        while (depot.getHeldItem().is(AllItems.WHEAT_FLOUR.get()) && ticks < 10000) {
            for (var fan : fans) fan.tick();
            if (ticks == 3 && fans.length == 1) {
                var stack = current(depot);
                int remaining = stack.processingTime;
                h.assertTrue(stack.processedBy == AllFanProcessingTypes.SPLASHING, "real airflow selects washing");
                var copied = stack.copy();
                h.assertTrue(copied.processingTime == remaining && copied.processedBy == stack.processedBy, "native transfer keeps progress and type");
                var tag = copied.serializeNBT(h.getLevel().registryAccess());
                // Previous versions already stored the rounded native timer alongside this obsolete field.
                tag.putDouble("DCFanRemaining", remaining - .5);
                var restored = TransportedItemStack.read(tag, h.getLevel().registryAccess());
                h.assertTrue(restored.processingTime == remaining && restored.processedBy == stack.processedBy, "old saves resume through native fields");
                depot.getBehaviour(DepotBehaviour.TYPE).setCenteredHeldItem(restored);
                drive(fans[0], 0);
                for (int paused = 0; paused < 20; paused++) fans[0].tick();
                h.assertTrue(current(depot).processingTime == remaining, "stopped fan does not process a stationary item");
                drive(fans[0], rpm);
            }
            if (rpm == 512 && fans.length == 1 && ticks % 10 == 5)
                drive(fans[0], ticks % 20 == 5 ? 1 : 512);
            ticks++;
        }
        h.assertTrue(depot.getHeldItem().is(AllItems.DOUGH.get()) && depot.getHeldItem().getCount() == count,
            "native washing produces the correct whole batch exactly once");
        return ticks;
    }

    private static void drive(EncasedFanBlockEntity fan, double rpm) {
        var network = (INetworkDynamics) fan.getOrCreateNetwork();
        double ratio = network.differentialcreate$getRatios().get(fan);
        float before = fan.getSpeed();
        network.differentialcreate$setSpeed(rpm / ratio);
        if (rpm != 0 && fan.getAirFlowDirection() != fan.getBlockState().getValue(BlockStateProperties.FACING))
            network.differentialcreate$setSpeed(-rpm / ratio);
        fan.onSpeedChanged(before);
        fan.airCurrent.rebuild();
    }

    private static void setInput(DepotBlockEntity depot, int count) {
        depot.getBehaviour(DepotBehaviour.TYPE).setCenteredHeldItem(new TransportedItemStack(AllItems.WHEAT_FLOUR.asStack(count)));
    }

    private static TransportedItemStack current(DepotBlockEntity depot) {
        TransportedItemStack[] result = {null};
        depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE).handleProcessingOnAllItems(stack -> {
            result[0] = stack;
            return TransportedResult.doNothing();
        });
        if (result[0] == null) throw new AssertionError("Missing depot item");
        return result[0];
    }
}
