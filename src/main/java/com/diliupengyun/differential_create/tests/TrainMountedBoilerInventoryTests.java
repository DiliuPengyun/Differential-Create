package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.TrainAssembly;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.minecart.TrainCargoManager;
import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity.CreativeSmartFluidTank;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native mounting excludes boilers at source; actual train saves, supply and rotated rebuilds retain that rule. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainMountedBoilerInventoryTests {
    private TrainMountedBoilerInventoryTests() {}
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 160, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (boolean creative : new boolean[]{false, true}) for (int kind = 0; kind < 4; kind++) {
            final int role = kind;
            add(tests, "mounted_boiler_" + (creative ? "creative_" : "ordinary_") + role, h -> mount(h, creative, role));
        }
        for (var rotation : Rotation.values()) for (boolean empty : new boolean[]{false, true})
            add(tests, "mounted_boiler_train_" + rotation.name().toLowerCase(Locale.ROOT) + (empty ? "_empty" : "_water"),
                h -> train(h, rotation, empty));
        for (boolean creative : new boolean[]{false, true}) for (boolean active : new boolean[]{false, true})
            add(tests, "mounted_boiler_multiblock_" + (creative ? "creative_" : "ordinary_") + active,
                h -> multiblock(h, creative, active));
        return tests;
    }
    private static void multiblock(GameTestHelper h, boolean creative, boolean active) {
        var bottom = new BlockPos(3, 3, 3);
        for (int y = 0; y < 4; y++)
            h.setBlock(bottom.above(y), (creative ? AllBlocks.CREATIVE_FLUID_TANK : AllBlocks.FLUID_TANK).getDefaultState());
        h.runAfterDelay(25, () -> {
            FluidTankBlockEntity root = h.getBlockEntity(bottom);
            h.assertTrue(root.isController() && root.getTotalTankSize() == 4, "Native four-block tank connectivity is complete");
            root.boiler.attachedEngines = active ? 1 : 0;
            var storage = new MountedStorageManager(); int nativeCount = 0;
            for (int y = 0; y < 4; y++) {
                FluidTankBlockEntity part = h.getBlockEntity(bottom.above(y));
                h.assertTrue(part.getControllerBE() == root, "Every native tank member resolves its boiler controller");
                var type = MountedFluidStorageType.REGISTRY.get(part.getBlockState().getBlock());
                var original = type.mount(h.getLevel(), part.getBlockState(), part.getBlockPos(), part);
                if (original != null) nativeCount += original.getTanks();
                storage.addBlock(h.getLevel(), part.getBlockState(), part.getBlockPos(), new BlockPos(0, y, 0), part);
            }
            storage.initialize();
            h.assertTrue(storage.getFluids().getTanks() == (active ? 0 : nativeCount),
                "Active boiler members expose no capacity; inactive ordinary/creative multiblocks keep native mounting behavior");
            if (active) h.assertTrue(storage.getFluids().storages.isEmpty()
                && storage.getFluids().fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 0,
                "Creative boiler child blocks cannot accept fluid through hidden capacity");
            h.succeed();
        });
    }
    private static void mount(GameTestHelper h, boolean creative, int role) {
        var pos = new BlockPos(3, 3, 3);
        h.setBlock(pos, (creative ? AllBlocks.CREATIVE_FLUID_TANK : AllBlocks.FLUID_TANK).getDefaultState());
        FluidTankBlockEntity tank = h.getBlockEntity(pos);
        if (tank.getTankInventory() instanceof CreativeSmartFluidTank endless)
            endless.setContainedFluid(new FluidStack(Fluids.WATER, 1000));
        else tank.getTankInventory().setFluid(new FluidStack(Fluids.WATER, 1000));
        tank.boiler.attachedEngines = role & 1;
        tank.boiler.attachedWhistles = role >> 1;
        int contained = tank.getTankInventory().getFluidAmount();
        var storage = new MountedStorageManager();
        storage.addBlock(h.getLevel(), tank.getBlockState(), tank.getBlockPos(), BlockPos.ZERO, tank);
        storage.initialize();
        var fluids = storage.getFluids();
        h.assertTrue(fluids.getTanks() == (role == 0 ? 1 : 0) && fluids.storages.isEmpty() == (role != 0),
            "Only inactive ordinary/creative tanks become mounted fluid cargo; engine and whistle boilers are excluded");
        if (role != 0) {
            h.assertTrue(fluids.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 0
                && fluids.fill(new FluidStack(Fluids.LAVA, 1000), FluidAction.EXECUTE) == 0
                && fluids.drain(1000, FluidAction.EXECUTE).isEmpty()
                && fluids.drain(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE).isEmpty(),
                "Mounted boilers cannot accept or expose ordinary liquid stock");
            h.assertTrue(tank.getTankInventory().getFluidAmount() == contained, "Mounting does not mutate the source block data");
        } else h.assertTrue(fluids.drain(1, FluidAction.SIMULATE).getAmount() == 1, "Inactive tanks retain native fluid access");
        var tag = new CompoundTag(); storage.write(tag, h.getLevel().registryAccess(), false);
        var restored = new MountedStorageManager(); restored.read(tag, h.getLevel().registryAccess(), false, null);
        h.assertTrue(restored.getFluids().getTanks() == fluids.getTanks(), "Current-format serialization preserves excluded boiler capacity");
        h.succeed();
    }
    private static void train(GameTestHelper h, Rotation rotation, boolean empty) {
        TrainTestFixture.create(h, "flat", false, false, setup -> {}, f -> {
            var carriage = f.train.carriages.getFirst();
            var boiler = TrainPhysics.carriage(carriage).layout.boilers.keySet().iterator().next();
            var fluids = carriage.storage.getFluids();
            h.assertTrue(!fluids.storages.containsKey(boiler) && fluids.getTanks() == 1,
                "Native carriage total inventory contains only its reserve, with no hidden boiler capacity");
            if (empty) fluids.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            int water = f.water(), version = carriage.storage.getVersion(), capacity = fluids.getTankCapacity(0);
            fluids.fill(new FluidStack(Fluids.WATER, Integer.MAX_VALUE), FluidAction.SIMULATE);
            fluids.drain(1, FluidAction.SIMULATE);
            h.assertTrue(carriage.storage.getVersion() == version && f.water() == water, "Simulation does not notify or mutate cargo");
            h.assertTrue(fluids.fill(new FluidStack(Fluids.WATER, Integer.MAX_VALUE), FluidAction.EXECUTE) == capacity - water
                && f.water() == capacity && fluids.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 0,
                "Total fluid inventory fills only reserve capacity and rejects boiler overflow");
            fluids.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            fluids.fill(new FluidStack(Fluids.WATER, 2500), FluidAction.EXECUTE);
            version = carriage.storage.getVersion(); water = f.water();
            f.tick(1, false);
            h.assertTrue(f.water() < water && carriage.storage.getVersion() > version,
                "Steam consumption uses the native total inventory and cargo change notification");
            water = f.water(); f.reload(); carriage = f.train.carriages.getFirst();
            h.assertTrue(f.water() == water && carriage.storage.getFluids().getTanks() == 1
                && !carriage.storage.getFluids().storages.containsKey(boiler), "Current-format train readback keeps only actual reserve stock");
            var cc = (CarriageContraption) f.entity().getContraption();
            h.assertTrue(cc.getBlocks().containsKey(boiler) && cc.getBlocks().get(boiler).nbt().contains("Boiler"),
                "Excluded inventory does not remove the actual boiler structure or running state");
            TrainPhysics.carriage(carriage).apply(cc, 1, false, true); cc.returnStorageForDisassembly(carriage.storage);
            var transform = new StructureTransform(cc.anchor, Direction.Axis.Y, rotation, Mirror.NONE);
            var wheel = transform.apply(BlockPos.ZERO);
            final int expectedWater = water;
            f.close(); cc.addBlocksToWorld(h.getLevel(), transform);
            h.runAfterDelay(25, () -> {
                var next = new CarriageContraption(rotation.rotate(Direction.SOUTH));
                try { h.assertTrue(next.assemble(h.getLevel(), wheel), "Rotated native train can be assembled again"); }
                catch (com.simibubi.create.content.contraptions.AssemblyException error) { throw new AssertionError(error); }
                var cargo = new TrainCargoManager();
                for (var block : next.getBlocks().values()) {
                    var world = block.pos().offset(next.anchor);
                    cargo.addBlock(h.getLevel(), block.state(), world, block.pos(), h.getLevel().getBlockEntity(world));
                }
                cargo.initialize();
                var layout = ((TrainAssembly.LayoutHolder) next).differentialcreate$layout();
                h.assertTrue(layout.boilers.size() == 1 && cargo.getFluids().getTanks() == 1
                    && cargo.getFluids().getFluidInTank(0).getAmount() == expectedWater,
                    "Rotated disassembly/reassembly retains boiler function, reserve stock and exclusion");
                h.succeed();
            });
        });
    }
}
