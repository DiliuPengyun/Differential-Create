package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockCheckingBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;
import com.simibubi.create.content.redstone.diodes.PulseTimerBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.itemHatch.ItemHatchBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native Create 6 logistics, using disposable worlds and the production mixins. */
@GameTestHolder("differentialcreate_logistics")
@PrefixGameTestTemplate(false)
public final class Create6LogisticsTests {
    private static final BlockPos A = new BlockPos(3, 3, 4), B = new BlockPos(11, 3, 4), FROG = new BlockPos(3, 3, 7);

    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (float speed : new float[]{.01f, -.01f, 16, -16, 256, -256, 1024, -1024, 2048, -2048, 8192, -8192})
            tests.add(test("chain_motion_" + key(speed), h -> movement(h, speed)));
        for (float speed : new float[]{16, -16, 256, -256, 1024, -1024, 8192, -8192})
            tests.add(test("chain_transfer_" + key(speed), h -> transfer(h, speed)));
        for (float speed : new float[]{256, -256, 2048, -2048, (float)(540 * Math.PI), (float)(-540 * Math.PI)})
            tests.add(test("chain_frog_crossing_" + key(speed), h -> frog(h, speed)));
        tests.add(test("chain_reverse", Create6LogisticsTests::reversal));
        tests.add(test("chain_reload", Create6LogisticsTests::reload));
        tests.add(test("chain_stopped_reverse_reload", Create6LogisticsTests::stoppedReload));
        tests.add(test("packager_roundtrip", Create6LogisticsTests::packager));
        tests.add(test("repackager_passthrough", Create6LogisticsTests::repackager));
        tests.add(test("repackager_fragments", Create6LogisticsTests::fragments));
        tests.add(test("stock_ticker_request", h -> stock(h, false, false)));
        tests.add(test("redstone_request", h -> stock(h, true, false)));
        tests.add(test("redstone_insufficient", h -> stock(h, true, true)));
        tests.add(test("pulse_timer", Create6LogisticsTests::timer));
        tests.add(test("chain_motor_detach", Create6LogisticsTests::motorDetach));
        tests.add(test("factory_gauge_stock", Create6LogisticsTests::factoryGauge));
        tests.add(test("item_hatch_deposit", Create6LogisticsTests::itemHatch));
        tests.add(test("frog_backpressure", Create6LogisticsTests::frogBlocked));
        for (int sign : new int[]{-1, 1}) {
            tests.add(test("chain_carry_" + sign, h -> carry(h, sign)));
            tests.add(test("chain_first_port_" + sign, h -> firstPort(h, sign)));
            tests.add(test("chain_closed_cycle_" + sign, h -> cycle(h, sign)));
        }
        return tests;
    }

    private static String key(float value) { return Float.toString(value).replace('.', '_').replace('-', 'n'); }
    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(name, name, "differentialcreate_logistics:kineticphysicstests.physics_test", 120, 0, true, h -> {
            KineticPhysicsTests.forceImpactChunks(h);
            body.accept(h);
        });
    }
    private static ItemStack parcel(int count) {
        ItemStack content = new ItemStack(Items.IRON_INGOT, count);
        content.set(DataComponents.CUSTOM_NAME, Component.literal("Audit payload"));
        ItemStack box = PackageItem.containing(List.of(content));
        PackageItem.addAddress(box, "Audit");
        return box;
    }
    private static void fixture(GameTestHelper h, boolean pair, Consumer<ChainConveyorBlockEntity> body) {
        h.setBlock(A, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        if (pair) h.setBlock(B, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        h.runAfterDelay(8, () -> {
            ChainConveyorBlockEntity a = h.getBlockEntity(A);
            if (pair) {
                ChainConveyorBlockEntity b = h.getBlockEntity(B);
                a.prepareStats(); b.prepareStats();
                a.addConnectionTo(b.getBlockPos()); b.addConnectionTo(a.getBlockPos());
                NetworkTopology.mark(a); NetworkTopology.mark(b);
            }
            NetworkTopology.flush();
            ElasticKinetics.refresh(a.getOrCreateNetwork());
            if (pair) {
                ChainConveyorBlockEntity b = h.getBlockEntity(B);
                h.assertTrue(a.network.equals(b.network), "remote chain topology joins the physical network");
                near(h, NetworkTopology.connection(a, b), 1, 0, "chain transmission ratio");
            }
            try { body.accept(a); }
            finally {
                speed(a, 0);
                if (pair) speed((KineticBlockEntity)h.getBlockEntity(B), 0);
                a.cancelDrops = true;
                h.setBlock(A, Blocks.AIR);
                if (pair) { ((ChainConveyorBlockEntity)h.getBlockEntity(B)).cancelDrops = true; h.setBlock(B, Blocks.AIR); }
            }
        });
    }
    private static void speed(KineticBlockEntity be, double value) {
        ElasticKinetics.state(be).physicalSpeed = value;
        ElasticKinetics.publishReference(be.getOrCreateNetwork());
    }
    private static double step(float rpm) { return (rpm / 360f / ((float)Math.PI * 1.5f)) * 360f; }
    private static void movement(GameTestHelper h, float rpm) {
        fixture(h, false, a -> {
            speed(a, rpm);
            var box = new ChainConveyorPackage(123, parcel(17));
            a.addLoopingPackage(box);
            a.tick();
            near(h, a.getSpeed(), rpm, .0001, "native conveyor sees actual physical RPM");
            near(h, box.chainPosition, a.wrapAngle(123 + (float)step(rpm)), .0001, "loop distance follows physical RPM");
            speed(a, 0); float stopped = box.chainPosition; a.tick();
            near(h, box.chainPosition, stopped, 0, "stationary chain does not move parcels");
            near(h, MachineActivity.impact(a), 1, 0, "native constant chain resistance remains");
            near(h, InertiaRegistry.getInertia(a), 0, 0, "chain inertia is the declared zero-inertia boundary");
            h.succeed();
        });
    }
    private static int count(ChainConveyorBlockEntity a) {
        return a.getLoopingPackages().size() + a.getTravellingPackages().values().stream().mapToInt(List::size).sum();
    }
    private static void transfer(GameTestHelper h, float rpm) {
        fixture(h, true, a -> {
            ChainConveyorBlockEntity b = h.getBlockEntity(B);
            speed(a, rpm); speed(b, rpm); a.prepareStats(); b.prepareStats();
            BlockPos offset = b.getBlockPos().subtract(a.getBlockPos());
            float angle = a.connectionStats.get(offset).tangentAngle();
            var box = new ChainConveyorPackage(a.wrapAngle(angle - Math.signum(rpm)), parcel(17));
            ItemStack expected = box.item.copy();
            a.addLoopingPackage(box);
            a.routingTable.receivePortInfo("Audit", offset);
            a.tick();
            h.assertTrue(a.getLoopingPackages().isEmpty(), "parcel crosses branch tangent and leaves loop at " + rpm);
            h.assertTrue(count(a) + count(b) == 1, "departure conserves parcel count");
            int limit = (int)Math.ceil(a.connectionStats.get(offset).chainLength() / (Math.abs(rpm) / 360f)) + 2;
            for (int i = 0; i < limit && b.getLoopingPackages().isEmpty(); i++) {
                // A controlled next frame; ordinary BE tick ordering is checked separately.
                ((IChainPackageMotion) box).differentialcreate$motion().tick = Long.MIN_VALUE;
                a.tick();
                h.assertTrue(count(a) + count(b) == 1, "linear travel conserves parcel count");
            }
            h.assertTrue(b.getLoopingPackages().size() == 1, "parcel reaches other conveyor at " + rpm);
            h.assertTrue(ItemStack.matches(box.item, expected), "transfer retains parcel payload and address");
            h.succeed();
        });
    }
    private static void frog(GameTestHelper h, float rpm) {
        h.setBlock(FROG, AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        fixture(h, false, a -> {
            speed(a, rpm); a.prepareStats();
            float start = Math.abs(rpm) <= 256 ? 180 - Math.signum(rpm) : 0;
            var box = new ChainConveyorPackage(start, parcel(17));
            a.addLoopingPackage(box);
            a.loopPorts.put(h.absolutePos(FROG).subtract(a.getBlockPos()),
                new ChainConveyorBlockEntity.ConnectedPort(180, null, "Audit"));
            FrogportBlockEntity frog = h.getBlockEntity(FROG);
            a.tick();
            if (Math.abs(rpm) == (float)(540 * Math.PI)) for (int tick = 0; tick < 39; tick++) a.tick();
            DifferentialCreate.LOGGER.info("DC_CREATE6_FROG rpm={} start={} degrees={} end={} remaining={} frogAnimating={}",
                rpm, start, step(rpm), box.chainPosition, count(a), frog.isAnimationInProgress());
            h.assertTrue(count(a) == 0 && frog.isAnimationInProgress(), "crossed frogport must receive parcel at " + rpm
                + "; actual travel=" + step(rpm) + ", retained=" + count(a));
            h.succeed();
        });
    }
    private static void reversal(GameTestHelper h) {
        fixture(h, true, a -> {
            ChainConveyorBlockEntity b = h.getBlockEntity(B);
            speed(a, 128); speed(b, 128); a.prepareStats(); b.prepareStats();
            BlockPos offset = b.getBlockPos().subtract(a.getBlockPos());
            var box = new ChainConveyorPackage(2, parcel(17));
            a.addTravellingPackage(box, offset); a.updateBoxWorldPositions();
            var before = box.worldPosition;
            speed(a, -128); speed(b, -128);
            a.tick(); b.tick();
            h.assertTrue(count(a) + count(b) == 1, "reversal transfers ownership once");
            DifferentialCreate.LOGGER.info("DC_CREATE6_REVERSE before={} after={} distance={} ownersA={} ownersB={}", before, box.worldPosition, box.worldPosition.distanceTo(before), count(a), count(b));
            h.assertTrue(Double.isFinite(box.worldPosition.distanceTo(before)), "reversal retains finite world position");
            h.succeed();
        });
    }
    private static void reload(GameTestHelper h) {
        fixture(h, true, a -> {
            ChainConveyorBlockEntity b = h.getBlockEntity(B);
            speed(a, -128); speed(b, -128); a.prepareStats(); b.prepareStats();
            ItemStack expected = parcel(17);
            a.addTravellingPackage(new ChainConveyorPackage(2.25f, expected.copy()), b.getBlockPos().subtract(a.getBlockPos()));
            a.addLoopingPackage(new ChainConveyorPackage(74.5f, parcel(3)));
            CompoundTag tag = a.saveWithoutMetadata(h.getLevel().registryAccess());
            var copy = new ChainConveyorBlockEntity(a.getType(), a.getBlockPos(), a.getBlockState());
            copy.setLevel(h.getLevel()); copy.loadWithComponents(tag, h.getLevel().registryAccess());
            h.assertTrue(count(copy) == 2 && copy.connections.equals(a.connections), "NBT retains both parcel groups and chain geometry");
            near(h, copy.getLoopingPackages().getFirst().chainPosition, 74.5, 0, "NBT retains loop progress");
            var restored = copy.getTravellingPackages().values().iterator().next().getFirst();
            near(h, restored.chainPosition, 2.25, 0, "NBT retains linear progress");
            h.assertTrue(ItemStack.matches(restored.item, expected), "NBT retains address and item components");
            h.succeed();
        });
    }
    private static void stoppedReload(GameTestHelper h) {
        fixture(h, true, a -> {
            ChainConveyorBlockEntity b = h.getBlockEntity(B);
            speed(a, -128); speed(b, -128); a.prepareStats(); b.prepareStats();
            var connection = b.getBlockPos().subtract(a.getBlockPos());
            var box = new ChainConveyorPackage(2.25f, parcel(17)); a.addTravellingPackage(box, connection);
            a.updateBoxWorldPositions(); var position = box.worldPosition;
            speed(a, 0); speed(b, 0);
            var saved = a.saveWithoutMetadata(h.getLevel().registryAccess());
            var copy = new ChainConveyorBlockEntity(a.getType(), a.getBlockPos(), a.getBlockState());
            copy.setLevel(h.getLevel()); copy.loadWithComponents(saved, h.getLevel().registryAccess());
            var restored = copy.getTravellingPackages().get(connection).getFirst();
            h.assertTrue(copy.reversed && ((IChainPackageMotion)restored).differentialcreate$motion().reversed,
                "stopped reverse motion preserves both conveyor and parcel direction");
            near(h, restored.worldPosition.distanceTo(position), 0, 1e-8, "stopped NBT reload retains the same chain side and world position");
            h.succeed();
        });
    }
    private static void packager(GameTestHelper h) {
        h.setBlock(A, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        h.setBlock(A.west(), Blocks.CHEST);
        h.runAfterDelay(8, () -> {
            PackagerBlockEntity p = h.getBlockEntity(A);
            ChestBlockEntity chest = h.getBlockEntity(A.west());
            ItemStack contents = PackageItem.getContents(parcel(17)).getStackInSlot(0).copy();
            chest.setItem(0, contents.copy()); p.targetInventory.findNewCapability(); p.attemptToSend(null);
            h.assertTrue(chest.isEmpty() && !p.heldBox.isEmpty(), "unpowered packager extracts chest into one parcel");
            h.assertTrue(ItemStack.matches(PackageItem.getContents(p.heldBox).getStackInSlot(0), contents), "packing retains components and quantity");
            ItemStack box = p.heldBox.copy();
            for (int i = 0; i < 20; i++) p.tick();
            h.assertTrue(p.animationTicks == 0, "native packager cycle remains 20 ticks without shaft power");
            p.heldBox = ItemStack.EMPTY;
            h.assertTrue(p.unwrapBox(box, true) && chest.isEmpty(), "unpacking simulation has no side effects");
            h.assertTrue(p.unwrapBox(box, false), "unpowered unpacking succeeds");
            h.assertTrue(ItemStack.matches(chest.getItem(0), contents), "pack/unpack roundtrip conserves payload");
            h.succeed();
        });
    }
    private static void repackager(GameTestHelper h) {
        h.setBlock(A, AllBlocks.REPACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        h.setBlock(A.west(), Blocks.CHEST);
        h.runAfterDelay(8, () -> {
            RepackagerBlockEntity p = h.getBlockEntity(A);
            ChestBlockEntity chest = h.getBlockEntity(A.west());
            ItemStack box = parcel(17); chest.setItem(0, box.copy());
            p.targetInventory.findNewCapability(); p.attemptToSend(null);
            h.assertTrue(chest.isEmpty() && ItemStack.matches(p.heldBox, box), "unpowered repackager forwards complete package exactly once");
            h.succeed();
        });
    }
    private static void stock(GameTestHelper h, boolean redstone, boolean insufficient) {
        BlockPos remote = new BlockPos(7, 3, 8);
        h.setBlock(A, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        h.setBlock(A.west(), Blocks.CHEST);
        h.setBlock(A.above(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR));
        h.setBlock(remote, redstone ? AllBlocks.REDSTONE_REQUESTER.getDefaultState() : AllBlocks.STOCK_TICKER.getDefaultState());
        h.runAfterDelay(8, () -> {
            PackagerBlockEntity p = h.getBlockEntity(A);
            PackagerLinkBlockEntity link = h.getBlockEntity(A.above());
            StockCheckingBlockEntity requester = h.getBlockEntity(remote);
            ChestBlockEntity chest = h.getBlockEntity(A.west());
            ItemStack content = new ItemStack(Items.IRON_INGOT, 17);
            chest.setItem(0, content.copy()); p.targetInventory.findNewCapability();
            UUID frequency = UUID.randomUUID();
            LogisticallyLinkedBehaviour.remove(link.behaviour);
            link.behaviour.freqId = requester.behaviour.freqId = frequency;
            LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
            h.assertTrue(link.getPackager() == p, "stock link resolves its physical packager");
            h.assertTrue(requester.getAccurateSummary().getCountOf(content) == 17, "remote stock summary sees chest contents");
            var order = PackageOrderWithCrafts.simple(List.of(new BigItemStack(content.copyWithCount(1), insufficient ? 18 : 7)));
            if (redstone) {
                var rr = (RedstoneRequesterBlockEntity)requester;
                rr.encodedRequest = order; rr.encodedTargetAdress = "Audit"; rr.triggerRequest();
                h.assertTrue(rr.lastRequestSucceeded != insufficient, "request status respects required inventory");
            } else h.assertTrue(requester.broadcastPackageRequest(LogisticallyLinkedBehaviour.RequestType.PLAYER, order, null, "Audit"), "stock ticker broadcasts request");
            if (insufficient) h.assertTrue(chest.getItem(0).getCount() == 17 && p.heldBox.isEmpty(), "insufficient request leaves inventory untouched");
            else {
                h.assertTrue(chest.getItem(0).getCount() == 10, "request extracts exact count once");
                h.assertTrue(PackageItem.matchAddress(p.heldBox, "Audit") && PackageItem.getContents(p.heldBox).getStackInSlot(0).getCount() == 7,
                    "requested package preserves destination and contents");
            }
            h.succeed();
        });
    }
    private static void fragments(GameTestHelper h) {
        h.setBlock(A, AllBlocks.REPACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        h.setBlock(A.west(), Blocks.CHEST);
        h.runAfterDelay(8, () -> {
            RepackagerBlockEntity p = h.getBlockEntity(A);
            ChestBlockEntity chest = h.getBlockEntity(A.west());
            ItemStack first = parcel(17), second = parcel(3);
            PackageItem.setOrder(first, 471, 0, true, 0, false, null);
            PackageItem.setOrder(second, 471, 0, true, 1, true, null);
            chest.setItem(0, first); p.targetInventory.findNewCapability(); p.attemptToSend(null);
            h.assertTrue(!chest.getItem(0).isEmpty() && p.heldBox.isEmpty() && p.queuedExitingPackages.isEmpty(), "repackager waits for missing fragment without consuming input");
            chest.setItem(1, second); p.attemptToSend(null);
            h.assertTrue(chest.isEmpty() && !p.queuedExitingPackages.isEmpty(), "complete fragment set becomes queued output");
            int total = 0;
            for (var entry : p.queuedExitingPackages) {
                h.assertTrue(PackageItem.matchAddress(entry.stack, "Audit"), "repackager retains address");
                var contents = PackageItem.getContents(entry.stack);
                for (int slot = 0; slot < contents.getSlots(); slot++) total += entry.count * contents.getStackInSlot(slot).getCount();
            }
            h.assertTrue(total == 20, "repackager conserves the total fragment contents");
            h.succeed();
        });
    }
    private static void timer(GameTestHelper h) {
        h.setBlock(A.below(), Blocks.STONE);
        h.setBlock(A, AllBlocks.PULSE_TIMER.getDefaultState());
        h.runAfterDelay(8, () -> {
            PulseTimerBlockEntity timer = h.getBlockEntity(A);
            int previous = timer.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("State");
            for (int i = 0; i < 40; i++) {
                timer.tick();
                int current = timer.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("State");
                h.assertTrue(current == (previous + 1) % 20, "pulse timer retains the native tick interval without shaft power");
                previous = current;
            }
            h.succeed();
        });
    }
    private static void motorDetach(GameTestHelper h) {
        h.setBlock(A, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        h.setBlock(B, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        h.setBlock(A.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        ((CreativeMotorBlockEntity)h.getBlockEntity(A.below())).generatedSpeed.setValue(128);
        h.runAfterDelay(8, () -> {
            ChainConveyorBlockEntity a = h.getBlockEntity(A), b = h.getBlockEntity(B);
            a.prepareStats(); b.prepareStats(); a.addConnectionTo(b.getBlockPos()); b.addConnectionTo(a.getBlockPos());
            NetworkTopology.mark(a); NetworkTopology.flush();
            h.runAfterDelay(20, () -> {
                String failure = null;
                try {
                    h.assertTrue(Math.abs(a.getSpeed()) > 1 && Math.abs(b.getSpeed()) > 1, "real motor drives both conveyors before removal");
                    DifferentialCreate.LOGGER.info("DC_CREATE6_DETACH before a={} b={}", a.getSpeed(), b.getSpeed());
                    h.setBlock(A.below(), Blocks.AIR); NetworkTopology.flush();
                    DifferentialCreate.LOGGER.info("DC_CREATE6_DETACH after a={} b={}", a.getSpeed(), b.getSpeed());
                    try { MachiningClock.advance(h.getLevel()); }
                    catch (RuntimeException e) { failure = e.toString(); }
                } finally {
                    speed(a, 0); speed(b, 0); a.cancelDrops = b.cancelDrops = true;
                    h.setBlock(A, Blocks.AIR); h.setBlock(B, Blocks.AIR); h.setBlock(A.below(), Blocks.AIR);
                    NetworkTopology.flush();
                }
                h.assertTrue(failure == null, "removing a real motor must not crash the server: " + failure);
                h.succeed();
            });
        });
    }
    private static void frogBlocked(GameTestHelper h) {
        h.setBlock(FROG, AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        fixture(h, false, a -> {
            speed(a, 128);
            FrogportBlockEntity frog = h.getBlockEntity(FROG);
            for (int slot = 0; slot < frog.inventory.getSlots(); slot++) frog.inventory.setStackInSlot(slot, parcel(1));
            var box = new ChainConveyorPackage(179, parcel(17));
            a.addLoopingPackage(box);
            a.loopPorts.put(frog.getBlockPos().subtract(a.getBlockPos()), new ChainConveyorBlockEntity.ConnectedPort(180, null, "Audit"));
            a.tick();
            h.assertTrue(count(a) == 1 && !frog.isAnimationInProgress(), "full frogport keeps parcel on conveyor");
            h.succeed();
        });
    }
    private static void carry(GameTestHelper h, int sign) {
        fixture(h, true, a -> {
            ChainConveyorBlockEntity b = h.getBlockEntity(B);
            speed(a, sign * 8192); speed(b, sign * 4096); a.prepareStats(); b.prepareStats();
            var connection = b.getBlockPos().subtract(a.getBlockPos());
            var stats = a.connectionStats.get(connection);
            var box = new ChainConveyorPackage(stats.chainLength() - 1, parcel(17));
            a.addTravellingPackage(box, connection);
            double remaining = 1 - 360d / 8192;
            double arrival = ChainConveyorTravel.wrap(stats.tangentAngle() + 180 + 70 * sign);
            double expected = ChainConveyorTravel.wrap(arrival + ChainConveyorTravel.degrees(sign * 4096) * remaining);
            a.tick();
            h.assertTrue(b.getLoopingPackages().contains(box) && count(a) + count(b) == 1, "residual time crosses the chain endpoint exactly once");
            near(h, box.chainPosition, expected, .0001, "destination uses its own speed for the remaining time");
            float position = box.chainPosition;
            b.tick();
            near(h, box.chainPosition, position, 0, "later destination tick cannot advance the parcel twice");
            h.succeed();
        });
    }
    private static void firstPort(GameTestHelper h, int sign) {
        BlockPos otherFrog = FROG.east(3);
        h.setBlock(FROG, AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        h.setBlock(otherFrog, AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        fixture(h, false, a -> {
            speed(a, sign * 8192);
            var nearPort = h.absolutePos(FROG).subtract(a.getBlockPos());
            var farPort = h.absolutePos(otherFrog).subtract(a.getBlockPos());
            // Insert the later event first: map iteration cannot determine delivery.
            a.loopPorts.put(farPort, new ChainConveyorBlockEntity.ConnectedPort((float)ChainConveyorTravel.wrap(sign * 90), null, "Audit"));
            a.loopPorts.put(nearPort, new ChainConveyorBlockEntity.ConnectedPort((float)ChainConveyorTravel.wrap(sign * 45), null, "Audit"));
            a.addLoopingPackage(new ChainConveyorPackage(0, parcel(17))); a.tick();
            FrogportBlockEntity first = h.getBlockEntity(FROG), second = h.getBlockEntity(otherFrog);
            h.assertTrue(first.isAnimationInProgress() && !second.isAnimationInProgress() && count(a) == 0,
                "multiple revolutions deliver to the first matching port along the actual direction");
            h.succeed();
        });
    }
    private static void cycle(GameTestHelper h, int sign) {
        fixture(h, true, a -> {
            ChainConveyorBlockEntity b = h.getBlockEntity(B);
            speed(a, sign * 1e30); speed(b, sign * 1e30); a.prepareStats(); b.prepareStats();
            var connection = b.getBlockPos().subtract(a.getBlockPos());
            a.routingTable.receivePortInfo("Audit", connection); b.routingTable.receivePortInfo("Audit", connection.multiply(-1));
            var box = new ChainConveyorPackage(0, parcel(17)); a.addLoopingPackage(box);
            a.tick();
            h.assertTrue(count(a) + count(b) == 1 && Float.isFinite(box.chainPosition), "closed route at extreme finite speed conserves the parcel and completes");
            h.succeed();
        });
    }
    private static void factoryGauge(GameTestHelper h) {
        h.setBlock(A, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        h.setBlock(A.west(), Blocks.CHEST);
        h.setBlock(A.above(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR));
        h.runAfterDelay(8, () -> {
            PackagerBlockEntity p = h.getBlockEntity(A);
            PackagerLinkBlockEntity link = h.getBlockEntity(A.above());
            ChestBlockEntity chest = h.getBlockEntity(A.west());
            ItemStack content = new ItemStack(Items.IRON_INGOT, 17); chest.setItem(0, content.copy()); p.targetInventory.findNewCapability();
            UUID frequency = UUID.randomUUID(); LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = frequency;
            LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
            h.setBlock(FROG, AllBlocks.FACTORY_GAUGE.getDefaultState());
            FactoryPanelBlockEntity board = h.getBlockEntity(FROG); board.addPanel(PanelSlot.TOP_LEFT, frequency); board.initialize();
            h.assertTrue(h.getBlockEntity(FROG) == board, "active factory gauge remains a real world block entity");
            var panel = board.panels.get(PanelSlot.TOP_LEFT);
            panel.setFilter(content.copyWithCount(1)); panel.setValueSettings(null, new ValueSettings(0, 10), false); panel.tick();
            h.assertTrue(panel.getLevelInStorage() == 17 && panel.satisfied, "factory gauge reads network inventory and satisfies demand");
            panel.setValueSettings(null, new ValueSettings(0, 18), false); panel.tick();
            h.assertTrue(!panel.satisfied, "factory gauge reports unmet higher demand");
            h.succeed();
        });
    }
    private static void itemHatch(GameTestHelper h) {
        h.setBlock(A.east(), Blocks.CHEST);
        h.setBlock(A, AllBlocks.ITEM_HATCH.getDefaultState().setValue(ItemHatchBlock.FACING, Direction.EAST));
        h.runAfterDelay(8, () -> {
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT, 17));
            var hit = new BlockHitResult(Vec3.atCenterOf(h.absolutePos(A)), Direction.WEST, h.absolutePos(A), false);
            h.getBlockState(A).useItemOn(player.getMainHandItem(), h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
            ChestBlockEntity chest = h.getBlockEntity(A.east());
            h.assertTrue(player.getMainHandItem().isEmpty() && chest.getItem(0).getCount() == 17, "item hatch deposits held stack into attached chest without shaft power");
            h.succeed();
        });
    }
    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String message) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
            message + ": expected " + expected + ", actual " + actual);
    }
}
