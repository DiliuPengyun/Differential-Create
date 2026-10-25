package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.kinetics.PipeHydraulics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native inventory restrictions and geometry changes must preserve real flow. */
@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class FluidInventoryPhysicsTests {
    private static final String TEMPLATE = "kineticphysicstests.physics_test";
    private static final BlockPos SOURCE = new BlockPos(4, 5, 4), UPPER = SOURCE.below();
    private static final BlockPos MIDDLE = UPPER.below(), LOWER = MIDDLE.below(), SINK = LOWER.below();

    @GameTest(template = TEMPLATE, batch = "fluid_inventory_nearlyEmptyTankStillReceivesFromAnotherPipe", timeoutTicks = 100)
    public static void nearlyEmptyTankStillReceivesFromAnotherPipe(GameTestHelper h) {
        sharedTank(h, true);
    }

    @GameTest(template = TEMPLATE, batch = "fluid_inventory_nearlyFullTankStillSuppliesAnotherPipe", timeoutTicks = 100)
    public static void nearlyFullTankStillSuppliesAnotherPipe(GameTestHelper h) {
        sharedTank(h, false);
    }

    @GameTest(template = TEMPLATE, batch = "fluid_inventory_formerVerticalArmDoesNotBlockHorizontalGlassPipe", timeoutTicks = 100)
    public static void formerVerticalArmDoesNotBlockHorizontalGlassPipe(GameTestHelper h) {
        var inlet = new BlockPos(3, 3, 4);
        var pipePos = inlet.east();
        var outlet = pipePos.east();
        forceChunks(h);
        h.setBlock(inlet, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(outlet, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(pipePos, AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.runAfterDelay(20, () -> {
            FluidTankBlockEntity source = h.getBlockEntity(inlet), sink = h.getBlockEntity(outlet);
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pipePos));
            if (pipe.interfaces == null) pipe.blockEntity.initialize();
            FluidKinetics.register(pipe);
            double[] received = new double[2];
            for (int attempt = 0; attempt < 8; attempt++) {
                setWater(source, 6000); setWater(sink, 0);
                var saved = FluidKinetics.state(pipe);
                saved.fluid = new FluidStack(Fluids.WATER, 1);
                saved.outward.clear(); saved.buffers.clear(); saved.contents.clear();
                saved.outward.put(Direction.WEST, -16d); saved.outward.put(Direction.EAST, 16d);
                saved.intervalFlows.clear();
                saved.phases = null; saved.macroLiquid = Double.NaN;
                saved.core = PipeContents.EMPTY; saved.coreFlows = new double[0];
                saved.pumpFacing = null;
                // Preserve 100 or 150 mB after a downward branch became glass.
                // The retired arm is inventory history, not current geometry.
                saved.contents.put(Direction.DOWN, PipeContents.FULL);
                var arm = attempt % 2 == 0 ? PipeContents.of(0, .5) : PipeContents.FULL;
                saved.contents.put(Direction.WEST, arm);
                saved.contents.put(Direction.EAST, arm);
                double before = source.getTankInventory().getFluidAmount() + saved.volume();
                FluidKinetics.invalidate(h.getLevel());
                var frame = FluidKinetics.capture(h.getLevel());
                FluidKinetics.beginTick(h.getLevel());
                FluidKinetics.advance(frame, com.diliupengyun.differential_create.kinetics.RotationMath.TICK_MINUTES);
                double after = source.getTankInventory().getFluidAmount() + sink.getTankInventory().getFluidAmount()
                    + saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
                h.assertTrue(Math.abs(after - before) < 1e-6, "retired-arm liquid remains real inventory");
                // Coasting motion, not a removed container-level pressure,
                // supplies this geometry test. Include fractional receipts.
                var receipt = saved.buffers.get(Direction.EAST);
                double delivered = sink.getTankInventory().getFluidAmount()
                    + (receipt != null && receipt.delivery ? receipt.amount : 0);
                received[attempt % 2] = Math.max(received[attempt % 2], delivered);
            }
            h.assertTrue(received[0] > .1, "horizontal flow does not wait for a retired vertical arm: " + received[0]);
            h.assertTrue(received[1] > .1, "retained excess liquid still permits horizontal flow: " + received[1]);
            h.succeed();
        });
    }

    private static void sharedTank(GameTestHelper h, boolean nearlyEmpty) {
        forceChunks(h);
        for (var pos : new BlockPos[] {SOURCE, MIDDLE, SINK}) h.setBlock(pos, AllBlocks.FLUID_TANK.getDefaultState());
        for (var pos : new BlockPos[] {UPPER, LOWER}) {
            boolean pump = pos.equals(nearlyEmpty ? LOWER : UPPER);
            var block = pump ? AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.DOWN)
                : AllBlocks.FLUID_PIPE.getDefaultState();
            if (!pump) for (var side : Direction.values())
                block = block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), side.getAxis() == Direction.Axis.Y);
            h.setBlock(pos, block);
        }
        h.runAfterDelay(20, () -> {
            FluidTankBlockEntity source = h.getBlockEntity(SOURCE), middle = h.getBlockEntity(MIDDLE), sink = h.getBlockEntity(SINK);
            PumpBlockEntity pump = h.getBlockEntity(nearlyEmpty ? LOWER : UPPER);
            int largestTransfer = 0;
            // Each independent trial restores this physical start. Multiple
            // attempts allow an initially cold solver to defer under its CPU
            // budget; every accepted transfer still has to conserve inventory.
            for (int attempt = 0; attempt < 8; attempt++) {
                setWater(source, nearlyEmpty ? 1000 : source.getTankInventory().getCapacity());
                setWater(middle, nearlyEmpty ? 1 : middle.getTankInventory().getCapacity() - 1);
                setWater(sink, 0);
                for (var pos : new BlockPos[] {UPPER, LOWER}) full(h, pos);
                ElasticKinetics.state(pump).physicalSpeed = 512;
                FluidKinetics.invalidate(h.getLevel());
                var frame = FluidKinetics.capture(h.getLevel());
                double total = stored(h);
                int before = nearlyEmpty ? source.getTankInventory().getFluidAmount() : sink.getTankInventory().getFluidAmount();
                FluidKinetics.beginTick(h.getLevel());
                FluidKinetics.advance(frame, com.diliupengyun.differential_create.kinetics.RotationMath.TICK_MINUTES);
                int transfer = nearlyEmpty ? before - source.getTankInventory().getFluidAmount()
                    : sink.getTankInventory().getFluidAmount() - before;
                largestTransfer = Math.max(largestTransfer, transfer);
                h.assertTrue(Math.abs(stored(h) - total) < 1e-6, "a shared tank keeps all native and owned liquid");
                h.assertTrue(middle.getTankInventory().getFluidAmount() >= 0
                    && middle.getTankInventory().getFluidAmount() <= middle.getTankInventory().getCapacity(),
                    "sequential coarse commits may use newly delivered stock or freed space but never overdraw the shared tank");
            }
            h.assertTrue(largestTransfer >= 2, nearlyEmpty
                ? "a one-mB withdrawal limit must not throttle another pipe refilling that tank: " + largestTransfer
                : "a one-mB receipt limit must not throttle another pipe draining that tank: " + largestTransfer);
            h.succeed();
        });
    }

    private static void forceChunks(GameTestHelper h) {
        var bounds = h.getBounds();
        for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
            for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
                h.getLevel().setChunkForced(x, z, true);
    }
    private static void setWater(FluidTankBlockEntity tank, int amount) {
        tank.getTankInventory().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
        if (amount > 0) tank.getTankInventory().fill(new FluidStack(Fluids.WATER, amount), FluidAction.EXECUTE);
    }
    private static void full(GameTestHelper h, BlockPos pos) {
        var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
        if (pipe.interfaces == null) pipe.blockEntity.initialize();
        FluidKinetics.register(pipe);
        PipeHydraulics state = FluidKinetics.state(pipe);
        state.fluid = new FluidStack(Fluids.WATER, 1);
        state.outward.clear(); state.buffers.clear(); state.contents.clear(); state.intervalFlows.clear();
        state.phases = null; state.macroLiquid = Double.NaN;
        state.pumpFlow = 0;
        state.pumpFacing = pipe.blockEntity instanceof PumpBlockEntity ? Direction.DOWN : null;
        state.core = state.pumpFacing == null ? PipeContents.EMPTY : PipeContents.FULL;
        state.coreFlows = new double[0];
        state.contents.put(Direction.UP, PipeContents.FULL); state.contents.put(Direction.DOWN, PipeContents.FULL);
    }
    private static double stored(GameTestHelper h) {
        double water = 0;
        for (var pos : new BlockPos[] {SOURCE, MIDDLE, SINK}) {
            FluidTankBlockEntity tank = h.getBlockEntity(pos); water += tank.getTankInventory().getFluidAmount();
        }
        for (var pos : new BlockPos[] {UPPER, LOWER}) {
            var state = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
            water += state.volume() + state.buffers.values().stream().mapToDouble(b -> b.amount).sum();
        }
        return water;
    }
}
