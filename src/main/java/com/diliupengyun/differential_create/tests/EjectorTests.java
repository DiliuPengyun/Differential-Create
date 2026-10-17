package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity.State;
import com.simibubi.create.content.logistics.depot.EntityLauncher;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;

final class EjectorTests {
    private static final BlockPos POS = new BlockPos(8, 3, 8);

    static void motion(GameTestHelper h, double speed) {
        fixture(h, be -> {
            be.setTarget(32, 0);
            double inertia = InertiaRegistry.getInertia(be), torque = be.calculateStressApplied();
            double duration = Math.min(RotationMath.TICK_MINUTES, inertia * Math.abs(speed) / torque);
            double turns = Math.abs(speed) * duration - torque / (2 * inertia) * duration * duration;
            setSpeed(be, speed);
            be.tick();
            near(h, charge(be).travel(), 0, 0, "native tick cannot add sampled-RPM work");
            MachiningClock.advance(h.getLevel());
            near(h, charge(be).travel(), turns * 1200, 1e-6, "winding equals actual paid shaft travel, including a natural stop");
            near(h, ElasticKinetics.state(be).physicalSpeed,
                Math.copySign(Math.max(0, Math.abs(speed) - torque / inertia * RotationMath.TICK_MINUTES), speed),
                1e-6, "winding resistance is charged once");
            h.assertTrue(MachineActivity.impact(be) == 2, "unfinished winding retains startup resistance");
            h.succeed();
        });
    }
    static void full(GameTestHelper h, int direction) {
        fixture(h, be -> {
            double required = EjectorCharge.required(8, 0);
            ((ShaftWork) be).differentialcreate$advanceTravel(required - 32);
            double inertia = InertiaRegistry.getInertia(be), torque = be.calculateStressApplied();
            setSpeed(be, direction * 128);
            MachiningClock.advance(h.getLevel());
            double expected = direction * Math.sqrt(128 * 128 - 2 * torque * (32d / 1200) / inertia);
            near(h, ElasticKinetics.state(be).physicalSpeed, expected, 1e-6, "full charge releases resistance at its exact event time");
            h.assertTrue(be.getState() == State.CHARGED && MachineActivity.impact(be) == 0, "charged waiting has zero work load");
            near(h, charge(be).travel(), required, 1e-9, "full charge holds exactly one cycle");
            MachiningClock.advance(h.getLevel());
            near(h, ElasticKinetics.state(be).physicalSpeed, expected, 1e-6, "charged rotor coasts without extra winding work");
            h.succeed();
        });
    }
    static void launch(GameTestHelper h, int count) {
        fixture(h, be -> {
            fill(be);
            hold(be, new ItemStack(Items.IRON_INGOT, count));
            be.activateDeferred();
            h.assertTrue(be.getState() == State.LAUNCHING && dynamics(be).differentialcreate$launchedItems().size() == 1,
                "charged ejector launches one native item stack without shaft power");
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().getFirst().getValue().getCount() == count,
                "native single-item/batch quantities are retained");
            near(h, charge(be).travel(), 0, 0, "launch consumes the entire winding credit");
            h.assertTrue(MachineActivity.impact(be) == 0, "spring release does not charge the shaft a second time");
            be.activateDeferred();
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().size() == 1, "repeated activation cannot duplicate the launch");
            setSpeed(be, 128);
            MachiningClock.advance(h.getLevel());
            near(h, ElasticKinetics.state(be).physicalSpeed, 128, 1e-9, "release animation retains inertia without shaft load");
            setSpeed(be, 0);
            be.tick(); be.tick();
            h.assertTrue(be.getState() == State.RETRACTING && MachineActivity.impact(be) == 2, "next cycle needs new winding work");
            near(h, charge(be).travel(), 0, 0, "stopped next cycle earns no work");
            h.succeed();
        });
    }
    static void retarget(GameTestHelper h) {
        fixture(h, be -> {
            ((ShaftWork) be).differentialcreate$advanceTravel(300);
            be.setTarget(16, 0);
            near(h, charge(be).travel(), 300, 0, "farther target preserves absolute paid work");
            near(h, ((ShaftWork) be).differentialcreate$remainingTravel(), 4096 - 300, 0, "farther target needs only its unpaid difference");
            be.setTarget(1, 0);
            near(h, charge(be).travel(), 256, 0, "cheaper target discards excess work");
            h.assertTrue(be.getState() == State.CHARGED && MachineActivity.impact(be) == 0, "cheaper fully paid target becomes ready immediately");
            be.setTarget(16, 0);
            near(h, charge(be).travel(), 256, 0, "changing back cannot recover discarded excess");
            h.assertTrue(be.getState() == State.RETRACTING, "farther target must be recharged before launching");
            hold(be, new ItemStack(Items.IRON_INGOT));
            be.activateDeferred();
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().isEmpty(), "retargeting cannot authorize an underpaid shot");
            fill(be);
            be.setTarget(3, 4);
            near(h, charge(be).travel(), 1280, 0, "height contributes to native target-distance budget");
            be.setTarget(3, -4);
            near(h, charge(be).travel(), 1280, 0, "mirrored height does not generate work");
            h.succeed();
        });
    }
    static void persistence(GameTestHelper h) {
        fixture(h, be -> {
            ((ShaftWork) be).differentialcreate$advanceTravel(123.123456789);
            var saved = be.saveWithoutMetadata(h.getLevel().registryAccess());
            be.setTarget(1, 0);
            be.loadWithComponents(saved, h.getLevel().registryAccess());
            near(h, charge(be).travel(), 123.123456789, 1e-12, "block NBT retains double-precision paid work");
            near(h, ((ShaftWork) be).differentialcreate$remainingTravel(), 2048 - 123.123456789, 1e-10,
                "reload does not rescale or duplicate work");
            NetworkTopology.mark(be);
            NetworkTopology.flush();
            setSpeed(be, 0);
            be.tick();
            near(h, charge(be).travel(), 123.123456789, 1e-12, "stopped reload preserves credit");
            saved.putDouble(EjectorCharge.KEY, Double.NaN);
            saved.putString("State", "CHARGED");
            be.loadWithComponents(saved, h.getLevel().registryAccess());
            h.assertTrue(be.getState() == State.RETRACTING && charge(be).travel() == 0, "invalid credit cannot restore a free charged state");
            h.succeed();
        });
    }
    static void redstone(GameTestHelper h) {
        fixture(h, be -> {
            fill(be);
            hold(be, new ItemStack(Items.IRON_INGOT, 8));
            h.setBlock(POS.west(), Blocks.REDSTONE_BLOCK);
            be.updateSignal();
            be.tick(); be.tick();
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().isEmpty() && be.getState() == State.CHARGED,
                "native redstone signal inhibits automatic launch without wasting charge");
            h.setBlock(POS.west(), Blocks.AIR);
            be.updateSignal();
            be.tick(); be.tick();
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().size() == 1 && charge(be).travel() == 0,
                "unlocking launches once and consumes one charge");
            h.succeed();
        });
    }
    static void inventory(GameTestHelper h) {
        fixture(h, be -> {
            BlockPos target = POS.south(8);
            h.setBlock(target, AllBlocks.DEPOT.getDefaultState());
            DepotBehaviour output = ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity) h.getBlockEntity(target)).getBehaviour(DepotBehaviour.TYPE);
            output.setHeldItem(new TransportedItemStack(new ItemStack(Items.COBBLESTONE, 64)));
            fill(be);
            hold(be, new ItemStack(Items.IRON_INGOT, 8));
            be.tick(); be.tick();
            h.assertTrue(be.getState() == State.CHARGED && dynamics(be).differentialcreate$launchedItems().isEmpty(),
                "blocked native target inventory postpones the shot");
            near(h, charge(be).travel(), 2048, 0, "blocked target does not consume charge");
            output.removeHeldItem();
            openArc(be, 8, 0);
            be.tick(); be.tick();
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().size() == 1, "available target resumes automatic launch");
            var launched = dynamics(be).differentialcreate$launchedItems().getFirst();
            var flight = dynamics(be).differentialcreate$flight(launched);
            for (int i = 0; i < 100; i++) { be.tick(); output.tick(); }
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().isEmpty() && output.getPresentStackSize() == 8,
                "native target insertion receives the entire flight exactly once: remaining="
                    + dynamics(be).differentialcreate$launchedItems().size() + ", output=" + output.getPresentStackSize()
                    + ", hit=" + flight.earlyTarget + ", end=" + flight.endTime() + ", position=" + flight.location(be, flight.endTime()));
            h.succeed();
        });
    }
    static void snapshots(GameTestHelper h) {
        fixture(h, be -> {
            hold(be, named(Items.IRON_INGOT, 32));
            var depot = be.getBehaviour(DepotBehaviour.TYPE);
            var depotTag = new CompoundTag();
            depot.write(depotTag, h.getLevel().registryAccess(), false);
            var outputs = new ItemStackHandler(8);
            outputs.setStackInSlot(0, named(Items.GOLD_INGOT, 8));
            depotTag.put("OutputBuffer", outputs.serializeNBT(h.getLevel().registryAccess()));
            depot.read(depotTag, h.getLevel().registryAccess(), false);
            fill(be);
            be.activateDeferred();
            var items = dynamics(be).differentialcreate$launchedItems();
            h.assertTrue(items.size() == 2, "native held and processing-output items launch together");
            var flight = dynamics(be).differentialcreate$flight(items.getFirst());
            h.assertTrue(flight == dynamics(be).differentialcreate$flight(items.getLast()), "one batch shares a collision cache");
            Vec3 expected = flight.location(be, 10);
            be.tick(); be.tick();
            be.setTarget(12, 4);
            h.getLevel().setBlock(be.getBlockPos(), be.getBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), Block.UPDATE_ALL);
            fill(be);
            hold(be, named(Items.DIAMOND, 16));
            be.activateDeferred();
            h.assertTrue(items.size() == 3, "different launch batches can be in flight together");
            near(h, flight.location(be, 10).distanceTo(expected), 0, 1e-12, "changing target and facing cannot move an old trajectory");
            var next = dynamics(be).differentialcreate$flight(items.getLast());
            h.assertTrue(next != flight && next.horizontal() == 12 && next.vertical() == 4 && next.facing() == Direction.EAST,
                "next batch captures the new target and direction");
            CompoundTag saved = be.saveWithoutMetadata(h.getLevel().registryAccess());
            be.setTarget(1, 0);
            be.loadWithComponents(saved, h.getLevel().registryAccess());
            items = dynamics(be).differentialcreate$launchedItems();
            h.assertTrue(items.size() == 3 && dynamics(be).differentialcreate$flight(items.getFirst())
                == dynamics(be).differentialcreate$flight(items.get(1)), "reload restores batch membership");
            near(h, dynamics(be).differentialcreate$flight(items.getFirst()).location(be, 10).distanceTo(expected), 0, 1e-12,
                "reload restores the old trajectory independently of the configured target");
            h.assertTrue(charge(be).travel() == 0 && be.getState() == State.LAUNCHING, "in-flight save cannot restore consumed charge");
            be.dropFlyingItems();
            var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(be.getBlockPos()).inflate(24),
                entity -> entity.getItem().getHoverName().getString().equals("Ejector snapshot"));
            h.assertTrue(drops.stream().mapToInt(entity -> entity.getItem().getCount()).sum() == 56
                && dynamics(be).differentialcreate$launchedItems().isEmpty(), "breaking drops every in-flight item once using its own trajectory");
            be.dropFlyingItems();
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(be.getBlockPos()).inflate(24),
                entity -> entity.getItem().getHoverName().getString().equals("Ejector snapshot")).size() == drops.size(), "repeat cleanup cannot duplicate flights");
            drops.forEach(ItemEntity::discard);
            h.succeed();
        });
    }
    static void collision(GameTestHelper h) {
        fixture(h, be -> {
            be.setTarget(16, 0);
            openArc(be, 16, 0);
            fill(be);
            hold(be, new ItemStack(Items.IRON_INGOT, 3));
            be.activateDeferred();
            var item = dynamics(be).differentialcreate$launchedItems().getFirst();
            var flight = dynamics(be).differentialcreate$flight(item);
            BlockPos obstacle = BlockPos.containing(flight.location(be, 7));
            h.getLevel().setBlock(obstacle, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            h.assertTrue(be.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("DCEjectorTracked") == 0, "fixture tracks its first flight");
            for (int i = 0; i < 16; i++) be.tick();
            h.assertTrue(flight.earlyTarget != null && flight.earlyTarget.getSecond().equals(obstacle),
                "tracked flight preserves native obstacle interception: obstacle=" + obstacle + ", hit=" + flight.earlyTarget + ", age=" + item.getFirst());
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().isEmpty(), "intercepted flight exits the virtual item list");
            h.getLevel().removeBlock(obstacle, false);
            h.succeed();
        });
    }
    static void entity(GameTestHelper h) {
        fixture(h, be -> {
            var pig = h.spawn(EntityType.PIG, new Vec3(POS.getX() + .5, POS.getY() + .95, POS.getZ() + .5));
            fill(be);
            be.activateDeferred();
            var launcher = new EntityLauncher(8, 0);
            var reference = h.spawn(EntityType.PIG, new Vec3(POS.getX() + 3.5, POS.getY() + .95, POS.getZ() + .5));
            launcher.applyMotion(reference, Direction.SOUTH);
            near(h, pig.getDeltaMovement().distanceTo(reference.getDeltaMovement()), 0, 1e-12, "native living-entity launch motion is retained");
            h.assertTrue(charge(be).travel() == 0 && MachineActivity.impact(be) == 0, "entity shot consumes stored work without extra shaft load");
            pig.discard(); reference.discard();
            h.succeed();
        });
    }
    static void unpaidTrigger(GameTestHelper h) {
        fixture(h, be -> {
            var pig = h.spawn(EntityType.PIG, new Vec3(POS.getX() + .5, POS.getY() + .8, POS.getZ() + .5));
            Vec3 before = pig.position();
            for (int i = 0; i < 8; i++) { be.activate(); be.activateDeferred(); }
            near(h, pig.position().distanceTo(before), 0, 0, "unpaid repeated triggers cannot nudge an entity upwards");
            h.assertTrue(be.getState() == State.RETRACTING && charge(be).travel() == 0
                && dynamics(be).differentialcreate$launchedItems().isEmpty(), "unpaid triggers cannot launch or generate credit");
            pig.discard();
            h.succeed();
        });
    }
    static void cancelPending(GameTestHelper h) {
        fixture(h, be -> {
            fill(be);
            hold(be, new ItemStack(Items.IRON_INGOT, 8));
            be.tick(); // Native automatic activation queues the shot for the next tick.
            be.setTarget(16, 0);
            be.tick();
            h.assertTrue(dynamics(be).differentialcreate$launchedItems().isEmpty()
                && be.getBehaviour(DepotBehaviour.TYPE).getHeldItemStack().getCount() == 8,
                "retargeting cancels an underpaid queued shot without losing its items");
            near(h, charge(be).travel(), 2048, 0, "cancelled shot retains only already paid work");
            h.succeed();
        });
    }

    private static ItemStack named(net.minecraft.world.item.Item item, int count) {
        var stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Ejector snapshot"));
        return stack;
    }
    private static void hold(EjectorBlockEntity be, ItemStack stack) {
        var held = new TransportedItemStack(stack);
        held.beltPosition = held.prevBeltPosition = .5f;
        be.getBehaviour(DepotBehaviour.TYPE).setHeldItem(held);
    }
    /** The shared rotor template has a low roof; flight tests need actual open sky. */
    private static void openArc(EjectorBlockEntity be, int horizontal, int vertical) {
        var flight = new EjectorFlight(horizontal, vertical, Direction.SOUTH);
        var cleared = new java.util.HashSet<BlockPos>();
        for (float time = 0; time <= flight.endTime() + 1; time += .25f) {
            var point = BlockPos.containing(flight.location(be, time));
            for (var pos : BlockPos.betweenClosed(point.offset(-1, -1, -1), point.offset(1, 1, 1)))
                if (pos.getY() >= be.getBlockPos().getY() + 2 && cleared.add(pos.immutable()))
                    be.getLevel().removeBlock(pos, false);
        }
    }
    private static IEjectorDynamics dynamics(EjectorBlockEntity be) { return (IEjectorDynamics) be; }
    private static EjectorCharge charge(EjectorBlockEntity be) { return dynamics(be).differentialcreate$charge(); }
    /** Fixtures explicitly prepay work; motion/full tests independently check its physical cost. */
    private static void fill(EjectorBlockEntity be) { ((ShaftWork) be).differentialcreate$advanceTravel(((ShaftWork) be).differentialcreate$remainingTravel()); }
    private static void setSpeed(EjectorBlockEntity be, double speed) {
        ElasticKinetics.state(be).physicalSpeed = speed;
        ElasticKinetics.publishReference(be.getOrCreateNetwork());
    }
    private static void fixture(GameTestHelper h, Consumer<EjectorBlockEntity> test) {
        h.setBlock(POS, AllBlocks.WEIGHTED_EJECTOR.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        h.runAfterDelay(8, () -> {
            NetworkTopology.flush();
            EjectorBlockEntity be = h.getBlockEntity(POS);
            ElasticKinetics.refresh(be.getOrCreateNetwork());
            h.assertTrue(be.getOrCreateNetwork().members.size() == 1, "fixture is an isolated physical rotor");
            be.setTarget(8, 0);
            test.accept(be);
        });
    }
    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String message) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, message + ": expected " + expected + ", got " + actual);
    }
}
