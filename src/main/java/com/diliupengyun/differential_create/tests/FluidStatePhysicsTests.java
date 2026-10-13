package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class FluidStatePhysicsTests {
    private static final String TEMPLATE = "kineticphysicstests.physics_test";
    private static void pipe(GameTestHelper h, BlockPos pos, Direction... sides) {
        var block = AllBlocks.FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) block = block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), false);
        for (var side : sides) block = block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), true);
        h.setBlock(pos, block);
    }
    private static PipeHydraulics state(GameTestHelper h, BlockPos pos) {
        return FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
    }
    private static void forceChunks(GameTestHelper h) {
        var bounds = h.getBounds();
        for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
            for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++) h.getLevel().setChunkForced(x, z, true);
    }
    @GameTest(template = TEMPLATE, batch = "pump_dryLiquidPumpDoesNotPrimeRemoteWater", timeoutTicks = 140)
    public static void virtualAirPumpPrimesRemoteWater(GameTestHelper h) {
        forceChunks(h);
        BlockPos source = new BlockPos(4, 2, 8), corner = source.east(), pumpPos = corner.above(5).east(2);
        h.setBlock(source, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(source.above(), AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, corner, Direction.WEST, Direction.UP);
        for (int y = 1; y < 5; y++) pipe(h, corner.above(y), Direction.DOWN, Direction.UP);
        pipe(h, corner.above(5), Direction.DOWN, Direction.EAST);
        pipe(h, pumpPos.west(), Direction.WEST, Direction.EAST);
        h.setBlock(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        pipe(h, pumpPos.east(), Direction.WEST, Direction.EAST);
        // The test template has solid walls; explicitly provide an open outlet.
        h.setBlock(pumpPos.east(2), Blocks.AIR);
        h.setBlock(pumpPos.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(pumpPos.above().east(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        FluidTankBlockEntity tank = h.getBlockEntity(source);
        PumpBlockEntity pump = h.getBlockEntity(pumpPos);
        CreativeMotorBlockEntity motor = h.getBlockEntity(pumpPos.above().east());
        motor.generatedSpeed.setValue(0);
        h.runAfterDelay(10, () -> {
            tank.getTankInventory().fill(new FluidStack(Fluids.WATER, 12000), FluidAction.EXECUTE);
            motor.generatedSpeed.setValue(32);
        });
        h.runAfterDelay(110, () -> {
            h.assertTrue(Math.abs(pump.getSpeed()) > 50, "the dry pump rotor is running");
            h.assertTrue(state(h, corner).volume() > 0, "submerged tank port supplies real water by gravity");
            h.assertTrue(state(h, pumpPos).volume() > 0, "the initially air-filled pump primes the remote real water supply");
            h.assertTrue(((IPumpDynamics) pump).differentialcreate$head() > 0,
                "virtual air provides pump pressure before and during liquid priming");
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_closedPipeRetainsActualWater", timeoutTicks = 100)
    public static void closedPipeRetainsActualWater(GameTestHelper h) {
        forceChunks(h);
        var positions = new ArrayList<BlockPos>(); BlockPos centre = new BlockPos(8, 3, 8);
        for (int x = -2; x <= 2; x++) { positions.add(centre.east(x)); pipe(h, centre.east(x), Direction.WEST, Direction.EAST); }
        h.setBlock(centre.west(3), Blocks.STONE); h.setBlock(centre.east(3), Blocks.STONE);
        double[] total = {0};
        h.runAfterDelay(10, () -> {
            for (var pos : positions) { var saved = state(h, pos); saved.fluid = new FluidStack(Fluids.WATER, 1); }
            for (var pos : positions) {
                var owned = state(h, pos);
                owned.phases = null; owned.macroLiquid = Double.NaN;
                var water = pos.equals(centre) ? PipeContents.FULL : PipeContents.of(0, .7);
                owned.motion(Direction.WEST, PipeMotion.uniform(water, -2));
                owned.motion(Direction.EAST, PipeMotion.uniform(water, 2));
                owned.outward.put(Direction.WEST, -2d); owned.outward.put(Direction.EAST, 2d);
                owned.prepareMacro();
            }
            FluidKinetics.invalidate(h.getLevel());
            total[0] = positions.stream().mapToDouble(pos -> state(h, pos).volume()).sum();
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 10) return;
            for (var pos : positions) {
                var saved = state(h, pos);
                h.assertTrue(Double.isFinite(saved.volume()) && saved.volume() >= 0 && saved.volume() <= 100 + 1e-7,
                    "closed pipe inventory remains finite and within capacity");
                for (double flow : saved.outward.values()) h.assertTrue(Double.isFinite(flow), "closed pipe flow remains finite");
            }
            h.assertTrue(Math.abs(positions.stream().mapToDouble(pos -> state(h, pos).volume()).sum() - total[0]) < 1e-7,
                "closed pipe conserves all owned water");
        });
        h.runAfterDelay(70, () -> {
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_blindPipeCanFillWithoutAnAirPocket", timeoutTicks = 120)
    public static void blindPipeRetainsItsIncompressibleAir(GameTestHelper h) {
        forceChunks(h);
        BlockPos tankPos = new BlockPos(8, 4, 8), pipePos = tankPos.below();
        h.setBlock(tankPos, AllBlocks.FLUID_TANK.getDefaultState());
        // Create may turn a one-neighbour pipe into a straight pipe. Seal all
        // other faces so that this fixture cannot acquire an open-ended buffer.
        for (var side : Direction.values()) if (side != Direction.UP) h.setBlock(pipePos.relative(side), Blocks.STONE);
        pipe(h, pipePos, Direction.UP, Direction.EAST);
        FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
        h.runAfterDelay(10, () -> tank.getTankInventory().fill(new FluidStack(Fluids.WATER, 8000), FluidAction.EXECUTE));
        h.runAfterDelay(90, () -> {
            var saved = state(h, pipePos);
            h.assertTrue(saved.volume() == 0 && saved.airVolume() == 100,
                "a blind pipe cannot erase trapped air or counterflow it through the water inlet");
            double buffered = saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
            h.assertTrue(Math.abs(tank.getTankInventory().getFluidAmount() + saved.volume() + buffered - 8000) < 1e-7,
                "full blind pipe owns actual water extracted from the native tank: "
                    + tank.getTankInventory().getFluidAmount() + " / " + saved.volume() + " / " + buffered);
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_traceWaterFallsToSealedBottom", timeoutTicks = 90)
    public static void sealedTraceCannotExchangeWithAirByDensity(GameTestHelper h) {
        forceChunks(h);
        var bottom = new BlockPos(8, 2, 8);
        h.setBlock(bottom.below(), Blocks.STONE); h.setBlock(bottom.above(4), Blocks.STONE);
        for (int y=0;y<4;y++) h.setBlock(bottom.above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.AXIS,Direction.Axis.Y));
        double supported=52.59400544956748, trace=.052900526521377556;
        h.runAfterDelay(10, () -> {
            for(int y=0;y<4;y++) {
                var saved=state(h,bottom.above(y)); saved.fluid=new FluidStack(Fluids.WATER,1); saved.outward.clear();
                double amount=y==0?supported:y==3?trace:0;
                saved.adoptMacro(amount,false,java.util.EnumSet.of(Direction.UP,Direction.DOWN),-.5+amount/100);
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(60, () -> {
            double total=0;
            for(int y=0;y<4;y++) {
                var saved=state(h,bottom.above(y));total+=saved.volume()+saved.buffers.values().stream().mapToDouble(b->b.amount).sum();
                if(y>0) h.assertTrue(Math.abs(saved.volume() - (y==3 ? trace : 0)) < 1e-12,
                    "sealed phases cannot exchange by density without a through-flow");
            }
            h.assertTrue(Math.abs(total-supported-trace)<1e-8,"all real trace water reaches the supported bottom without deletion");
            h.assertTrue(Math.abs(state(h,bottom).volume()-supported)<1e-8,"the sealed lower parcel retains its original inventory");
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_horizontalOpenEndDrainsBelowItsCentre", timeoutTicks = 660)
    public static void oneOpenHorizontalEndCannotCreateReplacementAir(GameTestHelper h) {
        forceChunks(h);
        var first=new BlockPos(7,3,8);var end=first.east(2);
        h.setBlock(first.west(),Blocks.STONE);
        for(int x=0;x<3;x++) h.setBlock(first.east(x),AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.AXIS,Direction.Axis.X));
        h.runAfterDelay(10, () -> {
            for(int x=0;x<3;x++) {
                var saved=state(h,first.east(x));saved.fluid=new FluidStack(Fluids.WATER,1);saved.outward.clear();
                saved.adoptMacro(48,false,java.util.EnumSet.of(Direction.EAST,Direction.WEST),-.0002);
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        // Gravity through a centimetre-wide horizontal bore has a slow final
        // tail. Check substantial early drainage, then the complete low-water
        // observation used by the copied-world reproduction.
        h.runAfterDelay(290, () -> {
            double remaining=0;
            for(int x=0;x<3;x++) remaining+=state(h,first.east(x)).volume();
            h.assertTrue(Math.abs(remaining-144)<1e-8,"one open end without same-port exchange cannot drain a sealed run: "+remaining);
        });
        h.runAfterDelay(600, () -> {
            double stored=0,total=0;
            for(int x=0;x<3;x++) {
                var saved=state(h,first.east(x));stored+=saved.volume();
                total+=saved.volume()+saved.buffers.values().stream().mapToDouble(b->b.amount).sum();
            }
            var last=FluidPropagator.getPipe(h.getLevel(),h.absolutePos(end));
            var source=((IPipePressure)last.interfaces.get(Direction.EAST)).differentialcreate$source();
            var handler=source.provideHandler().getCapability();
            for(int n=0;n<handler.getTanks();n++) total+=handler.getFluidInTank(n).getAmount();
            var registries=h.getLevel().registryAccess();
            var tag=((com.simibubi.create.content.fluids.OpenEndedPipe)source).serializeNBT(registries);
            total+=tag.contains("DifferentialDischargeAmount") ? tag.getDouble("DifferentialDischargeAmount")
                : FluidStack.parseOptional(registries,tag.getCompound("DifferentialDischarge")).getAmount();
            h.assertTrue(Math.abs(stored-144)<1e-8,"retained real parcels are neither deleted nor replaced with air: "+stored);
            h.assertTrue(Math.abs(total-144)<1e-7,"actual stocks plus discharged water account for all horizontal drainage: "+total);
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_bothOpenHorizontalEndsDischargeEveryActualDrop", timeoutTicks = 1240)
    public static void twoFlatOpenEndsNeedADriveToFlushTheirQueues(GameTestHelper h) {
        forceChunks(h);
        int[] lengths={1,5,5}, rows={4,7,10}; double[] amounts={100,100,.05};
        for(int test=0;test<lengths.length;test++) {
            var first=new BlockPos(7,3,rows[test]);
            for(int x=-1;x<=lengths[test];x++) {
                var pos=first.east(x);
                for(var side:List.of(Direction.NORTH,Direction.SOUTH,Direction.UP,Direction.DOWN)) h.setBlock(pos.relative(side),Blocks.STONE);
                h.setBlock(pos,x<0||x==lengths[test] ? Blocks.AIR.defaultBlockState()
                    : AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            }
        }
        h.runAfterDelay(10, () -> {
            for(int test=0;test<lengths.length;test++) for(int x=0;x<lengths[test];x++) {
                var saved=state(h,new BlockPos(7+x,3,rows[test])); saved.fluid=new FluidStack(Fluids.WATER,1);
                saved.outward.clear();saved.buffers.clear();
                saved.adoptMacro(amounts[test],false,java.util.EnumSet.of(Direction.WEST,Direction.EAST),-.005+.01*amounts[test]/100);
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(20, () -> h.assertTrue(state(h,new BlockPos(7,3,rows[0])).volume()==100,
            "a flat full queue has no pressure or gravity difference that selects a through-flow"));
        h.runAfterDelay(1200, () -> {
            for(int test=0;test<lengths.length;test++) {
                var first=new BlockPos(7,3,rows[test]);
                for(int x=0;x<lengths[test];x++) {
                    var saved=state(h,first.east(x));
                    h.assertTrue(Math.abs(saved.volume()-amounts[test])<1e-10 && saved.buffers.values().stream().mapToDouble(b->b.amount).sum()==0,
                        "undriven flat queues retain all real water, including tiny parcels");
                }
                double spent=0;
                for(var side:List.of(Direction.WEST,Direction.EAST)) {
                    var pos=side==Direction.WEST?first:first.east(lengths[test]-1);
                    var transport=FluidPropagator.getPipe(h.getLevel(),h.absolutePos(pos));
                    var open=(com.simibubi.create.content.fluids.OpenEndedPipe)((IPipePressure)transport.getConnection(side)).differentialcreate$source();
                    h.assertTrue(open.provideHandler().getCapability().getFluidInTank(0).isEmpty(),
                        "already discharged water is not duplicated in the native handler");
                    spent+=open.serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount");
                }
                h.assertTrue(spent==0,"zero through-flow does not produce fictional world discharge: "+spent);
            }
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_floodedColumnsFillTransparentBottom", timeoutTicks = 280)
    public static void sealedColumnsDoNotEraseTheAirInTheirBottomPipe(GameTestHelper h) {
        forceChunks(h);
        BlockPos centre = new BlockPos(8, 3, 8), left = centre.west(), right = centre.east();
        h.setBlock(left.above(3), Blocks.STONE); h.setBlock(right.above(3), Blocks.STONE);
        pipe(h, left, Direction.EAST, Direction.UP); pipe(h, right, Direction.WEST, Direction.UP);
        h.setBlock(centre, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        for (var foot : java.util.List.of(left, right)) for (int y = 1; y <= 2; y++)
            h.setBlock(foot.above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        var positions = java.util.List.of(left, centre, right, left.above(), right.above(), left.above(2), right.above(2));
        double[] amounts = {100, 59.311833076944495, 100, 100, 100, 75.39, 78.87};
        double total = Arrays.stream(amounts).sum();
        h.runAfterDelay(10, () -> {
            for (int n = 0; n < positions.size(); n++) {
                var saved = state(h, positions.get(n)); saved.fluid = new FluidStack(Fluids.WATER, 1); saved.outward.clear();
                var sides = n == 0 ? java.util.EnumSet.of(Direction.EAST, Direction.UP)
                    : n == 2 ? java.util.EnumSet.of(Direction.WEST, Direction.UP)
                    : n == 1 ? java.util.EnumSet.of(Direction.WEST, Direction.EAST)
                    : java.util.EnumSet.of(Direction.DOWN, Direction.UP);
                double surface = n == 1 ? -.005 + amounts[n] / 100 * .01 : n < 3 ? .5 : -.5 + amounts[n] / 100;
                saved.adoptMacro(amounts[n], false, sides, surface);
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 10) return;
            double actual = 0;
            for (var pos : positions) {
                var saved = state(h, pos);
                h.assertTrue(saved.volume() >= 0 && saved.volume() <= 100 + 1e-7, "U-shaped pipe respects each real capacity");
                actual += saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
            }
            h.assertTrue(Math.abs(actual - total) < 1e-7, "U-shaped filling conserves actual pipe and owned water: " + actual);
        });
        h.runAfterDelay(240, () -> {
            for (int n=0;n<positions.size();n++) h.assertTrue(Math.abs(state(h,positions.get(n)).volume()-amounts[n])<1e-7,
                "a sealed U-tube cannot remove its trapped air or exchange phases by density");
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "fluid_boundary_smallWetReservoirDoesNotLockUpperFullPipes", timeoutTicks = 1640)
    public static void smallWetReservoirDoesNotLockUpperFullPipes(GameTestHelper h) {
        forceChunks(h);
        BlockPos foot = new BlockPos(8, 2, 8), feedPos = foot.above(8), reservoirPos = feedPos.east(3), receiverPos = foot.below();
        for (var pos : java.util.List.of(feedPos, reservoirPos, receiverPos)) h.setBlock(pos, AllBlocks.FLUID_TANK.getDefaultState());
        for (int y = 0; y < 8; y++) h.setBlock(foot.above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        FluidTankBlockEntity feed = h.getBlockEntity(feedPos), reservoir = h.getBlockEntity(reservoirPos), receiver = h.getBlockEntity(receiverPos);
        h.runAfterDelay(10, () -> {
            reservoir.getTankInventory().fill(new FluidStack(Fluids.WATER, 6100), FluidAction.EXECUTE);
            h.assertTrue(reservoir.getTankInventory().drain(309, FluidAction.EXECUTE).getAmount() == 309, "priming debits real reservoir water");
            feed.getTankInventory().fill(new FluidStack(Fluids.WATER, 4), FluidAction.EXECUTE);
            for (int y = 0; y < 8; y++) {
                double amount = y >= 5 ? 100 : 1;
                var saved = state(h, foot.above(y)); saved.fluid = new FluidStack(Fluids.WATER, 1); saved.outward.clear();
                saved.adoptMacro(amount, false, java.util.EnumSet.of(Direction.UP, Direction.DOWN), -.5 + amount / 100);
            }
            FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 10) return;
            // This real reservoir transfer keeps the source wet but nearly
            // empty. It cannot supply an unlimited pressurised through-flow.
            int wanted = Math.max(0, 4 - feed.getTankInventory().getFluidAmount());
            if (wanted > 0) {
                var water = reservoir.getTankInventory().drain(wanted, FluidAction.EXECUTE);
                if (!water.isEmpty()) h.assertTrue(feed.getTankInventory().fill(water, FluidAction.EXECUTE) == water.getAmount(), "all reserved replenishment reaches the small feed tank");
            }
            double total = reservoir.getTankInventory().getFluidAmount() + feed.getTankInventory().getFluidAmount() + receiver.getTankInventory().getFluidAmount();
            for (int y = 0; y < 8; y++) {
                var saved = state(h, foot.above(y));
                h.assertTrue(saved.volume() >= 0 && saved.volume() <= 100 + 1e-7, "draining upper cells respect real inventory and capacity");
                total += saved.volume() + saved.buffers.values().stream().mapToDouble(b -> b.amount).sum();
            }
            h.assertTrue(Math.abs(total - 6100) < 1e-7, "continuous small replenishment conserves all real tanks, pipes and owned buffers: " + total);
        });
        h.runAfterDelay(1600, () -> {
            for (int y = 5; y < 8; y++) h.assertTrue(state(h, foot.above(y)).volume() < 20,
                "a nearly empty wet tank does not permanently lock a full upper pipe: " + y + " = " + state(h, foot.above(y)).volume());
            h.assertTrue(receiver.getTankInventory().getFluidAmount() > 250, "the original upper water drains into the actual lower tank");
            h.succeed();
        });
    }
    @GameTest(template = TEMPLATE, batch = "pump_macroInventorySurvivesRenderingAndNbt", timeoutTicks = 20)
    public static void macroInventorySurvivesRenderingAndNbt(GameTestHelper h) {
        var saved = new PipeHydraulics(); saved.fluid = new FluidStack(Fluids.WATER, 1);
        saved.adoptMacro(67, false,
            java.util.EnumSet.of(Direction.DOWN, Direction.UP, Direction.EAST), .037);
        var tag = new CompoundTag(); saved.write(tag, h.getLevel().registryAccess());
        var loaded = new PipeHydraulics(); loaded.read(tag, h.getLevel().registryAccess());
        h.assertTrue(loaded.volume() == 67 && tag.getCompound("DCIdealFluid").getInt("MacroVersion") == 2,
            "macro NBT retains authoritative inventory independently of its rendering");
        for (var side : Direction.values()) h.assertTrue(Arrays.equals(saved.content(side).data(), loaded.content(side).data()),
            "modern NBT restores the approximate water presentation");
        tag.getCompound("DCIdealFluid").remove("MacroVersion");
        var compatible = new PipeHydraulics(); compatible.read(tag, h.getLevel().registryAccess());
        h.assertTrue(Math.abs(compatible.volume() - 67) < 1e-10,
            "legacy liquid fields also encode the same water amount");
        h.succeed();
    }
    @GameTest(template = TEMPLATE, batch = "pump_legacyAirIsDiscardedWithoutChangingWaterAndMotion", timeoutTicks = 30)
    public static void legacyAirIsDiscardedWithoutChangingWaterAndMotion(GameTestHelper h) {
        pipe(h, new BlockPos(8, 3, 8), Direction.WEST, Direction.EAST);
        h.runAfterDelay(5, () -> {
            var saved = new PipeHydraulics(); saved.fluid = new FluidStack(Fluids.WATER, 1);
            saved.motion(Direction.EAST, new PipeMotion(PipeContents.of(.1, .3, .7, .9), new double[]{3, -2}));
            saved.buffers.computeIfAbsent(Direction.WEST, side -> new PipeHydraulics.Buffer()).add(saved.fluid, .625);
            var tag = new CompoundTag(); saved.write(tag, h.getLevel().registryAccess());
            CompoundTag retired = tag.getCompound("DCIdealFluid");
            retired.remove("MotionVersion"); retired.putInt("AirVersion", 1);
            // Retired gas fields, including malformed values, are irrelevant to
            // liquid migration and must not be parsed into the runtime state.
            retired.putDouble("MacroAirMass", Double.NaN); retired.putDouble("MacroAirEnergy", Double.POSITIVE_INFINITY);
            retired.putString("CoreAir", "retired"); retired.getCompound("east").putString("Air", "retired");
            var loaded = new PipeHydraulics(); loaded.read(tag, h.getLevel().registryAccess());
            h.assertTrue(Arrays.equals(saved.content(Direction.EAST).data(), loaded.content(Direction.EAST).data())
                && Arrays.equals(saved.motion(Direction.EAST).flow(), loaded.motion(Direction.EAST).flow()), "NBT preserves each liquid plug's position and signed velocity");
            h.assertTrue(loaded.buffers.get(Direction.WEST).amount == .625, "migration retains owned fractional port inventory");
            var clean = new CompoundTag(); loaded.write(clean, h.getLevel().registryAccess());
            CompoundTag current = clean.getCompound("DCIdealFluid");
            h.assertTrue(current.getInt("MotionVersion") == 1 && !current.contains("AirVersion")
                && !current.contains("CoreAir") && !current.contains("MacroAirMass") && !current.contains("MacroAirEnergy")
                && !current.getCompound("east").contains("Air"), "rewriting a legacy save drops all retired ambient-air state");
            retired.remove("AirVersion");
            var legacy = new PipeHydraulics(); legacy.read(tag, h.getLevel().registryAccess());
            h.assertTrue(legacy.volume() == saved.volume(), "migration never adds or deletes real water");
            h.assertTrue(legacy.motion(Direction.EAST).flow()[0] == 0, "older liquid saves retain their recorded port flow");
            saved.adoptMacro(67, false, java.util.EnumSet.of(Direction.DOWN, Direction.UP, Direction.EAST), .037);
            var macroTag = new CompoundTag(); saved.write(macroTag, h.getLevel().registryAccess());
            CompoundTag oldMacro = macroTag.getCompound("DCIdealFluid"); oldMacro.putInt("MacroVersion", 1);
            oldMacro.putDouble("MacroAirMass", .00003); oldMacro.putDouble("MacroAirEnergy", 2300000);
            var migrated = new PipeHydraulics(); migrated.read(macroTag, h.getLevel().registryAccess());
            h.assertTrue(migrated.volume() == 67 && migrated.buffers.get(Direction.WEST).amount == .625,
                "MacroVersion=1 ignores compressed air and retains all actual water");
            for (var side : Direction.values()) h.assertTrue(Arrays.equals(saved.content(side).data(), migrated.content(side).data()),
                "old macro saves retain their rendered fluid geometry");
            h.succeed();
        });
    }
}
