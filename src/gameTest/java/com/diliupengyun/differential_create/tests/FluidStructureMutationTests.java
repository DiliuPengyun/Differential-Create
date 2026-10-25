package com.diliupengyun.differential_create.tests;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;

/** Structural actions on actually running native worlds. Interaction times are
 * anchored to accepted air flow, the arriving liquid front, or wet delivery.
 * Breaking a block accounts ONLY its independently inspected destroyed stock;
 * wrench conversions use Create's real cache/load path and may not lose stock. */
final class FluidStructureMutationTests {
    static final List<String> OPERATIONS = List.of("edge_both", "edge_one", "break_middle", "break_run", "rapid_replace",
        "branch_blind", "branch_receiver", "branch_loop", "reroute_3d", "wrench_casing", "wrench_glass",
        "replace_smart", "replace_valve", "insert_pump", "remove_pump", "reverse_pump", "rotate_pump_axis", "remove_motor", "remove_cog");
    static final List<String> STATES = List.of("air", "front", "wet");
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static final BlockPos SOURCE = p(0, 8, 4), SINK = p(16, 0, 4), PUMP = p(3, 3, 4), TARGET = p(8, 3, 4);
    private static void initialise(NativeFluidTestNetwork n, BlockPos at) {
        if (n.entity(at) instanceof SmartBlockEntity smart) smart.initialize();
    }
    private static BlockState casing(EnumSet<Direction> sides) {
        var state = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), sides.contains(side));
        return state;
    }
    private static NativeFluidTestNetwork line(GameTestHelper h, String name) {
        var n = new NativeFluidTestNetwork(h, name); n.tank(SOURCE, 1, 4); n.tank(SINK, 1, 4);
        n.route(SOURCE, p(0, 3, 4), SINK.above(3)); n.pump(PUMP, Direction.EAST); n.build(); n.timeout(5998);
        return n;
    }
    static void mutation(GameTestHelper h, String operation, String state, Fluid fluid) {
        String name = "structure_" + operation + "_" + state + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = line(h, name); boolean[] acted = {false};
        n.after(20, () -> { if (!state.equals("air")) n.fill(SOURCE, fluid, 12000); n.speed(PUMP, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            n.audit(); phases(n);
            if (acted[0]) return;
            double volume = n.volume(TARGET), flow = Math.abs(((IPumpDynamics)n.entity(PUMP)).differentialcreate$flow());
            boolean ready = switch (state) {
                case "air" -> n.body() == 0 && flow > .01;
                case "front" -> volume > 1e-6 && volume < 100 - 1e-6;
                case "wet" -> volume >= 100 - 1e-6 && n.amount(SINK, fluid) >= 64;
                default -> throw new IllegalArgumentException(state);
            };
            if (!ready) return;
            acted[0] = true; int tick = (int)h.getTick(), before = n.amount(SINK, fluid);
            var original = n.entity(TARGET);
            System.out.println("FLUID_STRUCTURE_ACTION name=" + name + " tick=" + tick + " volume=" + volume
                + " accepted_pump_flow=" + flow + " received=" + before);
            change(n, operation, fluid, tick);
            if (state.equals("air")) n.fill(SOURCE, fluid, 12000);
            n.after(tick + 1500, () -> {
                n.expect(n.amount(SINK, fluid) >= before + 128, "restored or alternative structure makes new real native receipts");
                if (operation.equals("branch_receiver")) n.expect(n.amount(p(8, 0, 10), fluid) >= 64,
                    "the newly constructed vented branch actually delivers to its new tank");
                if (operation.startsWith("wrench") || operation.startsWith("break") || operation.equals("rapid_replace"))
                    n.expect(n.entity(TARGET) != original, "a different actual block entity was installed, not just an NBT edit");
                n.done("structural_action=" + operation + ", actual_before=" + state + ", new_receipts=" + (n.amount(SINK, fluid) - before));
            });
        });
    }
    private static void change(NativeFluidTestNetwork n, String operation, Fluid fluid, int tick) {
        var h = n.h; var at = TARGET;
        switch (operation) {
            case "edge_both", "edge_one" -> {
                if (operation.equals("edge_both")) n.edge(at, at.east(), false);
                else n.put(at, n.state(at).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.EAST), false));
                n.after(tick + 80, () -> n.expect(FluidKinetics.state(n.pipe(at)).outward.getOrDefault(Direction.EAST, 0d) == 0,
                    "the actually disconnected face accepts no flow, even with a wet queue"));
                n.after(tick + 120, () -> { n.edge(at, at.east(), true); });
            }
            case "break_middle", "break_run", "rapid_replace" -> {
                var removed = operation.equals("break_run") ? List.of(at.west(), at, at.east()) : List.of(at);
                Map<BlockPos, EnumSet<Direction>> old = new LinkedHashMap<>();
                for (var pos : removed) { old.put(pos, n.ports.get(pos).clone()); destroyPipe(n, pos); }
                n.after(tick + 80, () -> { for (var e : old.entrySet()) placePipe(n, e.getKey(), e.getValue()); });
                if (operation.equals("rapid_replace")) for (int i = 1; i <= 12; i++) {
                    int when = tick + 100 + i * 2;
                    n.after(when, () -> { destroyPipe(n, at); placePipe(n, at, old.get(at)); });
                }
            }
            case "branch_blind" -> {
                n.route(at, at.north(5), at.north(5).above(3)); n.installChangedPipes();
                n.after(tick + 100, () -> n.edge(at, at.north(), false));
                n.after(tick + 180, () -> n.edge(at, at.north(), true));
            }
            case "branch_receiver" -> {
                var sink = p(8, 0, 10); addTank(n, sink, 1, 1);
                n.route(at, p(8, 3, 10), sink); n.installChangedPipes();
            }
            case "branch_loop" -> {
                n.route(at.west(2), p(6, 3, 10), p(10, 3, 10), at.east(2)); n.installChangedPipes();
                n.after(tick + 100, () -> n.edge(at, at.east(), false));
                n.after(tick + 180, () -> n.edge(at, at.east(), true));
            }
            case "reroute_3d" -> {
                n.route(at.west(2), p(6, 6, 4), p(6, 6, 10), p(10, 6, 10), p(10, 3, 10), at.east(2));
                n.installChangedPipes();
                for (var pos : List.of(at.west(), at, at.east())) destroyPipe(n, pos);
            }
            case "wrench_casing", "wrench_glass" -> {
                if (operation.equals("wrench_glass")) convert(n, at,
                    AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
                wrench(n, at);
                n.expect(AllBlocks.FLUID_PIPE.has(n.state(at)), "the native wrench strips the casing");
                n.after(tick + 40, () -> {
                    wrench(n, at); n.expect(AllBlocks.GLASS_FLUID_PIPE.has(n.state(at)), "the native wrench makes a glass pipe");
                });
                n.after(tick + 80, () -> {
                    wrench(n, at); n.expect(AllBlocks.FLUID_PIPE.has(n.state(at)), "the native wrench returns glass to a normal pipe");
                    var player = FakePlayerFactory.getMinecraft(h.getLevel()); var abs = n.absolute(at);
                    AllBlocks.ENCASED_FLUID_PIPE.get().handleEncasing(n.state(at), h.getLevel(), abs,
                        new ItemStack(AllBlocks.COPPER_CASING.asItem()), player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
                    initialise(n, at);
                    n.expect(AllBlocks.ENCASED_FLUID_PIPE.has(n.state(at)), "the native encasing action restores the casing");
                });
            }
            case "replace_smart" -> {
                convert(n, at, AllBlocks.SMART_FLUID_PIPE.getDefaultState().setValue(SmartFluidPipeBlock.FACE, AttachFace.FLOOR)
                    .setValue(SmartFluidPipeBlock.FACING, Direction.EAST));
                filter(n, at, fluid.isSame(Fluids.WATER) ? Items.LAVA_BUCKET : Items.WATER_BUCKET);
                n.after(tick + 120, () -> filter(n, at, fluid.isSame(Fluids.WATER) ? Items.WATER_BUCKET : Items.LAVA_BUCKET));
            }
            case "replace_valve" -> {
                convert(n, at, AllBlocks.FLUID_VALVE.getDefaultState().setValue(FluidValveBlock.FACING, Direction.NORTH)
                    .setValue(FluidValveBlock.AXIS_ALONG_FIRST_COORDINATE, false).setValue(FluidValveBlock.ENABLED, true));
                var motor = at.above(); n.put(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
                initialise(n, motor); n.motors.put(at, motor); n.speed(at, 64);
                n.after(tick + 120, () -> { n.expect(!n.state(at).getValue(FluidValveBlock.ENABLED), "the native powered valve really closed"); n.speed(at, -64); });
                n.after(tick + 300, () -> n.expect(n.state(at).getValue(FluidValveBlock.ENABLED), "the native powered valve really reopened"));
            }
            case "insert_pump" -> {
                convert(n, at, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
                n.pump(at, Direction.EAST); installDrive(n, at, Direction.EAST); n.speed(at, 16);
            }
            case "remove_pump" -> n.replacePumpWithPipe(PUMP);
            case "reverse_pump" -> {
                wrench(n, PUMP); n.expect(n.state(PUMP).getValue(PumpBlock.FACING) == Direction.WEST, "real pump wrench reverses its arrow");
                n.after(tick + 120, () -> wrench(n, PUMP));
            }
            case "rotate_pump_axis" -> {
                n.put(PUMP.north(), Blocks.BARRIER.defaultBlockState()); n.put(PUMP.south(), Blocks.BARRIER.defaultBlockState());
                n.put(PUMP, n.state(PUMP).setValue(PumpBlock.FACING, Direction.NORTH));
                n.after(tick + 80, () -> n.expect(((IPumpDynamics)n.entity(PUMP)).differentialcreate$flow() == 0,
                    "the pump's genuinely new sealed axis has no accepted flow"));
                n.after(tick + 120, () -> n.put(PUMP, n.state(PUMP).setValue(PumpBlock.FACING, Direction.EAST)));
            }
            case "remove_motor", "remove_cog" -> {
                var pos = operation.equals("remove_motor") ? n.motors.get(PUMP) : PUMP.above(); var block = n.state(pos);
                h.getLevel().destroyBlock(n.absolute(pos), false); n.put(pos, Blocks.BARRIER.defaultBlockState());
                n.after(tick + 120, () -> { n.put(pos, block); initialise(n, pos); n.speed(PUMP, 16); });
            }
            default -> throw new IllegalArgumentException(operation);
        }
    }
    private static void filter(NativeFluidTestNetwork n, BlockPos at, net.minecraft.world.item.Item item) {
        ((SmartBlockEntity)n.entity(at)).getBehaviour(FilteringBehaviour.TYPE).setFilter(new ItemStack(item));
    }
    private static void installDrive(NativeFluidTestNetwork n, BlockPos pump, Direction facing) {
        var cog = facing.getAxis() == Direction.Axis.Y ? pump.east() : pump.above();
        n.put(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, facing.getAxis()));
        n.put(n.motors.get(pump), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,
            Direction.get(Direction.AxisDirection.NEGATIVE, facing.getAxis())));
        initialise(n, cog); initialise(n, pump); initialise(n, n.motors.get(pump));
    }
    static void addTank(NativeFluidTestNetwork n, BlockPos base, int width, int height) {
        n.tank(base, width, height);
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) for (int y = 0; y < height; y++) {
            var at = base.offset(x, y, z); n.put(at, AllBlocks.FLUID_TANK.getDefaultState()); initialise(n, at);
        }
    }
    private static void convert(NativeFluidTestNetwork n, BlockPos at, BlockState next) {
        var old = n.entity(at); FluidTransportBehaviour.cacheFlows(n.h.getLevel(), n.absolute(at));
        n.put(at, next); FluidTransportBehaviour.loadFlows(n.h.getLevel(), n.absolute(at)); initialise(n, at);
        n.expect(n.entity(at) != old, "native cache/load conversion creates a new actual entity");
    }
    static void wrench(NativeFluidTestNetwork n, BlockPos at) {
        var player = FakePlayerFactory.getMinecraft(n.h.getLevel()); var abs = n.absolute(at);
        var context = new UseOnContext(n.h.getLevel(), player, InteractionHand.MAIN_HAND, new ItemStack(AllItems.WRENCH.asItem()),
            new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        n.expect(((IWrenchable)n.state(at).getBlock()).onWrenched(n.state(at), context).consumesAction(), "native wrench action succeeds");
        initialise(n, at);
    }
    private static void placePipe(NativeFluidTestNetwork n, BlockPos at, EnumSet<Direction> sides) {
        n.ports.put(at, sides.clone()); n.put(at, casing(sides)); initialise(n, at);
        n.expect(n.volume(at) == 0, "newly placed replacement starts with air, never the destroyed block's old liquid");
    }
    static void destroyPipe(NativeFluidTestNetwork n, BlockPos at) {
        var pipe = n.pipe(at); n.expect(pipe != null, "only a real pipe is destroyed"); var saved = FluidKinetics.state(pipe);
        BiConsumer<FluidStack, Double> discarded = (kind, amount) -> {
            if (kind.isEmpty() || amount == 0) return;
            n.collected.merge(kind.getFluid(), amount, Double::sum); n.kind(kind).output += amount;
        };
        for (var part : saved.parcels()) discarded.accept(part.identity(), part.amount());
        for (var buffer : saved.buffers.values()) for (var part : buffer.parcels()) discarded.accept(part.identity(), part.amount());
        var ends = new IdentityHashMap<OpenEndedPipe, Boolean>();
        for (var connection : NativeFluidTestNetwork.connections(pipe))
            for (var source : new FlowSource[]{((IPipePressure)connection).differentialcreate$source(), ((IPipePressure)connection).differentialcreate$retainedSource()})
                if (source instanceof OpenEndedPipe end && ends.put(end, true) == null) {
                    var stock = end.provideHandler().getCapability().getFluidInTank(0); discarded.accept(stock, (double)stock.getAmount());
                    var tag = end.serializeNBT(n.h.getLevel().registryAccess());
                    discarded.accept(FluidStack.parseOptional(n.h.getLevel().registryAccess(), tag.getCompound("DifferentialDischarge")), tag.getDouble("DifferentialDischargeAmount"));
                }
        n.ports.remove(at); n.pumps.remove(at);
        n.expect(n.h.getLevel().destroyBlock(n.absolute(at), false), "native world really destroys the block");
        n.put(at, Blocks.BARRIER.defaultBlockState());
    }
    static void phases(NativeFluidTestNetwork n) {
        for (var at : n.ports.keySet()) if (n.channel(at)) {
            var saved = FluidKinetics.state(n.pipe(at));
            n.expect(saved.phases != null && Double.isFinite(saved.queueCapacity)
                && Math.abs(saved.phases.volume() - saved.queueCapacity) < 1e-7, "every actual cell stays full of conserved phases during mutation");
        }
    }
}
