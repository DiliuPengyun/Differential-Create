package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllFluids;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Built world structures run ordinary server ticks and native machine processing.
 * Setup introduces explicitly counted stock; observations never advance the solver
 * or synthesize pressure, pump priming, completed recipes or fluid receipts. */
@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class FluidMachineIntegrationTests {
    private static final String TEMPLATE = "kineticphysicstests.physics_test";
    private static final BlockPos TOP = new BlockPos(7, 8, 7);

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void gravityFeedsSpoutAndFillsWaterBucket(GameTestHelper h) { spout(h, Fluids.WATER, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void gravityFeedsSpoutAndFillsLavaBucket(GameTestHelper h) { spout(h, Fluids.LAVA, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void reloadedSpoutKeepsItsStockAndCompletesFilling(GameTestHelper h) { spout(h, Fluids.WATER, true); }

    private static void spout(GameTestHelper h, Fluid fluid, boolean reload) {
        var s = new Scene(h, "spout_" + (fluid == Fluids.WATER ? "water" : "lava") + (reload ? "_reload" : ""));
        s.tank(TOP);
        s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        BlockPos spout = TOP.below(3), depot = TOP.below(5);
        s.machine(spout, AllBlocks.SPOUT.getDefaultState(), Direction.UP);
        s.depot(depot);
        h.runAfterDelay(20, () -> { s.fill(TOP, fluid, 1000); s.bucketOnDepot(depot); });
        if (reload) h.runAfterDelay(45, () -> s.reload(spout));
        s.watch();
        h.runAfterDelay(700, () -> {
            h.assertTrue(s.depotFluid(depot, fluid) == 1000, "native spout fills one bucket using the supplied liquid");
            h.assertTrue(s.liquid(fluid) < 1e-6, "no water is stranded before the final whole-bucket recipe");
            s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void itemDrainEmptiesWaterBucketIntoTank(GameTestHelper h) { itemDrain(h, Fluids.WATER, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void itemDrainEmptiesLavaBucketIntoTank(GameTestHelper h) { itemDrain(h, Fluids.LAVA, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void itemDrainAndSpoutTransferTheSameRealBucket(GameTestHelper h) { itemDrain(h, Fluids.WATER, true); }

    private static void itemDrain(GameTestHelper h, Fluid fluid, boolean toSpout) {
        var s = new Scene(h, toSpout ? "drain_to_spout" : "item_drain_" + fluid);
        BlockPos drain = TOP, sink = TOP.below(3), depot = sink.below(2);
        s.machine(drain, AllBlocks.ITEM_DRAIN.getDefaultState(), Direction.DOWN); s.drains.add(drain);
        // The native empty bucket stays on the drain rather than being ejected.
        h.setBlock(drain.east(), Blocks.STONE);
        s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        if (toSpout) { s.machine(sink, AllBlocks.SPOUT.getDefaultState(), Direction.UP); s.depot(depot); }
        else s.tank(sink);
        h.runAfterDelay(20, () -> {
            var inventory = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(drain), Direction.WEST);
            var item = new ItemStack(fluid == Fluids.WATER ? Items.WATER_BUCKET : Items.LAVA_BUCKET);
            h.assertTrue(inventory != null && inventory.insertItem(0, item, false).isEmpty(), "native item drain receives the bucket");
            s.introduced.merge(fluid, 1000d, Double::sum);
            if (toSpout) s.bucketOnDepot(depot);
        });
        s.watch();
        h.runAfterDelay(700, () -> {
            if (toSpout) h.assertTrue(s.depotFluid(depot, fluid) == 1000, "native drain and spout complete both actual recipes");
            else h.assertTrue(s.amount(sink, fluid) == 1000, "native drain delivers the whole bucket to the lower tank");
            s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 640)
    public static void gravityFillsBasinWithWater(GameTestHelper h) { fillBasin(h, Fluids.WATER); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 640)
    public static void gravityFillsBasinWithLava(GameTestHelper h) { fillBasin(h, Fluids.LAVA); }

    private static void fillBasin(GameTestHelper h, Fluid fluid) {
        var s = new Scene(h, "fill_basin_" + fluid);
        s.tank(TOP); s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        BlockPos basin = TOP.below(3);
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.UP);
        h.runAfterDelay(20, () -> s.fill(TOP, fluid, 1000)); s.watch();
        h.runAfterDelay(600, () -> { h.assertTrue(s.amount(basin, fluid) == 1000, "lower native basin receives the entire bucket"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1440)
    public static void filteredPipeSelectsLavaFromMixedBasin(GameTestHelper h) { filteredBasin(h, false, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1440)
    public static void separateFiltersExtractBothBasinFluids(GameTestHelper h) { filteredBasin(h, true, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1440)
    public static void basinOutputDoesNotHideMatchingInputFluid(GameTestHelper h) { filteredBasin(h, false, true); }

    private static void filteredBasin(GameTestHelper h, boolean both, boolean output) {
        var s = new Scene(h, output ? "basin_output_and_input" : both ? "basin_two_filters" : "basin_lava_filter");
        BlockPos basin = new BlockPos(8, 8, 8), left = basin.west(), right = basin.east();
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.DOWN);
        s.smart(left, Direction.Axis.X);
        // The elbow is below the filter; both exposed filter ends must lead to real inventory.
        s.pipe(left.west(), Direction.EAST, Direction.DOWN); s.glass(left.west().below(), Direction.Axis.Y);
        s.tank(left.west().below(2));
        if (both) {
            s.smart(right, Direction.Axis.X); s.pipe(right.east(), Direction.WEST, Direction.DOWN);
            s.glass(right.east().below(), Direction.Axis.Y); s.tank(right.east().below(2));
        }
        h.runAfterDelay(20, () -> {
            s.filter(left, Fluids.LAVA); if (both) s.filter(right, Fluids.WATER);
            BasinBlockEntity be = h.getBlockEntity(basin);
            if (output) {
                h.assertTrue(be.acceptOutputs(List.of(), List.of(new FluidStack(Fluids.WATER, 1000)), false), "native basin receives counted recipe output");
                s.introduced.put(Fluids.WATER, 1000d);
            } else s.fill(basin, Fluids.WATER, 1000);
            s.fill(basin, Fluids.LAVA, 1000);
        });
        s.watch();
        h.runAfterDelay(1400, () -> {
            h.assertTrue(s.amount(left.west().below(2), Fluids.LAVA) == 1000, "lava filter extracts the matching second fluid rather than stalling on water");
            if (both) h.assertTrue(s.amount(right.east().below(2), Fluids.WATER) == 1000, "independent water filter extracts only water");
            else h.assertTrue(s.amount(basin, Fluids.WATER) == 1000, "unselected basin water remains untouched");
            s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void changingSmartFilterReleasesPreviouslyBlockedWater(GameTestHelper h) {
        var s = new Scene(h, "change_smart_filter");
        BlockPos filter = TOP.below(), sink = TOP.below(3);
        s.tank(TOP); s.smart(filter, Direction.Axis.Y); s.glass(filter.below(), Direction.Axis.Y); s.tank(sink);
        h.runAfterDelay(20, () -> { s.filter(filter, Fluids.LAVA); s.fill(TOP, Fluids.WATER, 1000); }); s.watch();
        h.runAfterDelay(180, () -> {
            h.assertTrue(s.amount(TOP, Fluids.WATER) == 1000 && s.amount(sink, Fluids.WATER) == 0, "wrong filter does not take or replace source water");
            s.filter(filter, Fluids.WATER);
        });
        h.runAfterDelay(700, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) == 1000, "changed filter resumes actual gravity transfer"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void changingFilterOnWetPipePreservesItsActualWater(GameTestHelper h) {
        var s = new Scene(h, "change_wet_filter");
        BlockPos filter = TOP.below(), sink = TOP.below(3);
        s.tank(TOP); s.smart(filter, Direction.Axis.Y); s.glass(filter.below(), Direction.Axis.Y); s.tank(sink);
        h.runAfterDelay(20, () -> { s.filter(filter, Fluids.WATER); s.fill(TOP, Fluids.WATER, 2000); }); s.watch();
        h.runAfterDelay(35, () -> {
            h.assertTrue(FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(filter))).volume() > 0,
                "filter change occurs during actual wet transfer"); s.filter(filter, Fluids.LAVA);
        });
        h.runAfterDelay(220, () -> s.filter(filter, Fluids.WATER));
        h.runAfterDelay(800, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) == 2000, "restored matching filter drains all retained actual water"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void fullBasinKeepsTheSourceUntilRealRoomIsFreed(GameTestHelper h) {
        var s = new Scene(h, "full_basin");
        BlockPos basin = TOP.below(3);
        s.tank(TOP); s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.UP);
        h.runAfterDelay(20, () -> { s.fill(basin, Fluids.WATER, 1000); s.fill(basin, Fluids.LAVA, 1000); s.fill(TOP, Fluids.WATER, 1000); });
        s.watch();
        h.runAfterDelay(180, () -> {
            h.assertTrue(s.amount(basin, Fluids.WATER) == 1000 && s.amount(basin, Fluids.LAVA) == 1000 && s.amount(TOP, Fluids.WATER) >= 799,
                "full native basin keeps water in real pipe space plus its one-mB source rounding parcel: source=" + s.amount(TOP, Fluids.WATER));
            var taken = s.cap(basin).drain(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE);
            h.assertTrue(taken.getAmount() == 1000, "a real extraction frees receiver space"); s.collected.put(Fluids.WATER, 1000d);
        });
        h.runAfterDelay(800, () -> { h.assertTrue(s.amount(basin, Fluids.WATER) == 1000 && s.amount(TOP, Fluids.WATER) == 0,
            "remaining supply drains into newly freed basin space"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1040)
    public static void motorOperatedValveClosesAndReopensWithoutLosingLiquid(GameTestHelper h) {
        var s = new Scene(h, "motor_valve");
        BlockPos valve = TOP.below(), sink = TOP.below(3), motor = valve.west();
        s.tank(TOP); s.valve(valve, Direction.Axis.Y); s.glass(valve.below(), Direction.Axis.Y); s.tank(sink);
        s.motor(motor, Direction.EAST);
        double[] stopped = {0};
        h.runAfterDelay(20, () -> s.fill(TOP, Fluids.WATER, 8000)); s.watch();
        h.runAfterDelay(100, () -> {
            h.assertTrue(s.amount(sink, Fluids.WATER) == 0 && s.amount(TOP, Fluids.WATER) == 8000, "closed valve prevents gravity intake");
            s.speed(motor, 16);
        });
        h.runAfterDelay(150, () -> {
            h.assertTrue(h.getBlockState(valve).getValue(FluidValveBlock.ENABLED) && s.amount(sink, Fluids.WATER) > 0, "positive real motor rotation opens the valve");
            s.speed(motor, -16);
        });
        h.runAfterDelay(250, () -> {
            h.assertTrue(!h.getBlockState(valve).getValue(FluidValveBlock.ENABLED), "reverse rotation closes the native valve");
            stopped[0] = s.amount(TOP, Fluids.WATER);
        });
        h.runAfterDelay(350, () -> {
            h.assertTrue(s.amount(TOP, Fluids.WATER) == stopped[0], "closed valve stops new withdrawal after downstream liquid drains");
            s.speed(motor, 16);
        });
        h.runAfterDelay(1000, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) == 8000, "reopening resumes transfer of the remaining actual liquid"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1040)
    public static void closedValveExcludesUnreachableCapacityFromWorldIntake(GameTestHelper h) {
        var s = new Scene(h, "world_intake_valve");
        BlockPos inlet = TOP, valve = TOP.below(), sink = TOP.below(3), motor = valve.west();
        s.glass(inlet, Direction.Axis.Y); s.valve(valve, Direction.Axis.Y); s.glass(valve.below(), Direction.Axis.Y); s.tank(sink);
        s.motor(motor, Direction.EAST); s.worldCell(inlet.above(), Direction.DOWN);
        h.runAfterDelay(20, () -> s.worldFill(inlet.above(), Fluids.WATER)); s.watch();
        h.runAfterDelay(160, () -> {
            h.assertTrue(h.getBlockState(inlet.above()).getFluidState().isSource() && s.liquid(Fluids.WATER) == 0,
                "a closed valve cannot lend the inaccessible tank's capacity to whole-bucket extraction");
            s.speed(motor, 16);
        });
        h.runAfterDelay(1000, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) == 1000, "open valve admits and drains the capacity-reserved world bucket"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 740)
    public static void partiallyFilledReceiverDoesNotDeleteOrRejectTheNextBucket(GameTestHelper h) {
        var s = new Scene(h, "nearly_full_spout");
        BlockPos spout = TOP.below(3), depot = spout.below(2);
        s.tank(TOP); s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        s.machine(spout, AllBlocks.SPOUT.getDefaultState(), Direction.UP); s.depot(depot);
        h.runAfterDelay(20, () -> { s.fill(spout, Fluids.WATER, 999); s.fill(TOP, Fluids.WATER, 1001); s.bucketOnDepot(depot); });
        s.watch();
        h.runAfterDelay(700, () -> {
            h.assertTrue(s.depotFluid(depot, Fluids.WATER) == 1000 && s.amount(spout, Fluids.WATER) == 1000,
                "one-mB room is used, recipe frees the receiver, and the next bucket finishes refilling it");
            s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1040)
    public static void reversingPumpShaftMovesTankWaterBack(GameTestHelper h) {
        var s = new Scene(h, "pump_shaft_reverse");
        BlockPos pump = new BlockPos(8, 4, 8), source = pump.west(3), sink = pump.east(3), motor = pump.above().west();
        s.tank(source); s.tank(source.above()); s.tank(sink); s.tank(sink.above());
        s.glass(pump.west(), Direction.Axis.X); s.glass(pump.west(2), Direction.Axis.X);
        s.glass(pump.east(), Direction.Axis.X); s.glass(pump.east(2), Direction.Axis.X);
        s.pump(pump, Direction.EAST);
        h.setBlock(pump.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        s.motor(motor, Direction.EAST);
        int[] forward = {0};
        h.runAfterDelay(20, () -> { s.fill(source, Fluids.WATER, 12000); s.fill(sink, Fluids.WATER, 8000); s.speed(motor, -32); });
        s.watch();
        h.runAfterDelay(350, () -> {
            PumpBlockEntity be = h.getBlockEntity(pump);
            h.assertTrue(Math.abs(be.getSpeed()) > 0 && s.amount(sink, Fluids.WATER) > 8000, "actual motor and naturally primed pump transfer water forward");
            forward[0] = (int) s.amount(sink, Fluids.WATER);
            h.assertTrue(be.getSpeed() > 0, "native gearing produces the positive pump axis");
            s.speed(motor, 32);
        });
        h.runAfterDelay(1000, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) < forward[0] - 100, "reversing the real pump shaft reverses actual transfer"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void reloadingWetGlassPipeAndTankPreservesTheTransfer(GameTestHelper h) {
        var s = new Scene(h, "reload_wet_transfer");
        BlockPos sink = TOP.below(4);
        s.tank(TOP); for (int i = 1; i <= 3; i++) s.glass(TOP.below(i), Direction.Axis.Y); s.tank(sink);
        h.runAfterDelay(20, () -> s.fill(TOP, Fluids.WATER, 2000)); s.watch();
        h.runAfterDelay(35, () -> { s.reload(TOP.below(2)); s.reload(sink); });
        h.runAfterDelay(800, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) == 2000, "wet block-entity reload preserves all liquid and resumes drainage"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void hosePulleyDrainsOneFiniteWorldWaterSource(GameTestHelper h) { hose(h, Fluids.WATER, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void hosePulleyDrainsOneFiniteWorldLavaSource(GameTestHelper h) { hose(h, Fluids.LAVA, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void hosePulleyDepositsRealTankWaterIntoTheWorld(GameTestHelper h) { hose(h, Fluids.WATER, true); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 640)
    public static void unpoweredHoseCannotLiftWaterAboveItsActualEnd(GameTestHelper h) { dryHose(h, Fluids.WATER); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 640)
    public static void unpoweredHoseCannotLiftLavaAboveItsActualEnd(GameTestHelper h) { dryHose(h, Fluids.LAVA); }

    private static void dryHose(GameTestHelper h, Fluid fluid) {
        var s = new Scene(h, "hose_no_lift_" + fluid);
        BlockPos hose = new BlockPos(8, 7, 8), root = hose.below(3), elbow = hose.west();
        s.machine(hose, AllBlocks.HOSE_PULLEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), Direction.WEST);
        s.pipe(elbow, Direction.EAST, Direction.DOWN); s.glass(elbow.below(), Direction.Axis.Y); s.tank(elbow.below(2)); s.worldCell(root);
        h.runAfterDelay(20, () -> { s.lowerHose(hose, 3); s.worldFill(root, fluid); }); s.watch();
        h.runAfterDelay(600, () -> {
            h.assertTrue(h.getBlockState(root).getFluidState().isSource() && s.liquid(fluid) == 0,
                "the real hose end is below the receiver and cannot create a free uphill pump"); s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void honeyBottlePassesThroughNativeDrainAndSpout(GameTestHelper h) { bottleChain(h, new ItemStack(Items.HONEY_BOTTLE), new ItemStack(Items.GLASS_BOTTLE)); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void virtualTeaPassesThroughNativeDrainAndSpout(GameTestHelper h) { bottleChain(h, AllItems.BUILDERS_TEA.asStack(), new ItemStack(Items.GLASS_BOTTLE)); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void milkBucketPassesThroughNativeDrainAndSpout(GameTestHelper h) { bottleChain(h, new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.BUCKET)); }

    private static void bottleChain(GameTestHelper h, ItemStack input, ItemStack empty) {
        var s = new Scene(h, "container_chain_" + input.getItem());
        BlockPos spout = TOP.below(3), depot = spout.below(2);
        s.machine(TOP, AllBlocks.ITEM_DRAIN.getDefaultState(), Direction.DOWN); s.drains.add(TOP); h.setBlock(TOP.east(), Blocks.STONE);
        s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        s.machine(spout, AllBlocks.SPOUT.getDefaultState(), Direction.UP); s.depot(depot);
        FluidStack[] introduced = {FluidStack.EMPTY};
        h.runAfterDelay(20, () -> {
            introduced[0] = GenericItemEmptying.emptyItem(h.getLevel(), input.copy(), true).getFirst();
            h.assertTrue(!introduced[0].isEmpty(), "fixture uses an actual native emptying recipe");
            var inventory = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(TOP), Direction.WEST);
            h.assertTrue(inventory != null && inventory.insertItem(0, input.copy(), false).isEmpty(), "drain receives the actual filled container");
            s.introduced.put(introduced[0].getFluid(), (double) introduced[0].getAmount()); s.itemOnDepot(depot, empty.copy());
        }); s.watch();
        h.runAfterDelay(800, () -> {
            DepotBlockEntity be = h.getBlockEntity(depot); var inventory = be.getBehaviour(DepotBehaviour.TYPE).itemHandler;
            boolean restored = false;
            for (int i = 0; i < inventory.getSlots(); i++) restored |= ItemStack.isSameItemSameComponents(inventory.getStackInSlot(i), input);
            h.assertTrue(restored && s.liquid(introduced[0].getFluid()) < 1e-6, "actual native emptying and filling restore the original container"); s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1040)
    public static void pipeFedMixerConsumesExactlyOneBucketForDough(GameTestHelper h) {
        var s = new Scene(h, "pipe_fed_dough");
        BlockPos basin = new BlockPos(7, 4, 8), mixer = basin.above(2), source = basin.west().above(4), motor = mixer.east().above();
        s.tank(source); s.glass(source.below(), Direction.Axis.Y); s.glass(source.below(2), Direction.Axis.Y);
        s.pipe(source.below(3), Direction.UP, Direction.EAST); s.pipe(basin.above(), Direction.WEST, Direction.DOWN);
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.UP); s.doughBasins.add(basin);
        h.setBlock(mixer, AllBlocks.MECHANICAL_MIXER.getDefaultState());
        h.setBlock(mixer.east(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        s.motor(motor, Direction.DOWN);
        h.runAfterDelay(20, () -> {
            BasinBlockEntity be = h.getBlockEntity(basin);
            h.assertTrue(be.getInputInventory().insertItem(0, AllItems.WHEAT_FLOUR.asStack(), false).isEmpty(), "native basin receives the actual flour ingredient");
            s.fill(source, Fluids.WATER, 1000); s.speed(motor, 64);
        }); s.watch();
        h.runAfterDelay(1000, () -> {
            BasinBlockEntity be = h.getBlockEntity(basin);
            h.assertTrue(s.doughCount(basin) == 1 && be.getInputInventory().getStackInSlot(0).isEmpty(), "normal world ticks perform Create's real water-and-flour dough recipe");
            h.assertTrue(s.liquid(Fluids.WATER) < 1e-6, "the recipe consumes exactly the supplied bucket and leaves no unaccounted liquid"); s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 2440)
    public static void eightAlternatingBucketsSurviveValveAndFilterChanges(GameTestHelper h) { alternatingMachineChain(h, true, false); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 2440)
    public static void eightAlternatingBucketsSurviveWetMachineReloads(GameTestHelper h) { alternatingMachineChain(h, false, true); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 2440)
    public static void eightAlternatingBucketsSurviveCombinedOperations(GameTestHelper h) { alternatingMachineChain(h, true, true); }

    private static void alternatingMachineChain(GameTestHelper h, boolean changes, boolean reloads) {
        var s = new Scene(h, "eight_buckets_changes_" + changes + "_reloads_" + reloads);
        BlockPos filter = TOP.below(), valve = TOP.below(2), pipe = TOP.below(3), spout = TOP.below(4), depot = spout.below(2), motor = valve.west();
        s.tank(TOP); s.smart(filter, Direction.Axis.Y); s.valve(valve, Direction.Axis.Y); s.glass(pipe, Direction.Axis.Y);
        s.machine(spout, AllBlocks.SPOUT.getDefaultState(), Direction.UP); s.depot(depot); s.motor(motor, Direction.EAST);
        int[] phase = {0}, operations = {0};
        h.runAfterDelay(20, () -> { s.filter(filter, Fluids.WATER); s.fill(TOP, Fluids.WATER, 1000); s.bucketOnDepot(depot); s.speed(motor, 64); });
        s.watch();
        h.onEachTick(() -> {
            if (s.closed) return;
            if (h.getTick() <= 20) return;
            Fluid fluid = phase[0] % 2 == 0 ? Fluids.WATER : Fluids.LAVA;
            DepotBlockEntity be = h.getBlockEntity(depot); var inventory = be.getBehaviour(DepotBehaviour.TYPE).itemHandler;
            for (int i = 0; i < inventory.getSlots(); i++) if (s.itemFluid(inventory.getStackInSlot(i), fluid) == 1000) {
                var item = inventory.extractItem(i, 1, false);
                h.assertTrue(s.itemFluid(item, fluid) == 1000, "only a real filled output bucket advances the next fluid phase");
                s.collected.merge(fluid, 1000d, Double::sum);
                if (++phase[0] == 8) {
                    h.assertTrue(s.collected.get(Fluids.WATER) == 4000 && s.collected.get(Fluids.LAVA) == 4000,
                        "all eight buckets emerge from actual processing, without clearing or replacing residual liquid");
                    System.out.println("FLUID_STRUCTURE_OPERATIONS name=" + s.name + " count=" + operations[0] + " buckets=" + phase[0]); s.done(); return;
                }
                fluid = phase[0] % 2 == 0 ? Fluids.WATER : Fluids.LAVA;
                s.filter(filter, fluid); s.fill(TOP, fluid, 1000); s.bucketOnDepot(depot); break;
            }
            long cycle = h.getTick() % 200;
            if (changes && cycle % 10 == 0) {
                s.speed(motor, cycle >= 120 && cycle < 160 ? -64 : cycle < 80 ? 64 : 16);
                s.filter(filter, cycle >= 80 && cycle < 110 ? fluid == Fluids.WATER ? Fluids.LAVA : Fluids.WATER : fluid);
                operations[0] += 2;
            }
            if (reloads && cycle == 45) { s.reload(pipe); s.reload(spout); operations[0] += 2; }
        });
        h.runAfterDelay(2400, () -> h.assertTrue(phase[0] == 8, "operation sequence completes every actual output bucket: " + phase[0]));
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 1240)
    public static void threeCommunicatingColumnsKeepMirrorSymmetry(GameTestHelper h) {
        var s = new Scene(h, "three_communicating_columns");
        BlockPos centre = new BlockPos(8, 3, 8), left = centre.west(3), right = centre.east(3);
        for (int x = -3; x <= 3; x++) {
            if (x == -3) s.pipe(centre.east(x), Direction.UP, Direction.EAST);
            else if (x == 3) s.pipe(centre.east(x), Direction.UP, Direction.WEST);
            else if (x == 0) s.pipe(centre, Direction.WEST, Direction.EAST, Direction.UP);
            else s.glass(centre.east(x), Direction.Axis.X);
        }
        for (var base : List.of(left, centre, right)) for (int y = 1; y <= 4; y++) s.glass(base.above(y), Direction.Axis.Y);
        h.setBlock(left.above(5), Blocks.STONE); h.setBlock(right.above(5), Blocks.STONE);
        s.worldCell(centre.above(5), Direction.DOWN);
        h.runAfterDelay(20, () -> s.worldFill(centre.above(5), Fluids.WATER)); s.watch();
        h.runAfterDelay(500, () -> {
            h.assertTrue(h.getBlockState(centre.above(5)).getFluidState().isSource() && s.liquid(Fluids.WATER) == 0,
                "sealed columns conserve their incompressible air and cannot accept a bucket");
            h.setBlock(left.above(5), Blocks.AIR); h.setBlock(right.above(5), Blocks.AIR); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (s.closed) return;
            if (h.getTick() <= 20) return;
            for (int y = 0; y <= 4; y++) {
                double a = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(left.above(y)))).volume();
                double b = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(right.above(y)))).volume();
                h.assertTrue(Math.abs(a - b) < 1e-6, "mirror columns have equal actual volumes rather than traversal-order bias: " + y + " / " + a + " / " + b);
            }
        });
        h.runAfterDelay(1200, () -> {
            h.assertTrue(!h.getBlockState(centre.above(5)).getFluidState().isSource() && s.liquid(Fluids.WATER) > 900,
                "venting both caps admits the actual bucket with symmetric stock"); s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void waterFilledBasinStillReceivesLavaFromHigherTank(GameTestHelper h) { prefilledBasin(h, Fluids.WATER, Fluids.LAVA); }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void lavaFilledBasinStillReceivesWaterFromHigherTank(GameTestHelper h) { prefilledBasin(h, Fluids.LAVA, Fluids.WATER); }

    private static void prefilledBasin(GameTestHelper h, Fluid existing, Fluid incoming) {
        var s = new Scene(h, "prefilled_basin_" + existing + "_receives_" + incoming);
        BlockPos basin = TOP.below(3);
        s.tank(TOP); s.glass(TOP.below(), Direction.Axis.Y); s.glass(TOP.below(2), Direction.Axis.Y);
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.UP);
        h.runAfterDelay(20, () -> { s.fill(basin, existing, 500); s.fill(TOP, incoming, 1000); }); s.watch();
        h.runAfterDelay(800, () -> {
            h.assertTrue(s.amount(basin, existing) == 500 && s.amount(basin, incoming) == 1000 && s.amount(TOP, incoming) == 0,
                "the lower container's other fluid cannot lock the empty pipe against the actual higher supply"); s.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 2040)
    public static void changingBasinFilterWaitsForTheIncompatibleReceiverToEmpty(GameTestHelper h) {
        var s = new Scene(h, "mixed_basin_receiver_switch");
        BlockPos basin = new BlockPos(8, 8, 8), filter = basin.west(), elbow = filter.west(), sink = elbow.below(2);
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.WEST); s.smart(filter, Direction.Axis.X);
        s.pipe(elbow, Direction.EAST, Direction.DOWN); s.glass(elbow.below(), Direction.Axis.Y); s.tank(sink);
        h.runAfterDelay(20, () -> { s.filter(filter, Fluids.LAVA); s.fill(basin, Fluids.WATER, 1000); s.fill(basin, Fluids.LAVA, 1000); }); s.watch();
        h.runAfterDelay(800, () -> {
            h.assertTrue(s.amount(sink, Fluids.LAVA) == 1000, "first matching lava bucket completes before changing the filter"); s.filter(filter, Fluids.WATER);
        });
        h.runAfterDelay(1000, () -> {
            h.assertTrue(s.amount(sink, Fluids.LAVA) == 1000 && s.amount(sink, Fluids.WATER) == 0,
                "a receiver containing real lava cannot be replaced or mixed by switching an upstream filter");
            var taken = s.cap(sink).drain(new FluidStack(Fluids.LAVA, 1000), FluidAction.EXECUTE);
            h.assertTrue(taken.getAmount() == 1000, "actual extraction empties the incompatible receiver"); s.collected.put(Fluids.LAVA, 1000d);
        });
        h.runAfterDelay(2000, () -> { h.assertTrue(s.amount(sink, Fluids.WATER) == 1000, "waiting real water drains after the old lava is actually removed"); s.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_machines", timeoutTicks = 840)
    public static void twoTankFeedsShareOneBasinWithoutOverfillingOrMixing(GameTestHelper h) {
        var s = new Scene(h, "shared_basin_two_feeds");
        BlockPos basin = new BlockPos(8, 5, 8), west = basin.west(), east = basin.east();
        s.machine(basin, AllBlocks.BASIN.getDefaultState(), Direction.UP);
        for (var leg : List.of(west, east)) {
            s.pipe(leg, Direction.UP, leg.equals(west) ? Direction.EAST : Direction.WEST);
            s.glass(leg.above(), Direction.Axis.Y); s.glass(leg.above(2), Direction.Axis.Y); s.tank(leg.above(3));
        }
        h.runAfterDelay(20, () -> { s.fill(west.above(3), Fluids.WATER, 1000); s.fill(east.above(3), Fluids.LAVA, 1000); }); s.watch();
        h.runAfterDelay(800, () -> {
            h.assertTrue(s.amount(basin, Fluids.WATER) > 0 && s.amount(basin, Fluids.WATER) <= 1000
                && s.amount(basin, Fluids.LAVA) > 0 && s.amount(basin, Fluids.LAVA) <= 1000
                && s.amount(west.above(3), Fluids.WATER) == 0 && s.amount(east.above(3), Fluids.LAVA) == 0,
                "both native components feed distinct basin slots without overfilling; finite basin head may leave conserved real stock in either pipe"); s.done();
        });
    }

    private static void hose(GameTestHelper h, Fluid fluid, boolean deposit) {
        var s = new Scene(h, deposit ? "hose_deposit" : "hose_extract_" + fluid);
        BlockPos hose = new BlockPos(10, 7, 8), root = hose.below(3), elbow = hose.west(2);
        // Facing north provides a west pipe and an east shaft.
        s.machine(hose, AllBlocks.HOSE_PULLEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), Direction.WEST);
        if (deposit) {
            s.glass(hose.west(), Direction.Axis.X); s.pipe(elbow, Direction.EAST, Direction.UP); s.glass(elbow.above(), Direction.Axis.Y); s.tank(elbow.above(2));
        } else {
            // Start with owned air and connect the dry pump to the hose.
            s.pump(hose.west(), Direction.WEST);
            s.pipe(elbow, Direction.EAST, Direction.DOWN);
            s.glass(elbow.below(), Direction.Axis.Y); s.tank(elbow.below(2));
            h.setBlock(hose.west().above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            s.motor(hose.above(), Direction.WEST);
        }
        s.worldCell(root);
        h.runAfterDelay(20, () -> {
            s.lowerHose(hose, 3);
            if (deposit) s.fill(elbow.above(2), fluid, 1000);
            else { s.worldFill(root, fluid); s.speed(hose.above(), 64); }
            FluidKinetics.invalidate(h.getLevel());
        }); s.watch();
        h.runAfterDelay(800, () -> {
            if (deposit) h.assertTrue(h.getBlockState(root).getFluidState().isSource() && s.liquid(fluid) < 1e-6, "native hose deposits one whole source from actual tank stock");
            else h.assertTrue(!h.getBlockState(root).getFluidState().isSource() && s.amount(elbow.below(2), fluid) >= 999,
                "dry air pump lifts the actual finite hose bucket: root=" + h.getBlockState(root).getFluidState()
                    + ", sink=" + s.amount(elbow.below(2), fluid));
            s.done();
        });
    }

    private static final class Scene {
        final GameTestHelper h;
        final String name;
        final List<BlockPos> pipes = new ArrayList<>(), drains = new ArrayList<>(), depots = new ArrayList<>(), world = new ArrayList<>(), motors = new ArrayList<>(), doughBasins = new ArrayList<>();
        final Map<BlockPos, Direction> machines = new LinkedHashMap<>();
        final Map<Fluid, Double> introduced = new LinkedHashMap<>();
        final Map<Fluid, Double> collected = new LinkedHashMap<>();
        double maximumInventoryError;
        boolean closed;
        Scene(GameTestHelper h, String name) {
            this.h = h; this.name = name;
            var bounds = h.getBounds();
            for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
                for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++) h.getLevel().setChunkForced(x, z, true);
        }
        void machine(BlockPos pos, BlockState state, Direction access) { h.setBlock(pos, state); machines.put(pos, access); }
        void tank(BlockPos pos) { machine(pos, AllBlocks.FLUID_TANK.getDefaultState(), Direction.UP); }
        void glass(BlockPos pos, Direction.Axis axis) { h.setBlock(pos, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, axis)); pipes.add(pos); }
        void pipe(BlockPos pos, Direction... sides) {
            var block = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState(); var open = EnumSet.copyOf(List.of(sides));
            for (var side : Direction.values()) block = block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), open.contains(side));
            h.setBlock(pos, block); pipes.add(pos);
        }
        void smart(BlockPos pos, Direction.Axis axis) {
            var block = AllBlocks.SMART_FLUID_PIPE.getDefaultState().setValue(SmartFluidPipeBlock.FACE, axis == Direction.Axis.Y ? AttachFace.WALL : AttachFace.FLOOR)
                .setValue(SmartFluidPipeBlock.FACING, axis == Direction.Axis.Z ? Direction.NORTH : Direction.EAST);
            h.setBlock(pos, block); pipes.add(pos);
        }
        void filter(BlockPos pos, Fluid fluid) {
            SmartBlockEntity be = h.getBlockEntity(pos);
            be.getBehaviour(FilteringBehaviour.TYPE).setFilter(new ItemStack(fluid == Fluids.WATER ? Items.WATER_BUCKET : Items.LAVA_BUCKET));
        }
        void valve(BlockPos pos, Direction.Axis axis) {
            var block = AllBlocks.FLUID_VALVE.getDefaultState().setValue(FluidValveBlock.FACING, Direction.NORTH)
                .setValue(FluidValveBlock.AXIS_ALONG_FIRST_COORDINATE, axis == Direction.Axis.Y);
            h.assertTrue(FluidValveBlock.getPipeAxis(block) == axis, "fixture valve pipe axis is correct");
            h.setBlock(pos, block); pipes.add(pos);
        }
        void pump(BlockPos pos, Direction facing) { h.setBlock(pos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, facing)); pipes.add(pos); }
        void motor(BlockPos pos, Direction facing) {
            h.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, facing)); motors.add(pos); speed(pos, 0);
        }
        void speed(BlockPos pos, int speed) { CreativeMotorBlockEntity be = h.getBlockEntity(pos); be.generatedSpeed.setValue(speed); }
        IFluidHandler cap(BlockPos pos) {
            var cap = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, h.absolutePos(pos), machines.get(pos));
            h.assertTrue(cap != null, "native fluid capability exists at " + pos); return cap;
        }
        void fill(BlockPos pos, Fluid fluid, int amount) {
            h.assertTrue(cap(pos).fill(new FluidStack(fluid, amount), FluidAction.EXECUTE) == amount, "fixture receives only the counted introduced stock");
            introduced.merge(fluid, (double) amount, Double::sum); FluidKinetics.invalidate(h.getLevel());
        }
        void depot(BlockPos pos) { h.setBlock(pos, AllBlocks.DEPOT.getDefaultState()); depots.add(pos); }
        void bucketOnDepot(BlockPos pos) {
            itemOnDepot(pos, new ItemStack(Items.BUCKET));
        }
        void itemOnDepot(BlockPos pos, ItemStack item) {
            DepotBlockEntity be = h.getBlockEntity(pos);
            be.getBehaviour(DepotBehaviour.TYPE).setCenteredHeldItem(new TransportedItemStack(item));
        }
        double depotFluid(BlockPos pos, Fluid fluid) {
            DepotBlockEntity be = h.getBlockEntity(pos); var inv = be.getBehaviour(DepotBehaviour.TYPE).itemHandler;
            double amount = 0; for (int i = 0; i < inv.getSlots(); i++) amount += itemFluid(inv.getStackInSlot(i), fluid); return amount;
        }
        double itemFluid(ItemStack stack, Fluid fluid) {
            if (fluid.isSame(Fluids.WATER)) return stack.is(Items.WATER_BUCKET) ? 1000d * stack.getCount() : 0;
            if (fluid.isSame(Fluids.LAVA)) return stack.is(Items.LAVA_BUCKET) ? 1000d * stack.getCount() : 0;
            if (fluid.isSame(AllFluids.HONEY.get())) return stack.is(Items.HONEY_BOTTLE) ? 250d * stack.getCount() : 0;
            if (fluid.isSame(AllFluids.TEA.get())) return AllItems.BUILDERS_TEA.isIn(stack) ? 250d * stack.getCount() : 0;
            if (stack.is(Items.MILK_BUCKET)) {
                var milk = stack.getCapability(Capabilities.FluidHandler.ITEM);
                if (milk != null && milk.getFluidInTank(0).getFluid().isSame(fluid)) return milk.getFluidInTank(0).getAmount() * (double) stack.getCount();
            }
            return 0;
        }
        int doughCount(BlockPos pos) {
            BasinBlockEntity be = h.getBlockEntity(pos); var inventory = be.getOutputInventory();
            int count = 0; for (int i = 0; i < inventory.getSlots(); i++) if (AllItems.DOUGH.isIn(inventory.getStackInSlot(i))) count += inventory.getStackInSlot(i).getCount();
            return count;
        }
        void worldCell(BlockPos pos, Direction... connected) {
            world.add(pos); h.setBlock(pos, Blocks.AIR);
            for (var side : Direction.values()) if (side != Direction.UP && !List.of(connected).contains(side)) h.setBlock(pos.relative(side), Blocks.STONE);
        }
        void worldFill(BlockPos pos, Fluid fluid) {
            h.setBlock(pos, fluid == Fluids.WATER ? Blocks.WATER : Blocks.LAVA);
            introduced.merge(fluid, 1000d, Double::sum); FluidKinetics.invalidate(h.getLevel());
        }
        void reload(BlockPos pos) {
            BlockEntity old = h.getBlockEntity(pos); var tag = old.saveWithFullMetadata(h.getLevel().registryAccess());
            var level = h.getLevel(); BlockPos absolute = h.absolutePos(pos); var block = h.getBlockState(pos);
            level.removeBlockEntity(absolute);
            var replacement = BlockEntity.loadStatic(absolute, block, tag, level.registryAccess());
            h.assertTrue(replacement != null, "native block entity can be reloaded"); level.setBlockEntity(replacement);
            if (replacement instanceof SmartBlockEntity smart) smart.initialize(); FluidKinetics.invalidate(level);
        }
        void lowerHose(BlockPos pos, int offset) {
            var be = h.getBlockEntity(pos); var tag = be.saveWithoutMetadata(h.getLevel().registryAccess());
            tag.put("Offset", LerpedFloat.linear().startWithValue(offset).writeNBT()); be.loadWithComponents(tag, h.getLevel().registryAccess());
        }
        double amount(BlockPos pos, Fluid fluid) {
            var handler = cap(pos);
            // Hose getFluidInTank can preview a still-unclaimed world bucket.
            // Only its real internal tank belongs in the inventory audit.
            if (handler instanceof HosePulleyFluidHandler hose) handler = hose.getInternalTank();
            double value = 0; for (int i = 0; i < handler.getTanks(); i++) {
                var stock = handler.getFluidInTank(i); if (stock.getFluid().isSame(fluid)) value += stock.getAmount();
            }
            return value;
        }
        double liquid(Fluid fluid) {
            double value = 0;
            var controllers = new java.util.HashSet<BlockPos>();
            for (var pos : machines.keySet()) {
                if (h.getBlockEntity(pos) instanceof FluidTankBlockEntity tank && !controllers.add(tank.getController())) continue;
                value += amount(pos, fluid);
            }
            for (var pos : pipes) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                h.assertTrue(pipe != null, "placed pipe still exists at " + pos);
                var saved = FluidKinetics.state(pipe);
                h.assertTrue(Double.isFinite(saved.volume()) && saved.volume() >= 0, "real pipe volume stays finite");
                h.assertTrue(saved.outward.values().stream().allMatch(Double::isFinite), "real pipe motion stays finite");
                for (var parcel : saved.parcels()) if (!parcel.identity().isEmpty() && parcel.identity().getFluid().isSame(fluid)) value += parcel.amount();
                for (var buffer : saved.buffers.values()) for (var parcel : buffer.parcels())
                    if (parcel.identity().getFluid().isSame(fluid)) value += parcel.amount();
                if (pipe.interfaces != null) for (var connection : pipe.interfaces.values()) {
                    connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
                    var source = ((IPipePressure) connection).differentialcreate$source();
                    if (source instanceof OpenEndedPipe end) {
                        var stock = end.provideHandler().getCapability().getFluidInTank(0);
                        if (stock.getFluid().isSame(fluid)) value += stock.getAmount();
                    }
                }
            }
            return value;
        }
        double total(Fluid fluid) {
            double value = liquid(fluid) + collected.getOrDefault(fluid, 0d);
            for (var pos : drains) { ItemDrainBlockEntity be = h.getBlockEntity(pos); value += itemFluid(be.getHeldItemStack(), fluid); }
            for (var pos : depots) value += depotFluid(pos, fluid);
            for (var pos : world) { var stock = h.getBlockState(pos).getFluidState(); if (stock.isSource() && stock.getType().isSame(fluid)) value += 1000; }
            // Create's real dough recipe consumes exactly 1000 mB. Account
            // for that transformed water in the actual native output items.
            if (fluid.isSame(Fluids.WATER)) for (var pos : doughBasins) value += 1000d * doughCount(pos);
            for (var pos : pipes) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                if (pipe.interfaces != null) for (var connection : pipe.interfaces.values()) {
                    var source = ((IPipePressure) connection).differentialcreate$source();
                    if (source instanceof OpenEndedPipe end) {
                        var tag = end.serializeNBT(h.getLevel().registryAccess());
                        var identity = FluidStack.parseOptional(h.getLevel().registryAccess(), tag.getCompound("DifferentialDischarge"));
                        if (identity.getFluid().isSame(fluid)) value += tag.getDouble("DifferentialDischargeAmount");
                    }
                }
            }
            return value;
        }
        void watch() {
            h.onEachTick(() -> {
                if (closed) return;
                if (h.getTick() <= 20) return;
                for (var entry : introduced.entrySet()) {
                    double actual = total(entry.getKey());
                    maximumInventoryError = Math.max(maximumInventoryError, Math.abs(actual - entry.getValue()));
                    h.assertTrue(Math.abs(actual - entry.getValue()) < 1e-6,
                        name + " conserves actual " + entry.getKey() + " at tick " + h.getTick() + ": " + actual + " / " + entry.getValue());
                }
                if (Boolean.getBoolean("differentialcreate.structureAudit") && h.getTick() % 200 == 0) {
                    var stock = new LinkedHashMap<BlockPos, String>();
                    for (var pos : machines.keySet()) stock.put(pos, amount(pos, Fluids.WATER) + "W/" + amount(pos, Fluids.LAVA) + "L");
                    System.out.println("FLUID_STRUCTURE_SAMPLE name=" + name + " tick=" + h.getTick() + " stock=" + stock);
                    if (!motors.isEmpty() || name.equals("hose_deposit")) {
                        for (var pos : pipes) {
                            var be = h.getBlockEntity(pos); var saved = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                            System.out.println("FLUID_STRUCTURE_DETAIL name=" + name + " pos=" + pos + " volume=" + saved.volume()
                                + " buffers=" + saved.buffers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().amount + ":" + e.getValue().delivery).toList()
                                + " flow=" + saved.outward
                                + (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity k ? " rpm=" + k.getSpeed() + " block=" + k.getBlockState() : ""));
                        }
                        for (var pos : motors) { CreativeMotorBlockEntity be = h.getBlockEntity(pos);
                            System.out.println("FLUID_STRUCTURE_MOTOR name=" + name + " pos=" + pos + " setting=" + be.generatedSpeed.getValue() + " generated=" + be.getGeneratedSpeed() + " rpm=" + be.getSpeed()); }
                    }
                }
            });
        }
        void done() {
            if (closed) return;
            System.out.println("FLUID_STRUCTURE_PASS name=" + name + " tick=" + h.getTick() + " water=" + total(Fluids.WATER) + " lava=" + total(Fluids.LAVA)
                + " error=" + maximumInventoryError + " introduced=" + introduced + " collected=" + collected);
            closed = true;
            // Keep sequential acceptance scenes independent: the harness does
            // not automatically remove prior successful structures. Remove
            // only Create entities inside THIS owned template, using loaded
            // chunks; do not load or touch another test's distant structures.
            var bounds = h.getBounds(); var owned = new ArrayList<BlockPos>();
            for (int x = (int)Math.floor(bounds.minX) >> 4; x <= (int)Math.floor(bounds.maxX) >> 4; x++)
                for (int z = (int)Math.floor(bounds.minZ) >> 4; z <= (int)Math.floor(bounds.maxZ) >> 4; z++) {
                    var chunk = h.getLevel().getChunkSource().getChunkNow(x, z); if (chunk == null) continue;
                    for (var entry : chunk.getBlockEntities().entrySet())
                        if (entry.getValue() instanceof SmartBlockEntity && bounds.contains(net.minecraft.world.phys.Vec3.atCenterOf(entry.getKey())))
                            owned.add(entry.getKey());
                }
            for (var pos : owned) h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            FluidKinetics.invalidate(h.getLevel()); h.succeed();
        }
    }
}
