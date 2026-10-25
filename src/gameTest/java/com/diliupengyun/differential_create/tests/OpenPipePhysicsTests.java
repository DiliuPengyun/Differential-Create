package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real Create open-end stock must back every world-fluid discharge. */
@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class OpenPipePhysicsTests {
    private static final String TEMPLATE = "kineticphysicstests.physics_test";
    private static final BlockPos PORT = new BlockPos(8, 3, 8), OUTPUT = PORT.east();

    @GameTest(template = TEMPLATE, batch = "open_pipe_cachedWaterQueriesDoNotReactAfterReload", timeoutTicks = 40)
    public static void cachedWaterQueriesDoNotReactAfterReload(GameTestHelper h) {
        var pipe = boundary(h);
        var handler = handler(pipe);
        ((FluidTank) handler).setFluid(new FluidStack(Fluids.WATER, 100));
        var registries = h.getLevel().registryAccess();
        var saved = pipe.serializeNBT(registries);
        var restored = OpenEndedPipe.fromNBT(saved, registries, h.absolutePos(PORT));
        restored.manageSource(h.getLevel(), null);
        var stock = handler(restored);
        h.setBlock(OUTPUT, Blocks.LAVA);
        h.runAfterDelay(20, () -> {
            for (int i = 0; i < 100; i++) {
                h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 900), FluidAction.SIMULATE) == 900,
                    "planning sees remaining space without spending water");
                h.assertTrue(h.getBlockState(OUTPUT).is(Blocks.LAVA), "idle queries cannot turn lava into obsidian");
                h.assertTrue(restored.serializeNBT(registries).equals(saved), "queries preserve saved native stock and direction");
            }
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aRealWaterDripDischargesItsMatchingCachedStock", timeoutTicks = 20)
    public static void aRealWaterDripDischargesItsMatchingCachedStock(GameTestHelper h) {
        var stock = handler(boundary(h));
        ((FluidTank) stock).setFluid(new FluidStack(Fluids.WATER, 100));
        h.setBlock(OUTPUT, Blocks.LAVA);
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 1, "one real mB is discharged");
        h.assertTrue(h.getBlockState(OUTPUT).is(Blocks.OBSIDIAN) && stock.getFluidInTank(0).isEmpty(),
            "a small real discharge reacts immediately and spends the matching stock already at the mouth");
        h.setBlock(OUTPUT, Blocks.LAVA);
        for (int i = 0; i < 100; i++) stock.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE);
        h.assertTrue(h.getBlockState(OUTPUT).is(Blocks.LAVA) && stock.getFluidInTank(0).isEmpty(),
            "past water transport cannot generate another free obsidian block");
        h.assertTrue(stock.fill(FluidStack.EMPTY, FluidAction.EXECUTE) == 0
            && h.getBlockState(OUTPUT).is(Blocks.LAVA), "an empty execution cannot react using old stock");
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 1
            && h.getBlockState(OUTPUT).is(Blocks.OBSIDIAN) && stock.getFluidInTank(0).isEmpty(),
            "another real drip can react without a bucket threshold");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aSingleRealDripReactsWithAnEmptyNativeBuffer", timeoutTicks = 20)
    public static void aSingleRealDripReactsWithAnEmptyNativeBuffer(GameTestHelper h) {
        var stock = handler(boundary(h));
        h.setBlock(OUTPUT, Blocks.LAVA);
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1), FluidAction.SIMULATE) == 1
            && h.getBlockState(OUTPUT).is(Blocks.LAVA) && stock.getFluidInTank(0).isEmpty(),
            "a one-mB capacity query is read-only even with no staged bucket");
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 1
            && h.getBlockState(OUTPUT).is(Blocks.OBSIDIAN) && stock.getFluidInTank(0).isEmpty(),
            "the first actual drip reacts immediately without waiting for 1000 mB");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_firstWaterBucketPlacesSource", timeoutTicks = 20)
    public static void firstWaterBucketPlacesSource(GameTestHelper h) {
        var stock = handler(boundary(h));
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE) == 1000,
            "an empty buffer reports its full water capacity");
        h.assertTrue(h.getBlockState(OUTPUT).isAir() && stock.getFluidInTank(0).isEmpty(), "planning does not place or store water");
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 1000,
            "the first bucket is accepted using the incoming fluid's block state");
        h.assertTrue(h.getBlockState(OUTPUT).is(Blocks.WATER) && h.getBlockState(OUTPUT).getFluidState().isSource()
            && stock.getFluidInTank(0).isEmpty(), "current filled stock is discharged once into a water source");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_worldIntakeQueriesAndWrongFiltersPreserveSource", timeoutTicks = 20)
    public static void worldIntakeQueriesAndWrongFiltersPreserveSource(GameTestHelper h) {
        var pipe = boundary(h); var stock = handler(pipe);
        h.setBlock(OUTPUT, Blocks.WATER);
        var saved = pipe.serializeNBT(h.getLevel().registryAccess());
        for (int i = 0; i < 100; i++) {
            h.assertTrue(stock.drain(new FluidStack(Fluids.WATER, 25), FluidAction.SIMULATE).getAmount() == 25,
                "a source query offers only the requested amount");
            h.assertTrue(pipe.serializeNBT(h.getLevel().registryAccess()).equals(saved)
                && h.getBlockState(OUTPUT).getFluidState().isSource(), "source queries preserve stock, mode and world");
        }
        h.assertTrue(stock.drain(new FluidStack(Fluids.LAVA, 25), FluidAction.EXECUTE).isEmpty()
            && pipe.serializeNBT(h.getLevel().registryAccess()).equals(saved)
            && h.getBlockState(OUTPUT).getFluidState().isSource(), "a rejected filter cannot remove another world's fluid");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_partialCapabilityRequestCannotExtractAWorldBucket", timeoutTicks = 20)
    public static void partialCapabilityRequestCannotExtractAWorldBucket(GameTestHelper h) {
        var pipe = boundary(h); var stock = handler(pipe);
        h.setBlock(OUTPUT, Blocks.WATER);
        var saved = pipe.serializeNBT(h.getLevel().registryAccess());
        for (int requested : new int[] {1, 25, 100, 999}) {
            h.assertTrue(stock.drain(requested, FluidAction.EXECUTE).isEmpty()
                && stock.getFluidInTank(0).isEmpty() && h.getBlockState(OUTPUT).getFluidState().isSource()
                && pipe.serializeNBT(h.getLevel().registryAccess()).equals(saved),
                "a partial request cannot take a whole source into an unreserved cache: " + requested);
        }
        h.assertTrue(stock.drain(1000, FluidAction.EXECUTE).getAmount() == 1000
            && stock.getFluidInTank(0).isEmpty() && !h.getBlockState(OUTPUT).getFluidState().isSource(),
            "a complete capability request receives the complete world bucket without a surplus");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_worldIntakeRequiresSpaceForTheWholeTransfer", timeoutTicks = 20)
    public static void worldIntakeRequiresSpaceForTheWholeTransfer(GameTestHelper h) {
        var pipe = boundary(h); var stock = handler(pipe);
        h.setBlock(OUTPUT, Blocks.WATER);
        var intake = (com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement) stock;
        var water = new FluidStack(Fluids.WATER, 1);
        var saved = pipe.serializeNBT(h.getLevel().registryAccess());
        for (double space : new double[] {0, 100, 900, 999, 999.999})
            h.assertTrue(intake.differentialcreate$takeWorld(water, 25, space).isEmpty()
                && pipe.serializeNBT(h.getLevel().registryAccess()).equals(saved) && h.getBlockState(OUTPUT).getFluidState().isSource(),
                "insufficient actual remaining space preserves the world source: " + space);
        h.assertTrue(intake.differentialcreate$takeWorld(water, 25, 1000).getAmount() == 25
            && stock.getFluidInTank(0).getAmount() == 975 && !h.getBlockState(OUTPUT).getFluidState().isSource(),
            "only a reserved whole bucket can deliver a small first parcel");
        h.assertTrue(!pipe.serializeNBT(h.getLevel().registryAccess()).contains("DifferentialIntakeHeight"),
            "a capacity reservation adds no cached pressure or finite water surface");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_oldIntakeStockSurvivesWithoutInventedHeadOrBulkSpill", timeoutTicks = 20)
    public static void oldIntakeStockSurvivesWithoutInventedHeadOrBulkSpill(GameTestHelper h) {
        var pipe = boundary(h); var stock = handler(pipe);
        ((FluidTank) stock).setFluid(new FluidStack(Fluids.WATER, 900));
        var registries = h.getLevel().registryAccess(); var saved = pipe.serializeNBT(registries);
        saved.putBoolean("Pulling", true); saved.putDouble("DifferentialIntakeHeight", .9);
        var restored = OpenEndedPipe.fromNBT(saved, registries, h.absolutePos(PORT)); restored.manageSource(h.getLevel(), null);
        var actual = handler(restored);
        h.assertTrue(actual.getFluidInTank(0).getAmount() == 900 && !restored.serializeNBT(registries).contains("DifferentialIntakeHeight"),
            "reload preserves actual liquid and ignores unknown cache-head metadata");
        h.assertTrue(((com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement) actual)
            .differentialcreate$discharge(new FluidStack(Fluids.WATER, 1), .025, FluidAction.EXECUTE) == .025
            && actual.getFluidInTank(0).getAmount() == 900 && h.getBlockState(OUTPUT).isAir(),
            "one actual air drip cannot empty a separate 900-mB intake reservation");
        h.assertTrue(actual.drain(25, FluidAction.EXECUTE).getAmount() == 25 && actual.getFluidInTank(0).getAmount() == 875,
            "existing real intake can still be withdrawn exactly once");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_blindHorizontalPipeDoesNotTakeTheWorldSource", timeoutTicks = 660)
    public static void blindHorizontalPipeDoesNotTakeTheWorldSource(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.WATER, 0, Direction.WEST, Direction.EAST);
            h.setBlock(PORT.west(), Blocks.WATER); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 10) return;
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
            var open = ((IPipePressure) pipe.getConnection(Direction.WEST)).differentialcreate$source();
            if (!(open instanceof OpenEndedPipe end)) return;
            double nativeAmount = handler(end).getFluidInTank(0).getAmount();
            h.assertTrue(owned(h) == 0 && nativeAmount == 0 && discharged(h, end).isEmpty()
                && h.getBlockState(PORT.west()).getFluidState().isSource(),
                "the 100-mB horizontal pipe has no surplus cache, passive intake or world-source removal");
        });
        h.runAfterDelay(620, h::succeed);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_gravityCannotTakeABucketIntoNinePipeCells", timeoutTicks = 180)
    public static void gravityCannotTakeABucketIntoNinePipeCells(GameTestHelper h) { worldColumn(h, 9, 0, false); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_occupiedCapacityPreventsAnotherWorldBucket", timeoutTicks = 180)
    public static void occupiedCapacityPreventsAnotherWorldBucket(GameTestHelper h) { worldColumn(h, 10, 1, false); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_exactlyOneBucketOfFreePipeCapacityAllowsGravityIntake", timeoutTicks = 180)
    public static void sealedBucketSizedColumnStillNeedsAnAirOutlet(GameTestHelper h) { worldColumn(h, 10, 0, false); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aFlatBucketSizedPipeStillNeedsAnIntakeDrive", timeoutTicks = 180)
    public static void aFlatBucketSizedPipeStillNeedsAnIntakeDrive(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        var first = PORT.west(5);
        for (int i = 0; i < 10; i++) h.setBlock(first.east(i), AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(first.east(10), Blocks.STONE); encloseSource(h, first.west(), Direction.EAST);
        h.runAfterDelay(20, () -> { h.setBlock(first.west(), Blocks.WATER); FluidKinetics.invalidate(h.getLevel()); });
        h.runAfterDelay(140, () -> {
            h.assertTrue(h.getBlockState(first.west()).getFluidState().isSource(), "a flat undriven network leaves the source in the world");
            for (int i = 0; i < 10; i++) {
                var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(first.east(i))));
                h.assertTrue(saved.volume() == 0 && saved.buffers.values().stream().allMatch(b -> b.amount == 0),
                    "flat pipes do not passively acquire water despite their sufficient total nominal capacity");
            }
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aReceivingTankCountsItsActualFreeCapacity", timeoutTicks = 180)
    public static void aReceivingTankCountsItsActualFreeCapacity(GameTestHelper h) { worldTank(h, 900, true); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aNearlyFullTankDoesNotAuthorizeExcessWorldIntake", timeoutTicks = 180)
    public static void aNearlyFullTankDoesNotAuthorizeExcessWorldIntake(GameTestHelper h) { worldTank(h, 899, false); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_sideWorldIntakeCanUseGravityToALowerTank", timeoutTicks = 180)
    public static void sideWorldIntakeCanUseGravityToALowerTank(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        var corner = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) corner = corner.setValue(
            com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side),
            side == Direction.WEST || side == Direction.DOWN);
        h.setBlock(PORT, corner); h.setBlock(PORT.below(), AllBlocks.FLUID_TANK.getDefaultState());
        h.runAfterDelay(20, () -> { h.setBlock(PORT.west(), Blocks.WATER); FluidKinetics.invalidate(h.getLevel()); });
        h.runAfterDelay(140, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            h.assertTrue(!h.getBlockState(PORT.west()).getFluidState().isSource()
                && Math.abs(tank.getTankInventory().getFluidAmount() + columnStored(h, 1) - 1000) < 1e-6,
                "a lower actual receiver supplies a gravity route without a running pump");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_multipleWorldMouthsReserveTheSameCapacityOnlyOnce", timeoutTicks = 240)
    public static void multipleWorldMouthsReserveTheSameCapacityOnlyOnce(GameTestHelper h) {
        column(h, 10);
        var top = PORT.above(9);
        // A fixed encased junction cannot lose its source mouths through
        // Create's automatic shape updates or vanilla waterlogging.
        var tee = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) tee = tee.setValue(
            com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side),
            side == Direction.DOWN || side == Direction.WEST || side == Direction.EAST);
        h.setBlock(top, tee);
        h.runAfterDelay(20, () -> {
            encloseSource(h, top.west(), Direction.EAST);
            encloseSource(h, top.east(), Direction.WEST);
            h.setBlock(top.west(), Blocks.WATER); h.setBlock(top.east(), Blocks.WATER);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            double stock = columnStored(h, 10);
            h.assertTrue(stock <= 1050 + 1e-7, "two source mouths cannot reserve two buckets into 1050 mB: " + stock);
        });
        h.runAfterDelay(200, () -> {
            int sources = (h.getBlockState(top.west()).getFluidState().isSource() ? 1 : 0)
                + (h.getBlockState(top.east()).getFluidState().isSource() ? 1 : 0);
            h.assertTrue(sources == 2 && columnStored(h, 10) == 0,
                "two immersed inlets cannot erase sealed air or counterflow it through either source mouth");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aWetDrivenPumpCanReserveWorldWater", timeoutTicks = 240)
    public static void aWetDrivenPumpCanReserveWorldWater(GameTestHelper h) { worldPump(h, true); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aDryDrivenPumpCannotSelfPrimeFromAWorldSource", timeoutTicks = 240)
    public static void anAirFilledDrivenPumpSelfPrimesFromAWorldSource(GameTestHelper h) { worldPump(h, false); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_otherFluidCannotEraseCachedWater", timeoutTicks = 20)
    public static void otherFluidCannotEraseCachedWater(GameTestHelper h) {
        var stock = handler(boundary(h));
        ((FluidTank) stock).setFluid(new FluidStack(Fluids.WATER, 100));
        for (var action : FluidAction.values())
            h.assertTrue(stock.fill(new FluidStack(Fluids.LAVA, 1000), action) == 0
                && stock.getFluidInTank(0).getFluid() == Fluids.WATER && stock.getFluidInTank(0).getAmount() == 100,
                "a different-fluid request preserves all owned water: " + action);
        h.assertTrue(h.getBlockState(OUTPUT).isAir(), "rejected receipts cannot place a world fluid");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aSmallActualDischargeCannotBeDrawnBack", timeoutTicks = 20)
    public static void aSmallActualDischargeCannotBeDrawnBack(GameTestHelper h) {
        var pipe = boundary(h);
        var stock = handler(pipe);
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 10), FluidAction.EXECUTE) == 10,
            "a small exposed discharge is accepted immediately");
        h.assertTrue(stock.getFluidInTank(0).isEmpty() && h.getBlockState(OUTPUT).isAir(),
            "the real drop has left stock without creating a free source block");
        for (var action : FluidAction.values())
            h.assertTrue(stock.drain(new FluidStack(Fluids.WATER, 10), action).isEmpty(),
                "a discharged drop cannot be sucked back from a placement counter: " + action);
        h.assertTrue(discharged(h, pipe).getAmount() == 10,
            "the source-placement threshold remembers only the actual discharged amount");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_continuousDropsPlaceOneSourceAtOneBucket", timeoutTicks = 20)
    public static void continuousDropsPlaceOneSourceAtOneBucket(GameTestHelper h) {
        var pipe = boundary(h);
        var stock = handler(pipe);
        for (int i = 0; i < 9; i++) {
            h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE) == 100,
                "each real stream parcel leaves immediately");
            h.assertTrue(stock.getFluidInTank(0).isEmpty() && h.getBlockState(OUTPUT).isAir(),
                "partial discharges neither remain drainable nor place an early source");
        }
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE) == 100,
            "the final actual parcel completes the world bucket");
        h.assertTrue(h.getBlockState(OUTPUT).getFluidState().isSource()
            && stock.getFluidInTank(0).isEmpty() && discharged(h, pipe).isEmpty(),
            "one thousand actual discharged mB place exactly one source without retained duplicate stock");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_dischargeProgressSurvivesReloadWithoutBecomingStock", timeoutTicks = 20)
    public static void dischargeProgressSurvivesReloadWithoutBecomingStock(GameTestHelper h) {
        var pipe = boundary(h);
        handler(pipe).fill(new FluidStack(Fluids.WATER, 600), FluidAction.EXECUTE);
        var registries = h.getLevel().registryAccess();
        var saved = pipe.serializeNBT(registries);
        var restored = OpenEndedPipe.fromNBT(saved, registries, h.absolutePos(PORT));
        restored.manageSource(h.getLevel(), null);
        var stock = handler(restored);
        for (int i = 0; i < 100; i++) stock.fill(new FluidStack(Fluids.WATER, 400), FluidAction.SIMULATE);
        h.assertTrue(restored.serializeNBT(registries).equals(saved) && stock.getFluidInTank(0).isEmpty()
            && h.getBlockState(OUTPUT).isAir(), "reload and queries cannot replay discharge or fabricate liquid stock");
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 400), FluidAction.EXECUTE) == 400
            && h.getBlockState(OUTPUT).getFluidState().isSource(), "new real liquid completes the preserved placement threshold");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_differentDischargesDoNotCombineIntoAFreeSource", timeoutTicks = 20)
    public static void differentDischargesDoNotCombineIntoAFreeSource(GameTestHelper h) {
        var pipe = boundary(h);
        var stock = handler(pipe);
        stock.fill(new FluidStack(Fluids.WATER, 600), FluidAction.EXECUTE);
        stock.fill(new FluidStack(Fluids.LAVA, 400), FluidAction.EXECUTE);
        h.assertTrue(h.getBlockState(OUTPUT).isAir() && discharged(h, pipe).getFluid() == Fluids.LAVA
            && discharged(h, pipe).getAmount() == 400, "water's old placement counter cannot supply new lava");
        stock.fill(new FluidStack(Fluids.LAVA, 600), FluidAction.EXECUTE);
        h.assertTrue(h.getBlockState(OUTPUT).is(Blocks.LAVA) && h.getBlockState(OUTPUT).getFluidState().isSource(),
            "only one actual bucket of the same discharged fluid places a source");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_finalFractionalSpillKeepsItsOvershootAfterPlacement", timeoutTicks = 40)
    public static void finalFractionalSpillKeepsItsOvershootAfterPlacement(GameTestHelper h) {
        var open = boundary(h); var stock = handler(open);
        var receipt = (com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement) stock;
        var water = new FluidStack(Fluids.WATER, 1);
        h.assertTrue(receipt.differentialcreate$discharge(water, 999.75, FluidAction.EXECUTE) == 999.75,
            "the first real fractional spill owns its outside placement credit");
        h.assertTrue(receipt.differentialcreate$discharge(water, .5, FluidAction.EXECUTE) == .5
            && h.getBlockState(OUTPUT).getFluidState().isSource() && stock.getFluidInTank(0).isEmpty(),
            "the final actual spill is fully reported and places a world bucket");
        var registries = h.getLevel().registryAccess(); var saved = open.serializeNBT(registries);
        h.assertTrue(saved.getDouble("DifferentialDischargeAmount") == .25,
            "a 1000.25-mB spill places 1000 mB and retains the 0.25-mB already outside");
        ((FluidTank) stock).readFromNBT(registries, saved);
        for (var action : FluidAction.values()) h.assertTrue(receipt.differentialcreate$discharge(water, .25, action) == 0
            && stock.drain(1000, FluidAction.SIMULATE).getAmount() == 1000
            && open.serializeNBT(registries).getDouble("DifferentialDischargeAmount") == .25,
            "an existing world source blocks another spill without spending or replaying its remainder");
        var state = h.getBlockState(OUTPUT);
        var bucket = ((net.minecraft.world.level.block.BucketPickup) state.getBlock()).pickupBlock(
            null, h.getLevel(), h.absolutePos(OUTPUT), state);
        h.assertTrue(bucket.is(net.minecraft.world.item.Items.WATER_BUCKET), "the first real source is collected as a bucket");
        h.assertTrue(receipt.differentialcreate$discharge(water, 999.75, FluidAction.EXECUTE) == 999.75
            && h.getBlockState(OUTPUT).getFluidState().isSource()
            && open.serializeNBT(registries).getDouble("DifferentialDischargeAmount") == 0,
            "only a new 999.75-mB spill combines with the true quarter-mB remainder to place the second bucket");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_finalIntegerSpillKeepsAllOfItsReportedVolume", timeoutTicks = 40)
    public static void finalIntegerSpillKeepsAllOfItsReportedVolume(GameTestHelper h) {
        var open = boundary(h); var stock = handler(open);
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 999), FluidAction.EXECUTE) == 999
            && stock.fill(new FluidStack(Fluids.WATER, 2), FluidAction.SIMULATE) == 2
            && h.getBlockState(OUTPUT).isAir(), "a simulation neither spends credit nor places a source");
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 2), FluidAction.EXECUTE) == 2
            && h.getBlockState(OUTPUT).getFluidState().isSource()
            && open.serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount") == 1,
            "integer receipt of 1001 mB accounts for the world bucket plus one already discharged mB");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_blockedMouthDoesNotSpendOrAdvanceDischarge", timeoutTicks = 20)
    public static void blockedMouthDoesNotSpendOrAdvanceDischarge(GameTestHelper h) {
        var pipe = boundary(h);
        var stock = handler(pipe);
        stock.fill(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE);
        h.setBlock(OUTPUT, Blocks.STONE);
        var registries = h.getLevel().registryAccess();
        var saved = pipe.serializeNBT(registries);
        for (var action : FluidAction.values())
            h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 900), action) == 0,
                "a blocked mouth accepts no liquid: " + action);
        h.assertTrue(pipe.serializeNBT(registries).equals(saved) && h.getBlockState(OUTPUT).is(Blocks.STONE),
            "rejected receipts leave the world and placement threshold unchanged");
        h.succeed();
    }

    private static FluidStack discharged(GameTestHelper h, OpenEndedPipe pipe) {
        var registries = h.getLevel().registryAccess();
        var tag = pipe.serializeNBT(registries);
        var fluid = FluidStack.parseOptional(registries, tag.getCompound("DifferentialDischarge"));
        return tag.contains("DifferentialDischargeAmount")
            ? fluid.copyWithAmount((int) Math.ceil(tag.getDouble("DifferentialDischargeAmount"))) : fluid;
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_fractionalDischargeStartsWithoutOneMb", timeoutTicks = 20)
    public static void fractionalDischargeStartsWithoutOneMb(GameTestHelper h) {
        var pipe = boundary(h);
        var stock = handler(pipe);
        var port = (com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement) stock;
        var water = new FluidStack(Fluids.WATER, 1);
        var registries = h.getLevel().registryAccess();
        var initial = pipe.serializeNBT(registries);
        for (int i = 0; i < 100; i++)
            h.assertTrue(port.differentialcreate$discharge(water, .025, FluidAction.SIMULATE) == .025,
                "fractional capacity queries do not round the incoming parcel");
        h.assertTrue(pipe.serializeNBT(registries).equals(initial) && h.getBlockState(OUTPUT).isAir(),
            "fractional queries cannot replay particles, spend stock or change the world");
        h.assertTrue(port.differentialcreate$discharge(water, .025, FluidAction.EXECUTE) == .025,
            "the first real 0.025 mB leaves immediately, without an integer threshold");
        h.assertTrue(stock.getFluidInTank(0).isEmpty() && stock.drain(1, FluidAction.EXECUTE).isEmpty(),
            "a sub-mB discharge is not duplicated as drainable inventory");
        h.assertTrue(pipe.serializeNBT(registries).getDouble("DifferentialDischargeAmount") == .025,
            "the source threshold records the precise actual sub-mB discharge");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_fractionalDischargeThresholdSurvivesReload", timeoutTicks = 20)
    public static void fractionalDischargeThresholdSurvivesReload(GameTestHelper h) {
        var pipe = boundary(h);
        var registries = h.getLevel().registryAccess();
        var water = new FluidStack(Fluids.WATER, 1);
        var port = (com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement) handler(pipe);
        h.assertTrue(port.differentialcreate$discharge(water, 999.75, FluidAction.EXECUTE) == 999.75
            && h.getBlockState(OUTPUT).isAir(), "less than one actual bucket does not place a source");
        var saved = pipe.serializeNBT(registries);
        var restored = OpenEndedPipe.fromNBT(saved, registries, h.absolutePos(PORT));
        restored.manageSource(h.getLevel(), null);
        port = (com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement) handler(restored);
        h.assertTrue(port.differentialcreate$discharge(water, .25, FluidAction.SIMULATE) == .25
            && restored.serializeNBT(registries).equals(saved), "reloading preserves the fractional threshold without replay");
        h.assertTrue(port.differentialcreate$discharge(water, .25, FluidAction.EXECUTE) == .25
            && h.getBlockState(OUTPUT).getFluidState().isSource() && handler(restored).getFluidInTank(0).isEmpty(),
            "exactly 999.75 plus 0.25 actual mB place one source without duplicate stock");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_actualWaterIntoFlowingLavaMakesCobblestone", timeoutTicks = 20)
    public static void actualWaterIntoFlowingLavaMakesCobblestone(GameTestHelper h) {
        collision(h, Fluids.WATER, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
            Blocks.COBBLESTONE.defaultBlockState());
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_actualLavaIntoWaterMakesStone", timeoutTicks = 20)
    public static void actualLavaIntoWaterMakesStone(GameTestHelper h) {
        collision(h, Fluids.LAVA, Blocks.WATER.defaultBlockState(), Blocks.STONE.defaultBlockState());
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_actualLavaIntoFlowingWaterMakesCobblestone", timeoutTicks = 20)
    public static void actualLavaIntoFlowingWaterMakesCobblestone(GameTestHelper h) {
        collision(h, Fluids.LAVA, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
            Blocks.COBBLESTONE.defaultBlockState());
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_actualBucketStillWaterlogsTarget", timeoutTicks = 20)
    public static void actualBucketStillWaterlogsTarget(GameTestHelper h) {
        var stock = handler(boundary(h));
        h.setBlock(OUTPUT, Blocks.OAK_SLAB);
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE) == 1000
            && !h.getBlockState(OUTPUT).getValue(BlockStateProperties.WATERLOGGED), "simulated water cannot waterlog a slab");
        h.assertTrue(stock.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 1000
            && h.getBlockState(OUTPUT).getValue(BlockStateProperties.WATERLOGGED) && stock.getFluidInTank(0).isEmpty(),
            "one actual bucket waterlogs through Create's normal world placement");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_realLavaContactReactsBeforeWaterBackpressure", timeoutTicks = 40)
    public static void stationaryLavaDoesNotReactWithoutACommittedDischarge(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.LAVA, 100, Direction.WEST, Direction.EAST);
            h.setBlock(PORT.west(), Blocks.WATER);
            h.setBlock(OUTPUT, Blocks.WATER);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(h.getBlockState(PORT.west()).is(Blocks.WATER) && h.getBlockState(OUTPUT).is(Blocks.WATER),
                "stationary interfaces do not fabricate reaction discharges or replacement air");
            h.assertTrue(Math.abs(owned(h) - 100) < 1e-7, "no actual discharge means no lava debit");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_dryTopMouthWaitsForRealLiquid", timeoutTicks = 40)
    public static void dryTopMouthWaitsForRealLiquid(GameTestHelper h) {
        actualPipe(h, Direction.Axis.Y);
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.WATER, 30, Direction.DOWN, Direction.UP);
            h.setBlock(PORT.above(), Blocks.LAVA);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(h.getBlockState(PORT.above()).is(Blocks.LAVA), "water below a dry top mouth cannot react remotely");
            h.assertTrue(Math.abs(owned(h) - 30) < 1e-7, "a dry mouth neither spends nor invents water");
            seed(h, Fluids.WATER, 100, Direction.DOWN, Direction.UP);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(30, () -> {
            h.assertTrue(h.getBlockState(PORT.above()).is(Blocks.LAVA), "mouth contact alone cannot invent an outward flow against the external column");
            h.assertTrue(Math.abs(owned(h) - 100) < 1e-7, "blocked bulk flow preserves all wet-mouth water");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_cachedLavaReactsInsteadOfBeingSuckedBack", timeoutTicks = 40)
    public static void cachedIntakeIsNotAReactionDischarge(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        h.setBlock(PORT.west(), Blocks.AIR);
        IFluidHandler[] nativeStock = {null};
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.LAVA, 100, Direction.WEST, Direction.EAST);
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
            var open = (OpenEndedPipe) ((IPipePressure) pipe.getConnection(Direction.WEST)).differentialcreate$source();
            nativeStock[0] = handler(open);
            ((FluidTank) nativeStock[0]).setFluid(new FluidStack(Fluids.LAVA, 9));
            h.setBlock(PORT.west(), Blocks.WATER);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(h.getBlockState(PORT.west()).is(Blocks.WATER), "an intake cache cannot replay an outward reaction");
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
            double actual = owned(h) + nativeStock[0].getFluidInTank(0).getAmount();
            for (var connection : pipe.interfaces.values()) {
                var source = ((IPipePressure)connection).differentialcreate$source();
                if (source instanceof OpenEndedPipe open)
                    actual += open.serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount");
            }
            h.assertTrue(Math.abs(actual - 109) < 1e-7, "all cached lava remains owned or accounts for a new real outward spill");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aFractionalActualDripCanReact", timeoutTicks = 40)
    public static void aFractionalActualDripCanReact(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.LAVA, 0, Direction.WEST, Direction.EAST);
            var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)));
            var pending = saved.buffers.computeIfAbsent(Direction.WEST,
                side -> new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer());
            pending.add(new FluidStack(Fluids.LAVA, 1), .25);
            pending.delivery = true;
            h.setBlock(PORT.west(), Blocks.WATER);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(h.getBlockState(PORT.west()).is(Blocks.STONE), "a real sub-mB drop reacts without waiting for an integer capability transfer");
            h.assertTrue(owned(h) == 0, "the reaction consumes the actual quarter-mB and leaves no phantom lava");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_subResolutionActualWaterAtTheMouthCanReact", timeoutTicks = 40)
    public static void blockedTinyMouthParcelIsPreservedUntilDischarge(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.WATER, .025, Direction.WEST, Direction.EAST);
            h.setBlock(PORT.west(), Blocks.LAVA);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(h.getBlockState(PORT.west()).is(Blocks.LAVA) && Math.abs(owned(h)-.025)<1e-12,
                "a blocked tiny parcel is preserved without inventing a discharge or replacing it with air");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_theFinalBucketDropCanPassADryPump", timeoutTicks = 100)
    public static void theFinalBucketDropCanPassADryPump(GameTestHelper h) { bucketThroughPump(h, true); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_oneBucketAboveATankPlacesOneSourceAfterDraining", timeoutTicks = 660)
    public static void oneBucketAboveATankPlacesOneSourceAfterDraining(GameTestHelper h) { bucketThroughPump(h, false); }

    @GameTest(template = TEMPLATE, batch = "open_pipe_anEmptyWaterPipeAcceptsLavaAfterReload", timeoutTicks = 240)
    public static void anEmptyWaterPipeAcceptsLavaAfterReload(GameTestHelper h) {
        switchWorldFluid(h, 8.348877145181177e-14, false);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aCompletedWaterReceiptDoesNotLockTheNextFluid", timeoutTicks = 240)
    public static void aCompletedWaterReceiptDoesNotLockTheNextFluid(GameTestHelper h) {
        switchWorldFluid(h, 8.348877145181177e-14, true);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_realFractionalWaterIsNotErasedToAdmitLava", timeoutTicks = 240)
    public static void realFractionalWaterIsNotErasedToAdmitLava(GameTestHelper h) {
        switchWorldFluid(h, .025, false);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_savedLavaBodyRoundoffDoesNotBlockWater", timeoutTicks = 240)
    public static void savedLavaBodyRoundoffDoesNotBlockWater(GameTestHelper h) {
        switchAfterLavaBody(h, 2.220446049250313e-16);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_realLavaBodyIsNotErasedToAdmitWater", timeoutTicks = 240)
    public static void realLavaBodyIsNotErasedToAdmitWater(GameTestHelper h) {
        switchAfterLavaBody(h, .025);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_waterAndLavaBucketsCanAlternateThroughADrainedPump", timeoutTicks = 1460)
    public static void waterAndLavaBucketsCanAlternateThroughADrainedPump(GameTestHelper h) {
        bucketThroughPump(h, false, 6);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_anEmptyNetworkWithSavedSpillCreditKeepsAllOfTheNextBucket", timeoutTicks = 660)
    public static void anEmptyNetworkWithSavedSpillCreditKeepsAllOfTheNextBucket(GameTestHelper h) {
        bucketThroughPump(h, false, 1, 374.59006376000457);
    }

    private static void switchAfterLavaBody(GameTestHelper h, double residual) {
        actualPipe(h, Direction.Axis.Y); h.setBlock(PORT.below(), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(PORT.above(), Blocks.AIR);
        boolean realLava = residual > 1e-10;
        h.runAfterDelay(20, () -> {
            var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)));
            saved.fluid = new FluidStack(Fluids.LAVA, 1); saved.outward.clear(); saved.buffers.clear();
            saved.adoptMacro(0, false, java.util.EnumSet.of(Direction.DOWN, Direction.UP), -.5);
            var parent = new net.minecraft.nbt.CompoundTag(); saved.write(parent, h.getLevel().registryAccess());
            var body = parent.getCompound("DCIdealFluid"); body.putDouble("MacroLiquid", residual);
            for (var side : java.util.List.of(Direction.DOWN, Direction.UP)) {
                var contents = new net.minecraft.nbt.ListTag(); contents.add(net.minecraft.nbt.DoubleTag.valueOf(0));
                contents.add(net.minecraft.nbt.DoubleTag.valueOf(residual / 100));
                var port = body.getCompound(side.getName()); port.put("Contents", contents);
            }
            saved.read(parent, h.getLevel().registryAccess());
            h.setBlock(PORT.above(), Blocks.WATER); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
            for (var connection : pipe.interfaces.values()) connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            double total = tank.getTankInventory().getFluidAmount() + columnStored(h, 1)
                + (h.getBlockState(PORT.above()).getFluidState().isSource() ? 1000 : 0);
            h.assertTrue(Math.abs(total - 1000 - (realLava ? residual : 0)) < 1e-7,
                "changing from lava body inventory to water preserves real fluid: " + total);
        });
        h.runAfterDelay(200, () -> {
            var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)));
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            if (realLava) {
                double retained = saved.volume() + tank.getTankInventory().getFluidAmount();
                h.assertTrue(saved.volume() == 0 || saved.fluid.getFluid() == Fluids.LAVA,
                    "the real body retains its actual lava identity while draining");
                h.assertTrue(tank.getTankInventory().isEmpty() || tank.getTankInventory().getFluid().getFluid() == Fluids.LAVA,
                    "only the actual lava can reach the receiving tank");
                for (var buffer : saved.buffers.values()) if (buffer.amount > 0) {
                    h.assertTrue(buffer.fluid.getFluid() == Fluids.LAVA, "the actual fractional receipt remains lava");
                    retained += buffer.amount;
                }
                h.assertTrue(Math.abs(retained - residual) < 1e-12
                    && h.getBlockState(PORT.above()).getFluidState().isSource(),
                    "a real 0.025-mB lava body may drain into owned receipts, but cannot disappear or turn into water");
            } else h.assertTrue(tank.getTankInventory().getFluidAmount() == 1000
                && tank.getTankInventory().getFluid().getFluid() == Fluids.WATER && columnStored(h, 1) == 0,
                "an empty lava pipe admits the complete next water bucket");
            h.succeed();
        });
    }

    private static void switchWorldFluid(GameTestHelper h, double residual, boolean liveReceipt) {
        actualPipe(h, Direction.Axis.Y); h.setBlock(PORT.below(), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(PORT.above(), Blocks.AIR);
        boolean realWater = residual > 1e-10;
        h.runAfterDelay(20, () -> {
            var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)));
            saved.fluid = new FluidStack(Fluids.WATER, 1); saved.outward.clear(); saved.buffers.clear();
            saved.adoptMacro(0, false, java.util.EnumSet.of(Direction.DOWN, Direction.UP), -.5);
            var buffer = new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer();
            buffer.add(saved.fluid, residual + (liveReceipt ? 1 : 0)); buffer.delivery = true;
            saved.buffers.put(Direction.DOWN, buffer);
            if (!liveReceipt) {
                var parent = new net.minecraft.nbt.CompoundTag(); saved.write(parent, h.getLevel().registryAccess());
                // Preserve the exact old saved residual even when new arithmetic
                // already canonicalises an empty buffer before serialisation.
                var port = parent.getCompound("DCIdealFluid").getCompound("down");
                port.putDouble("Owned", residual); port.put("Fluid", saved.fluid.save(h.getLevel().registryAccess()));
                port.putBoolean("Delivery", true);
                saved.read(parent, h.getLevel().registryAccess());
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(30, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            if (liveReceipt) h.assertTrue(tank.getTankInventory().drain(1, FluidAction.EXECUTE).getAmount() == 1,
                "the original actual one-mB receipt reached the real tank before changing fluid");
            h.setBlock(PORT.above(), Blocks.LAVA); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 30) return;
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
            for (var connection : pipe.interfaces.values()) connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
            double total = tank.getTankInventory().getFluidAmount() + columnStored(h, 1)
                + (h.getBlockState(PORT.above()).getFluidState().isSource() ? 1000 : 0);
            h.assertTrue(Math.abs(total - 1000 - (realWater ? residual : 0)) < 1e-7,
                "changing fluid conserves the real source, tank and fractional stock: " + total);
            h.assertTrue(Math.abs(singleColumnStock(h, Fluids.WATER) - (realWater ? residual : 0)) < 1e-7
                && Math.abs(singleColumnStock(h, Fluids.LAVA) - 1000) < 1e-7,
                "water and lava each retain their own real stock while the integer adapter changes fluid");
        });
        h.runAfterDelay(200, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            if (realWater) {
                h.assertTrue(Math.abs(singleColumnStock(h, Fluids.WATER) - residual) < 1e-10
                    && tank.getTankInventory().getFluidAmount() == 1000 && tank.getTankInventory().getFluid().getFluid() == Fluids.LAVA
                    && !h.getBlockState(PORT.above()).getFluidState().isSource(),
                    "the real 0.025-mB water remainder remains independently owned while the next lava bucket is actually delivered");
            } else h.assertTrue(tank.getTankInventory().getFluidAmount() == 1000
                && tank.getTankInventory().getFluid().getFluid() == Fluids.LAVA
                && columnStored(h, 1) < 1e-9 && !h.getBlockState(PORT.above()).getFluidState().isSource(),
                "an actually empty water pipe admits the complete lava bucket into the real tank");
            h.succeed();
        });
    }

    /** Independent typed inventory for the saved integer-receipt fixture. */
    private static double singleColumnStock(GameTestHelper h, net.minecraft.world.level.material.Fluid fluid) {
        var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)); var saved = FluidKinetics.state(pipe);
        double result = saved.volume(new FluidStack(fluid, 1));
        for (var buffer : saved.buffers.values()) for (var part : buffer.parcels())
            if (part.identity().getFluid().isSame(fluid)) result += part.amount();
        var seen = new java.util.IdentityHashMap<OpenEndedPipe, Boolean>();
        for (var connection : NativeFluidTestNetwork.connections(pipe)) {
            var c = (IPipePressure)connection;
            for (var source : new com.simibubi.create.content.fluids.FlowSource[]{c.differentialcreate$source(), c.differentialcreate$retainedSource()})
                if (source instanceof OpenEndedPipe open && seen.put(open, true) == null) {
                    var stock = handler(open).getFluidInTank(0); if (!stock.isEmpty() && stock.getFluid().isSame(fluid)) result += stock.getAmount();
                    var tag = open.serializeNBT(h.getLevel().registryAccess());
                    var spill = FluidStack.parseOptional(h.getLevel().registryAccess(), tag.getCompound("DifferentialDischarge"));
                    if (!spill.isEmpty() && spill.getFluid().isSame(fluid)) result += tag.getDouble("DifferentialDischargeAmount");
                }
        }
        com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
        var received = tank.getTankInventory().getFluid(); if (!received.isEmpty() && received.getFluid().isSame(fluid)) result += received.getAmount();
        var world = h.getBlockState(PORT.above()).getFluidState(); if (world.isSource() && world.getType().isSame(fluid)) result += 1000;
        return result;
    }

    private static void bucketThroughPump(GameTestHelper h, boolean savedTail) { bucketThroughPump(h, savedTail, 1); }

    private static void bucketThroughPump(GameTestHelper h, boolean savedTail, int cycles) {
        bucketThroughPump(h, savedTail, cycles, 0);
    }

    private static void bucketThroughPump(GameTestHelper h, boolean savedTail, int cycles, double outsideCredit) {
        actualPipe(h, Direction.Axis.X);
        h.setBlock(PORT, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(
            com.simibubi.create.content.fluids.pump.PumpBlock.FACING, Direction.WEST));
        h.setBlock(PORT.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(PORT.above().east(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity drive = h.getBlockEntity(PORT.above().east());
        // This west-facing motor and one cog produce positive pump rotation,
        // which discharges along the pump's west-facing arrow.
        drive.generatedSpeed.setValue(64);
        var elbow = PORT.east(2); var tankPos = elbow.above(); var inlet = elbow.above(2);
        h.setBlock(PORT.east(), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        var bend = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) bend = bend.setValue(
            com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), side == Direction.WEST || side == Direction.UP);
        h.setBlock(elbow, bend); h.setBlock(tankPos, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(inlet, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        encloseSource(h, PORT.west(), Direction.EAST); h.setBlock(PORT.west(), Blocks.AIR);
        encloseSource(h, inlet.above(), Direction.DOWN); h.setBlock(inlet.above(), Blocks.AIR);
        var cells = java.util.List.of(PORT, PORT.east(), elbow, inlet);
        int[] introduced = {0}, collected = {0};
        h.runAfterDelay(20, () -> {
            introduced[0] = 1;
            if (savedTail) {
                for (var pos : cells) {
                    var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                    saved.fluid = new FluidStack(Fluids.WATER, 1); saved.buffers.clear(); saved.outward.clear();
                    double amount = pos.equals(elbow) ? .045369753824465786 : 0;
                    var sides = pos.equals(elbow) ? java.util.EnumSet.of(Direction.WEST, Direction.UP)
                        : pos.equals(inlet) ? java.util.EnumSet.of(Direction.DOWN, Direction.UP) : java.util.EnumSet.of(Direction.WEST, Direction.EAST);
                    saved.adoptMacro(amount, pos.equals(PORT), sides, -.005 + .505 * amount / 100);
                }
                com.simibubi.create.content.fluids.pump.PumpBlockEntity pump = h.getBlockEntity(PORT);
                com.diliupengyun.differential_create.kinetics.ElasticKinetics.state(pump).physicalSpeed = 3.8680105412689;
                FluidKinetics.invalidate(h.getLevel()); FluidKinetics.capture(h.getLevel());
                var outlet = bucketOutlet(h); var registries = h.getLevel().registryAccess();
                var progress = outlet.serializeNBT(registries);
                progress.put("DifferentialDischarge", new FluidStack(Fluids.WATER, 1).save(registries));
                progress.putDouble("DifferentialDischargeAmount", 999.9546302461762);
                ((FluidTank) handler(outlet)).readFromNBT(registries, progress);
            } else {
                if (cycles > 1) {
                    // Start from the user's drained-lava state, including the
                    // saved body residue, then exercise repeated live changes.
                    var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(inlet)));
                    saved.fluid = new FluidStack(Fluids.LAVA, 1);
                    saved.adoptMacro(0, false, java.util.EnumSet.of(Direction.DOWN, Direction.UP), -.5);
                    var parent = new net.minecraft.nbt.CompoundTag(); saved.write(parent, h.getLevel().registryAccess());
                    parent.getCompound("DCIdealFluid").putDouble("MacroLiquid", 2.220446049250313e-16);
                    saved.read(parent, h.getLevel().registryAccess());
                    com.simibubi.create.content.fluids.pump.PumpBlockEntity pump = h.getBlockEntity(PORT);
                    com.diliupengyun.differential_create.kinetics.ElasticKinetics.state(pump).physicalSpeed = 11.787660794100777;
                }
                h.setBlock(inlet.above(), Blocks.WATER);
            }
            FluidKinetics.invalidate(h.getLevel());
            if (outsideCredit > 0) {
                com.simibubi.create.content.fluids.pump.PumpBlockEntity pump = h.getBlockEntity(PORT);
                com.diliupengyun.differential_create.kinetics.ElasticKinetics.state(pump).physicalSpeed = 23.158800410805053;
                FluidKinetics.capture(h.getLevel());
                var outlet = bucketOutlet(h); var registries = h.getLevel().registryAccess();
                var saved = outlet.serializeNBT(registries);
                saved.put("DifferentialDischarge", new FluidStack(Fluids.WATER, 1).save(registries));
                saved.putDouble("DifferentialDischargeAmount", outsideCredit);
                ((FluidTank) handler(outlet)).readFromNBT(registries, saved);
            }
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            double total = 0;
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
            total += tank.getTankInventory().getFluidAmount();
            for (var pos : cells) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)); var saved = FluidKinetics.state(pipe);
                total += saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
                for (var connection : pipe.interfaces.values()) {
                    // Native neighbour notifications can temporarily retain an
                    // open handler as previousSource between physics updates.
                    connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
                    var source = ((IPipePressure) connection).differentialcreate$source();
                    if (source instanceof OpenEndedPipe open) total += handler(open).getFluidInTank(0).getAmount();
                }
            }
            total += bucketOutlet(h).serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount");
            if (h.getBlockState(inlet.above()).getFluidState().isSource()) total += 1000;
            if (h.getBlockState(PORT.west()).getFluidState().isSource()) total += 1000;
            h.assertTrue(Math.abs(total + collected[0] * 1000 - introduced[0] * 1000 - outsideCredit) < 1e-6,
                "pipe, tank, native stock and collected world buckets conserve all supplied fluid: " + total);
        });
        for (int cycle = 0; cycle < cycles; cycle++) {
            int index = cycle;
            h.runAfterDelay(savedTail ? 80 : cycles == 1 ? 600 : 20 + 220 * (cycle + 1), () -> {
                if (!h.getBlockState(PORT.west()).getFluidState().isSource()) {
                    com.simibubi.create.content.fluids.pump.PumpBlockEntity pump = h.getBlockEntity(PORT);
                    com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
                    System.out.println("OPEN_PIPE_BUCKET_DIAGNOSTIC cycle=" + index + " pump_rpm="
                        + com.diliupengyun.differential_create.kinetics.NetworkDynamics.preciseSpeed(pump)
                        + " motor=" + h.getBlockState(PORT.above().east()) + " cog=" + h.getBlockState(PORT.above())
                        + " inlet=" + h.getBlockState(inlet.above()) + " tank=" + tank.getTankInventory().getFluid()
                        + " spill=" + bucketOutlet(h).serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount")
                        + " cells=" + cells.stream().map(pos -> {
                            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)); var state = FluidKinetics.state(pipe);
                            return pos + ": " + state.parcels() + ", flow=" + state.outward + ", buffers="
                                + state.buffers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().parcels()
                                    + "/delivery=" + e.getValue().delivery).toList()
                                + ", native=" + pipe.interfaces.entrySet().stream().map(e -> {
                                    var source = ((IPipePressure)e.getValue()).differentialcreate$source();
                                    return e.getKey() + ":" + (source instanceof OpenEndedPipe open
                                        ? handler(open).getFluidInTank(0) : source);
                                }).toList();
                        }).toList());
                }
                h.assertTrue(h.getBlockState(PORT.west()).getFluidState().isSource(),
                    "cumulative actual discharges, including any saved outside credit, place one source: cycle " + index);
                h.assertTrue(h.getBlockState(PORT.west()).getFluidState().getType() == (index % 2 == 0 ? Fluids.WATER : Fluids.LAVA),
                    "each world outlet bucket keeps the newly supplied fluid type: " + index);
                if (outsideCredit == 0) for (var pos : cells)
                    h.assertTrue(FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos))).volume() < 1e-9,
                        "the discharged bucket leaves no stranded water in the pipe or dry pump");
                else {
                    com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
                    double retained = tank.getTankInventory().getFluidAmount();
                    for (var pos : cells) {
                        var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                        retained += saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
                    }
                    h.assertTrue(retained > 0 && retained <= outsideCredit,
                        "a source completed using old outside credit blocks only the real remaining new water: " + retained);
                }
                if (index + 1 == cycles) { h.succeed(); return; }
                var output = h.getBlockState(PORT.west());
                var bucket = ((net.minecraft.world.level.block.BucketPickup) output.getBlock()).pickupBlock(
                    null, h.getLevel(), h.absolutePos(PORT.west()), output);
                h.assertTrue(bucket.is(index % 2 == 0 ? net.minecraft.world.item.Items.WATER_BUCKET : net.minecraft.world.item.Items.LAVA_BUCKET),
                    "the preceding world source is actually collected as its native bucket");
                collected[0]++;
                h.setBlock(inlet.above(), index % 2 == 0 ? Blocks.LAVA : Blocks.WATER); introduced[0]++;
                FluidKinetics.invalidate(h.getLevel());
            });
        }
    }

    private static OpenEndedPipe bucketOutlet(GameTestHelper h) {
        var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
        var connection = pipe.getConnection(Direction.WEST);
        connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
        return (OpenEndedPipe) ((IPipePressure) connection).differentialcreate$source();
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_aHigherLavaColumnOverflowsTheActualUpperMouth", timeoutTicks = 240)
    public static void aHigherLavaColumnOverflowsTheActualUpperMouth(GameTestHelper h) {
        actualPipe(h, Direction.Axis.Y);
        var right = PORT.south();
        for (var base : new BlockPos[] {PORT, right}) {
            var elbow = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
            for (var side : Direction.values()) elbow = elbow.setValue(
                com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side),
                side == Direction.UP || side == (base.equals(PORT) ? Direction.SOUTH : Direction.NORTH));
            h.setBlock(base, elbow);
        }
        var cells = java.util.List.of(PORT, right, PORT.above(), PORT.above(2), right.above(), right.above(2), right.above(3));
        for (var pos : cells) if (pos.getY() > PORT.getY())
            h.setBlock(pos, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(PORT.above(3), Blocks.AIR); h.setBlock(right.above(4), Blocks.AIR);
        double extra = 27.8746171745172, initial = 600 + extra;
        double[] discharged = {0}, peak = {0};
        h.runAfterDelay(20, () -> {
            for (var pos : cells) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                var saved = FluidKinetics.state(pipe); saved.fluid = new FluidStack(Fluids.LAVA, 1);
                saved.buffers.clear(); saved.outward.clear();
                saved.adoptMacro(pos.equals(right.above(3)) ? extra : 100, false,
                    pos.getY() == PORT.getY() ? java.util.EnumSet.of(Direction.UP, pos.equals(PORT) ? Direction.SOUTH : Direction.NORTH)
                        : java.util.EnumSet.of(Direction.DOWN, Direction.UP), pos.equals(right.above(3)) ? -.5 + extra / 100 : .5);
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            double owned = 0;
            for (var pos : cells) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)); var saved = FluidKinetics.state(pipe);
                owned += saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
                if (pos.equals(PORT.above(2))) peak[0] = Math.max(peak[0], saved.outward.getOrDefault(Direction.UP, 0d));
            }
            var outlet = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT.above(2)));
            var source = ((IPipePressure) outlet.getConnection(Direction.UP)).differentialcreate$source();
            if (source instanceof OpenEndedPipe end) {
                owned += handler(end).getFluidInTank(0).getAmount();
                discharged[0] = end.serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount");
            }
            h.assertTrue(Math.abs(owned + discharged[0] - initial) < 1e-6,
                "upper overflow spends exactly its real pipe inventory: " + owned + " + " + discharged[0]);
        });
        h.runAfterDelay(200, () -> {
            h.assertTrue(peak[0] > .01 && discharged[0] > 20,
                "a free upper mouth cannot add a fake half-metre head and block the higher column: peak=" + peak[0] + " discharged=" + discharged[0]);
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_pumpDrawsLavaFromTankIntoWater", timeoutTicks = 260)
    public static void pumpDrawsLavaFromTankIntoWater(GameTestHelper h) {
        tankAboveWetOutlet(h, 0, false);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_savedCoastingPumpDoesNotLetOutsideWaterLockLava", timeoutTicks = 260)
    public static void savedCoastingPumpDoesNotLetOutsideWaterLockLava(GameTestHelper h) {
        tankAboveWetOutlet(h, 16.407044374797657, false);
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_realOpenEndWaterIsPreservedAheadOfLavaTank", timeoutTicks = 260)
    public static void realOpenEndWaterIsPreservedAheadOfLavaTank(GameTestHelper h) {
        tankAboveWetOutlet(h, 0, true);
    }

    private static void tankAboveWetOutlet(GameTestHelper h, double rpm, boolean cachedWater) {
        actualPipe(h, Direction.Axis.X);
        h.setBlock(PORT, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(
            com.simibubi.create.content.fluids.pump.PumpBlock.FACING, Direction.WEST));
        var elbow = PORT.east(2); var tankPos = elbow.above(); var inlet = elbow.above(2);
        h.setBlock(PORT.east(), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        var bend = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) bend = bend.setValue(
            com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), side == Direction.WEST || side == Direction.UP);
        h.setBlock(elbow, bend); h.setBlock(tankPos, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(inlet, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        encloseSource(h, PORT.west(), Direction.EAST); h.setBlock(PORT.west(), Blocks.AIR);
        encloseSource(h, inlet.above(), Direction.DOWN); h.setBlock(inlet.above(), Blocks.AIR);
        var cells = java.util.List.of(PORT, PORT.east(), elbow, inlet);
        if (rpm == 0 && !cachedWater) {
            h.setBlock(PORT.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            h.setBlock(PORT.above().west(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity motor = h.getBlockEntity(PORT.above().west());
            motor.generatedSpeed.setValue(32);
        }
        h.runAfterDelay(20, () -> {
            for (var pos : cells) {
                var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                saved.fluid = new FluidStack(pos.equals(inlet) ? Fluids.LAVA : Fluids.WATER, 1);
                saved.buffers.clear(); saved.outward.clear();
                var sides = pos.equals(elbow) ? java.util.EnumSet.of(Direction.WEST, Direction.UP)
                    : pos.equals(inlet) ? java.util.EnumSet.of(Direction.DOWN, Direction.UP) : java.util.EnumSet.of(Direction.WEST, Direction.EAST);
                saved.adoptMacro(0, pos.equals(PORT), sides, -.5);
            }
            com.simibubi.create.content.fluids.pump.PumpBlockEntity pump = h.getBlockEntity(PORT);
            com.diliupengyun.differential_create.kinetics.ElasticKinetics.state(pump).physicalSpeed = rpm;
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
            tank.getTankInventory().setFluid(new FluidStack(Fluids.LAVA, 1000));
            FluidKinetics.invalidate(h.getLevel()); FluidKinetics.capture(h.getLevel());
            if (cachedWater) {
                ((FluidTank) handler(bucketOutlet(h))).setFluid(new FluidStack(Fluids.WATER, 975));
            }
            h.setBlock(PORT.west(), Blocks.WATER);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
            double total = tank.getTankInventory().getFluidAmount(), water = 0;
            for (var pos : cells) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)); var saved = FluidKinetics.state(pipe);
                total += saved.volume();
                for (var part : saved.parcels()) if (part.identity().getFluid() == Fluids.WATER) water += part.amount();
                for (var buffer : saved.buffers.values()) {
                    total += buffer.amount;
                    for (var part : buffer.parcels()) if (part.identity().getFluid() == Fluids.WATER) water += part.amount();
                }
                for (var connection : pipe.interfaces.values()) {
                    connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
                    var source = ((IPipePressure) connection).differentialcreate$source();
                    if (source instanceof OpenEndedPipe open) {
                        var stock = handler(open).getFluidInTank(0); total += stock.getAmount();
                        if (stock.getFluid() == Fluids.WATER) water += stock.getAmount();
                    }
                }
            }
            if (cachedWater) h.assertTrue(Math.abs(water - 975) < 1e-6 && Math.abs(total - 1975) < 1e-6,
                "coexisting queued water and lava retain their separate inventories: " + total);
            else {
                h.assertTrue(water == 0, "an idle low world-water mouth cannot steal the lava tank's empty network");
                if (h.getBlockState(PORT.west()).is(Blocks.WATER)) h.assertTrue(Math.abs(total - 1000) < 1e-6,
                    "all supplied lava is still owned before the first contact reaction: " + total);
                else h.assertTrue(h.getBlockState(PORT.west()).is(Blocks.STONE) && total > 0 && total < 1000,
                    "contact spends real lava, and its resulting solid lip retains the rest: " + total);
            }
        });
        h.runAfterDelay(220, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
            if (!cachedWater) h.assertTrue(tank.getTankInventory().getFluidAmount() < 1000
                && h.getBlockState(PORT.west()).is(Blocks.STONE),
                "the pump draws actual lava into the empty pipe and it reacts at the wet outlet");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_dryTankSideCannotLockAWorldWaterIntake", timeoutTicks = 240)
    public static void dryTankSideCannotLockAWorldWaterIntake(GameTestHelper h) {
        column(h, 10);
        var top = PORT.above(9); var tankPos = top.east();
        var tee = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) tee = tee.setValue(
            com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side),
            side == Direction.DOWN || side == Direction.UP || side == Direction.EAST);
        h.setBlock(top, tee); h.setBlock(tankPos, AllBlocks.FLUID_TANK.getDefaultState());
        h.runAfterDelay(20, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
            tank.getTankInventory().setFluid(new FluidStack(Fluids.LAVA, 1000));
            h.setBlock(PORT.above(10), Blocks.WATER); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
            double stock = columnStored(h, 10);
            h.assertTrue(tank.getTankInventory().getFluidAmount() == 1000 && tank.getTankInventory().getFluid().getFluid() == Fluids.LAVA,
                "a side port above the lava surface cannot supply or mix that lava");
            h.assertTrue(Math.abs(stock + (h.getBlockState(PORT.above(10)).getFluidState().isSource() ? 1000 : 0) - 1000) < 1e-6,
                "the independent world-water bucket remains fully accounted for: stock=" + stock
                    + " source=" + h.getBlockState(PORT.above(10)).getFluidState().isSource());
        });
        h.runAfterDelay(200, () -> {
            h.assertTrue(!h.getBlockState(PORT.above(10)).getFluidState().isSource() && Math.abs(columnStored(h, 10) - 1000) < 1e-6,
                "only a tank port actually wetted by its liquid can choose the source type");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "open_pipe_historicalLavaWithoutStockCannotReact", timeoutTicks = 40)
    public static void historicalLavaWithoutStockCannotReact(GameTestHelper h) {
        actualPipe(h, Direction.Axis.X);
        h.runAfterDelay(10, () -> {
            seed(h, Fluids.LAVA, 0, Direction.WEST, Direction.EAST);
            h.setBlock(PORT.west(), Blocks.WATER);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(!h.getBlockState(PORT.west()).is(Blocks.STONE) && !h.getBlockState(PORT.west()).is(Blocks.COBBLESTONE),
                "a past lava identity without any owned lava cannot make another stone block");
            h.succeed();
        });
    }

    private static void actualPipe(GameTestHelper h, Direction.Axis axis) {
        var bounds = h.getBounds();
        for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
            for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
                h.getLevel().setChunkForced(x, z, true);
        h.setBlock(PORT, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
        for (var side : Direction.values()) {
            var pos = PORT.relative(side);
            h.setBlock(pos, Blocks.STONE);
            if (side.getAxis() == axis) for (var border : Direction.values())
                if (!pos.relative(border).equals(PORT)) h.setBlock(pos.relative(border), Blocks.STONE);
        }
    }

    private static void encloseSource(GameTestHelper h, BlockPos source, Direction pipeSide) {
        for (var side : Direction.values()) if (side != pipeSide) h.setBlock(source.relative(side), Blocks.STONE);
    }

    private static void column(GameTestHelper h, int cells) {
        actualPipe(h, Direction.Axis.Y);
        for (int i = 1; i < cells; i++) {
            var pos = PORT.above(i);
            for (var side : Direction.values()) if (side.getAxis() != Direction.Axis.Y) h.setBlock(pos.relative(side), Blocks.STONE);
            h.setBlock(pos, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        }
        h.setBlock(PORT.below(), Blocks.STONE);
        encloseSource(h, PORT.above(cells), Direction.DOWN);
    }

    private static double columnStored(GameTestHelper h, int cells) {
        double stock = 0;
        for (int i = 0; i < cells; i++) {
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT.above(i)));
            var saved = FluidKinetics.state(pipe);
            stock += saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
            if (pipe.interfaces != null) for (var connection : pipe.interfaces.values()) {
                connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
                var source = ((IPipePressure) connection).differentialcreate$source();
                if (source instanceof OpenEndedPipe end) stock += handler(end).getFluidInTank(0).getAmount();
            }
        }
        return stock;
    }

    private static void worldColumn(GameTestHelper h, int cells, double initial, boolean takes) {
        column(h, cells);
        h.runAfterDelay(20, () -> {
            if (initial > 0) seed(h, Fluids.WATER, initial, Direction.DOWN, Direction.UP);
            h.setBlock(PORT.above(cells), Blocks.WATER); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            h.assertTrue(columnStored(h, cells) <= cells * 100 + 1e-7, "all owned liquid fits the actual pipe capacity");
            if (!takes) h.assertTrue(h.getBlockState(PORT.above(cells)).getFluidState().isSource()
                && Math.abs(columnStored(h, cells) - initial) < 1e-7, "insufficient free space prevents world extraction even with gravity");
        });
        h.runAfterDelay(140, () -> {
            if (takes) h.assertTrue(!h.getBlockState(PORT.above(cells)).getFluidState().isSource()
                && Math.abs(columnStored(h, cells) - 1000) < 1e-6, "exactly one bucket enters sufficient true free capacity");
            h.succeed();
        });
    }

    private static void worldTank(GameTestHelper h, int tankFree, boolean takes) {
        actualPipe(h, Direction.Axis.Y);
        h.setBlock(PORT.below(), AllBlocks.FLUID_TANK.getDefaultState());
        h.runAfterDelay(20, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.below());
            int initial = tank.getTankInventory().getCapacity() - tankFree;
            tank.getTankInventory().setFluid(new FluidStack(Fluids.WATER, initial));
            h.setBlock(PORT.above(), Blocks.WATER); FluidKinetics.invalidate(h.getLevel());
            h.runAfterDelay(120, () -> {
                double total = tank.getTankInventory().getFluidAmount() + columnStored(h, 1);
                h.assertTrue(Math.abs(total - initial - (takes ? 1000 : 0)) < 1e-6
                    && h.getBlockState(PORT.above()).getFluidState().isSource() != takes,
                    "world admission uses the tank's actual free space plus pipe space: " + total);
                h.succeed();
            });
        });
    }

    private static void worldPump(GameTestHelper h, boolean wet) {
        actualPipe(h, Direction.Axis.X);
        h.setBlock(PORT, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(
            com.simibubi.create.content.fluids.pump.PumpBlock.FACING, Direction.EAST));
        h.setBlock(PORT.east(), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(PORT.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(PORT.above().west(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(PORT.above().west(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity motor = h.getBlockEntity(PORT.above().west(2));
        // East-facing motor plus the actual meshing cog invert the control.
        motor.generatedSpeed.setValue(-64);
        h.runAfterDelay(20, () -> {
            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT));
            var saved = FluidKinetics.state(pipe); saved.fluid = new FluidStack(Fluids.WATER, 1);
            saved.adoptMacro(wet ? 50 : 0, true, java.util.EnumSet.of(Direction.WEST, Direction.EAST), 0);
            h.setBlock(PORT.west(), Blocks.WATER); FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(180, () -> {
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank = h.getBlockEntity(PORT.east());
            double total = tank.getTankInventory().getFluidAmount() + columnStored(h, 1);
            h.assertTrue(!h.getBlockState(PORT.west()).getFluidState().isSource()
                && Math.abs(total - (wet ? 1050 : 1000)) < 1e-6,
                "a running pump drives either real water or virtual air and admits exactly one capacity-reserved bucket: " + total);
            h.succeed();
        });
    }

    private static void seed(GameTestHelper h, Fluid fluid, double amount, Direction... sides) {
        var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)));
        saved.fluid = new FluidStack(fluid, 1);
        saved.outward.clear();
        double half = sides[0].getAxis() == Direction.Axis.Y ? .5 : .005;
        saved.adoptMacro(amount, false, java.util.EnumSet.copyOf(java.util.List.of(sides)), -half + 2 * half * amount / 100);
    }

    private static double owned(GameTestHelper h) {
        var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PORT)));
        return saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
    }

    private static void collision(GameTestHelper h, Fluid fluid, BlockState outside, BlockState result) {
        var stock = handler(boundary(h));
        h.setBlock(OUTPUT, outside);
        h.assertTrue(stock.fill(new FluidStack(fluid, 1), FluidAction.SIMULATE) == 1
            && h.getBlockState(OUTPUT).equals(outside) && stock.getFluidInTank(0).isEmpty(),
            "a collision query leaves the world and actual stock unchanged");
        h.assertTrue(stock.fill(new FluidStack(fluid, 1), FluidAction.EXECUTE) == 1
            && h.getBlockState(OUTPUT).equals(result) && stock.getFluidInTank(0).isEmpty(),
            "the native registered collision uses one real mB without first placing a source block");
        h.succeed();
    }

    private static OpenEndedPipe boundary(GameTestHelper h) {
        // Isolate the real Create capability from automatic network transfers
        // and world-fluid spreading while testing its simulation contract.
        h.setBlock(PORT, Blocks.COPPER_BLOCK);
        for (var side : Direction.values()) if (side != Direction.WEST) h.setBlock(OUTPUT.relative(side), Blocks.STONE);
        h.setBlock(OUTPUT, Blocks.AIR);
        var pipe = new OpenEndedPipe(new BlockFace(h.absolutePos(PORT), Direction.EAST));
        pipe.manageSource(h.getLevel(), null);
        return pipe;
    }

    private static IFluidHandler handler(OpenEndedPipe pipe) {
        return pipe.provideHandler().getCapability();
    }
}
