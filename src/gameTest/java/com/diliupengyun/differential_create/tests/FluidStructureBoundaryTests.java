package com.diliupengyun.differential_create.tests;

import java.util.List;
import java.util.IdentityHashMap;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Identity, multiblock and retained-world-cache mutations, with native inventory
 * execution and independent identities rather than totals adjusted to pass. */
final class FluidStructureBoundaryTests {
    static final List<String> TANKS = List.of("extend_height", "extend_width", "split_controller", "split_member", "replace_receiver");
    static final List<String> ENDPOINTS = List.of("world_to_tank", "world_to_pipe", "seal_cached_world", "close_cached_face", "close_cached_reload",
        "last_fraction_to_pipe", "last_fraction_close_reload", "close_cached_drain", "last_fraction_closed_drain");
    static final List<String> OUTFLOWS = List.of("close_face", "close_reload", "seal_mouth");
    static final List<String> SOURCES = List.of("close_face", "remove_and_seal");
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static FluidStack tagged(String name, int amount) {
        var stack = new FluidStack(Fluids.WATER, amount); var tag = new CompoundTag(); tag.putString("structure_identity", name);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); return stack;
    }
    static void merge(GameTestHelper h, String identity, boolean reverseOrder) {
        String name = "structure_merge_" + identity + "_" + (reverseOrder ? "right_first" : "left_first");
        var n = new NativeFluidTestNetwork(h, name); var a = p(0, 8, 4); var b = p(0, 8, 12);
        var ra = p(16, 0, 4); var rb = p(16, 0, 12); var pa = p(3, 3, 4); var pb = p(3, 3, 12);
        for (var source : List.of(a, b)) n.tank(source, 1, 4);
        for (var sink : List.of(ra, rb)) n.tank(sink, 1, 4);
        n.route(a, p(0, 3, 4), ra.above(3)); n.route(b, p(0, 3, 12), rb.above(3));
        n.pump(pa, Direction.EAST); n.pump(pb, Direction.EAST); n.build(); n.timeout(5998);
        var fa = identity.equals("components") ? tagged("left", 12000) : new FluidStack(Fluids.WATER, 12000);
        var fb = identity.equals("components") ? tagged("right", 12000) : new FluidStack(identity.equals("different") ? Fluids.LAVA : Fluids.WATER, 12000);
        boolean[] acted = {false}; int[] phase = {0}; int[] newReceipts = {0, 0};
        n.after(20, () -> { n.fill(a, fa); n.fill(b, fb); n.speed(pa, 16); n.speed(pb, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0] || n.amount(ra, fa.getFluid()) < 64 || n.amount(rb, fb.getFluid()) < 64) return;
            acted[0] = true; int tick = (int)h.getTick();
            var left = p(8, 3, 4); var right = p(8, 3, 12);
            // Add an actual air-filled bridge first, then connect its two ends
            // in both construction orders. All pre-existing liquid stays put.
            n.route(left.south(), right.north()); n.installChangedPipes();
            n.ports.get(left.south()).add(Direction.NORTH); n.ports.get(right.north()).add(Direction.SOUTH);
            n.installChangedPipes();
            var first = reverseOrder ? right : left; var second = reverseOrder ? left : right;
            n.ports.get(first).add(reverseOrder ? Direction.NORTH : Direction.SOUTH); n.installChangedPipes();
            n.after(tick + 2, () -> { n.ports.get(second).add(reverseOrder ? Direction.SOUTH : Direction.NORTH); n.installChangedPipes(); phase[0] = 1; });
            n.after(tick + 160, () -> {
                n.expect(phase[0] == 1, "both actual previously wet networks were joined");
                n.edge(left, left.south(), false); n.edge(right, right.north(), false);
                takeTank(n, ra); takeTank(n, rb);
                n.expect(n.tankEntity(ra).getTankInventory().isEmpty() && n.tankEntity(rb).getTankInventory().isEmpty(),
                    "actual integer execution empties both receivers, including their real data components");
                n.expect(n.tankEntity(a).getTankInventory().getFluidAmount() < 31000 && n.tankEntity(b).getTankInventory().getFluidAmount() < 31000,
                    "there is genuine native room for counted replenishment after splitting");
                n.fill(a, fa.copyWithAmount(1000)); n.fill(b, fb.copyWithAmount(1000));
                phase[0] = 2;
            });
            // A single-fluid native tank must be genuinely emptied between
            // different identities. Model those explicit player extractions;
            // they cannot unblock the old sub-mB plug with an empty receiver.
            for (int k = 0; k < 20; k++) n.after(tick + 220 + 40 * k, () -> {
                newReceipts[0] += takeTank(n, ra); newReceipts[1] += takeTank(n, rb);
            });
            n.after(tick + 1500, () -> {
                n.expect(phase[0] == 2 && newReceipts[0] + n.tankEntity(ra).getTankInventory().getFluidAmount() >= 64
                    && newReceipts[1] + n.tankEntity(rb).getTankInventory().getFluidAmount() >= 64,
                    "after wet merge and real split both components make new native receipts");
                n.done("wet_merge=" + identity + ", actual_join_order=" + reverseOrder + ", split_and_resupply=true, native_receiver_extractions=20, new_left="
                    + newReceipts[0] + ", new_right=" + newReceipts[1]);
            });
        });
    }
    private static int takeTank(NativeFluidTestNetwork n, BlockPos at) {
        var actual = n.tankEntity(at).getTankInventory().drain(Integer.MAX_VALUE,
            net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        if (!actual.isEmpty()) { n.collected.merge(actual.getFluid(), (double)actual.getAmount(), Double::sum); n.kind(actual).output += actual.getAmount(); }
        return actual.getAmount();
    }
    static void sharedWorldRoom(GameTestHelper h, Fluid fluid) {
        String name = "structure_shared_world_room_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var sink = p(8, 1, 8);
        var first = p(5, 3, 8); var second = p(11, 3, 8);
        n.tank(sink, 1, 1);
        n.route(first, p(5, 1, 8), sink); n.route(second, p(11, 1, 8), sink);
        n.port(first, Direction.UP); n.port(second, Direction.UP); n.build(); n.timeout(3998);
        n.put(second, n.state(second).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.UP), false));
        boolean[] opened = {false}, extracted = {false};
        n.after(20, () -> {
            n.fill(sink, fluid, 7499); n.worldFill(first.above(), fluid, Direction.DOWN); n.worldFill(second.above(), fluid, Direction.DOWN);
        });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (opened[0] && !extracted[0]) n.expect(h.getLevel().getFluidState(n.absolute(second.above())).isSource(),
                "two separate components cannot use the same shared native room to take two whole buckets");
            if (opened[0] || h.getLevel().getFluidState(n.absolute(first.above())).isSource()) return;
            opened[0] = true; int tick = (int)h.getTick();
            // Each physical circuit has 500 mB room. Their receiver has only
            // 501 mB left: enough for one bucket, never two simultaneous caches.
            n.put(second, n.state(second).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.UP), true));
            n.after(tick + 400, () -> {
                n.expect(n.amount(sink, fluid) > 7600, "the first actual bucket made real native receipts");
                n.expect(n.removeTank(sink, fluid, 1000) == 1000, "the player extraction creates actual shared room for the second bucket");
                extracted[0] = true;
            });
            n.after(tick + 2200, () -> {
                n.expect(!h.getLevel().getFluidState(n.absolute(second.above())).isSource(), "the reopened circuit resumes when real receiving room becomes available");
                n.expect(n.amount(sink, fluid) > 7600, "both circuits actually deliver to the shared receiver after extraction");
                n.done("separate_world_inlets=2, pipe_room_each=500, initial_shared_native_room=501, actual_open_then_native_extraction=true");
            });
        });
    }
    static void source(GameTestHelper h, String operation, Fluid fluid) {
        String name = "structure_source_" + operation + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 8, 4); var sink = p(16, 0, 4); var pump = p(3, 3, 4);
        var inlet = source.below(); n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, p(0, 3, 4), sink.above(3));
        n.pump(pump, Direction.EAST); n.build(); n.timeout(5998); boolean[] acted = {false};
        n.after(20, () -> { n.fill(source, fluid, 12000); n.speed(pump, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0] || n.amount(sink, fluid) < 64) return;
            var buffer = FluidKinetics.state(n.pipe(inlet)).buffers.get(Direction.UP);
            if (buffer == null || buffer.delivery || !(buffer.amount > 1e-6 && buffer.amount < 1 - 1e-6)) return;
            acted[0] = true; int tick = (int)h.getTick(), before = n.amount(sink, fluid), untouched = n.amount(source, fluid);
            double owned = buffer.amount;
            if (operation.equals("close_face")) n.put(inlet, n.state(inlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.UP), false));
            else {
                n.expect(n.removeTank(source, fluid, Integer.MAX_VALUE) == untouched, "all remaining native source liquid is actually extracted before removal");
                n.tanks.removeIf(t -> t.base().equals(source));
                n.ports.remove(source);
                for (int y = 0; y < 4; y++) { n.tankBlocks.remove(source.above(y)); n.put(source.above(y), Blocks.BARRIER.defaultBlockState()); }
            }
            n.after(tick + 20, () -> n.reload(inlet));
            n.after(tick + 900, () -> {
                var tail = FluidKinetics.state(n.pipe(inlet)).buffers.get(Direction.UP);
                n.expect(tail == null || tail.amount < 1e-7, "already extracted native source fraction enters physical cells while its old interface remains closed");
                if (operation.equals("close_face")) n.expect(n.amount(source, fluid) == untouched,
                    "the virtual owned-stock inlet cannot extract any fresh native source liquid through the closed face");
                else n.expect(n.state(source).is(Blocks.BARRIER), "the old native source is still actually gone and sealed");
            });
            n.after(tick + 1000, () -> {
                if (operation.equals("close_face")) n.put(inlet, n.state(inlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.UP), true));
                else { FluidStructureMutationTests.addTank(n, source, 1, 4); n.fill(source, fluid, 1000); }
            });
            n.after(tick + 2600, () -> {
                n.expect(n.amount(sink, fluid) >= before + 128, "restoring the actual source or its face makes new native receipts");
                n.done("actual_source_fraction=" + owned + ", source_action=" + operation + ", reload=true, old_source_stops_fresh_extraction=true");
            });
        });
    }
    static void tank(GameTestHelper h, String operation, Fluid fluid) {
        String name = "structure_tank_" + operation + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 8, 4); var sink = p(16, 0, 4); var pump = p(3, 3, 4);
        n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, p(0, 3, 4), sink.above(3));
        n.pump(pump, Direction.EAST); n.build(); n.timeout(5998); boolean[] acted = {false};
        n.after(20, () -> { n.fill(source, fluid, 12000); n.speed(pump, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0] || n.amount(sink, fluid) < 64) return;
            acted[0] = true; int tick = (int)h.getTick(); int before = n.amount(sink, fluid); var old = n.tankEntity(sink);
            BlockPos receiver = sink;
            switch (operation) {
                case "extend_height" -> FluidStructureMutationTests.addTank(n, sink.above(4), 1, 2);
                case "extend_width" -> FluidStructureMutationTests.addTank(n, sink, 2, 4);
                case "split_controller", "split_member" -> {
                    var removed = operation.equals("split_controller") ? sink : sink.above();
                    // Native multiblock removal redistributes stock to the
                    // remaining physical blocks. Do not fake a discarded total.
                    n.tanks.removeIf(t -> t.base().equals(sink)); n.tankBlocks.remove(removed);
                    for (int y = 0; y < 4; y++) if (!sink.above(y).equals(removed)) n.tanks.add(new NativeFluidTestNetwork.Tank(sink.above(y), 1, 1));
                    n.expect(h.getLevel().destroyBlock(n.absolute(removed), false), "the actual tank member is broken");
                    n.put(removed, Blocks.BARRIER.defaultBlockState());
                    receiver = operation.equals("split_controller") ? sink.above() : sink.above(2);
                }
                case "replace_receiver" -> {
                    n.removeTank(sink, fluid, Integer.MAX_VALUE);
                    n.tanks.removeIf(t -> t.base().equals(sink));
                    n.ports.remove(sink.above(3));
                    for (int y = 0; y < 4; y++) { n.tankBlocks.remove(sink.above(y)); n.put(sink.above(y), Blocks.BARRIER.defaultBlockState()); }
                    n.after(tick + 120, () -> { FluidStructureMutationTests.addTank(n, sink, 1, 4); n.port(sink.above(3), Direction.WEST); });
                    before = 0;
                }
                default -> throw new IllegalArgumentException(operation);
            }
            var target = receiver; int checkpoint = before;
            n.after(tick + 1400, () -> {
                if (operation.equals("extend_height")) n.expect(n.tankEntity(target).getHeight() == 6 && n.tankEntity(target).getTankInventory().getCapacity() == 48000,
                    "native connectivity actually enlarged the receiver height");
                if (operation.equals("extend_width")) n.expect(n.tankEntity(target).getWidth() == 2 && n.tankEntity(target).getTankInventory().getCapacity() == 128000,
                    "native connectivity actually enlarged the receiver cross-section");
                if (operation.equals("split_controller") || operation.equals("replace_receiver")) n.expect(n.tankEntity(target) != old,
                    "the actual capability controller changed while the adjacent wet pipe stayed in place");
                n.expect(n.amount(target, fluid) >= checkpoint + 64, "after the native container transition the remaining connection receives new liquid");
                n.done("native_tank_action=" + operation + ", new_receiver=" + target);
            });
        });
    }
    static void cachedEndpoint(GameTestHelper h, String operation, Fluid fluid) {
        String name = "structure_endpoint_" + operation + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        boolean lastFraction = operation.startsWith("last_fraction");
        var n = new NativeFluidTestNetwork(h, name); var inlet = p(0, lastFraction ? 3 : 8, 4);
        var side = lastFraction ? Direction.WEST : Direction.UP; var mouth = inlet.relative(side);
        var sink = lastFraction ? p(4, 3, 4) : p(16, 0, 4); var pump = p(lastFraction ? 2 : 3, 3, 4);
        n.tank(sink, 1, lastFraction ? 1 : 4);
        if (lastFraction) n.route(inlet, sink); else n.route(inlet, p(0, 3, 4), sink.above(3));
        n.port(inlet, side);
        n.pump(pump, Direction.EAST); n.build();
        if (lastFraction) {
            // The native motor's smallest setting drives the old pump at 2 RPM.
            // Two real 2:1 reductions leave time to observe the final sub-mB
            // receipt even while its liquid/air front changes the pump load.
            // A single reduction can finish that tail between native tick samples.
            n.put(n.motors.get(pump), Blocks.AIR.defaultBlockState());
            var large = p(1, 4, 4); var small = p(1, 5, 5);
            var secondLarge = p(0, 5, 5); var secondSmall = p(0, 6, 6); var motor = p(-1, 6, 6);
            n.put(large, AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            n.put(small, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            n.put(secondLarge, AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            n.put(secondSmall, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            n.put(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            n.motors.put(pump, motor); n.speed(pump, 0);
            for (var at : List.of(large, small, secondLarge, secondSmall, motor))
                if (n.entity(at) instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) smart.initialize();
        }
        n.timeout(lastFraction || operation.endsWith("drain") ? 11998 : 5998); boolean[] acted = {false};
        n.after(20, () -> { n.worldFill(mouth, fluid, side.getOpposite()); n.speed(pump, lastFraction ? 1 : 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0]) return;
            var connection = (IPipePressure)n.pipe(inlet).interfaces.get(side);
            var src = connection.differentialcreate$source();
            if (!(src instanceof OpenEndedPipe)) src = connection.differentialcreate$retainedSource();
            if (!(src instanceof OpenEndedPipe end)) return;
            int cached = end.provideHandler().getCapability().getFluidInTank(0).getAmount();
            double buffered = com.diliupengyun.differential_create.kinetics.FluidKinetics.state(n.pipe(inlet)).buffers.values().stream()
                .filter(b -> !b.delivery).mapToDouble(b -> b.amount).sum();
            if (operation.startsWith("last_fraction")) { if (cached != 0 || !(buffered > 1e-6 && buffered < 1 - 1e-6)) return; }
            else if (cached < 10 || cached >= 1000 || n.volume(inlet) <= 0) return;
            acted[0] = true; int tick = (int)h.getTick();
            n.expect(!h.getLevel().getFluidState(n.absolute(mouth)).isSource(), "the whole source was actually taken before this structure change");
            if (lastFraction) {
                n.expect(Math.abs(((com.diliupengyun.differential_create.kinetics.IPumpDynamics)n.entity(pump)).differentialcreate$flow()) < 1,
                    "the native gear reduction produces an actual flow below one mB per tick at the observed source tail");
                // Keep the tail through the actual mutation tick, then use the
                // native motor to finish delivery within the normal horizon.
                n.after(tick + 1, () -> n.speed(pump, 32));
            }
            System.out.println("FLUID_STRUCTURE_CACHED name=" + name + " tick=" + tick + " native_cached=" + cached);
            switch (operation) {
                case "world_to_tank" -> { n.worldSources.remove(mouth); FluidStructureMutationTests.addTank(n, mouth, 1, 1); }
                case "world_to_pipe", "last_fraction_to_pipe" -> {
                    n.worldSources.remove(mouth); n.route(mouth, mouth.above(2)); n.ports.get(mouth).add(side.getOpposite());
                    var extra = mouth.above(3); FluidStructureMutationTests.addTank(n, extra, 1, 1);
                    n.route(mouth.above(2), extra); n.installChangedPipes();
                }
                case "seal_cached_world" -> {
                    n.put(mouth, Blocks.BARRIER.defaultBlockState());
                    n.after(tick + 120, () -> n.put(mouth, Blocks.AIR.defaultBlockState()));
                }
                case "close_cached_face", "close_cached_reload", "last_fraction_close_reload", "close_cached_drain", "last_fraction_closed_drain" -> {
                    n.put(inlet, n.state(inlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), false));
                    if (!operation.equals("close_cached_face")) n.after(tick + 20, () -> n.reload(inlet));
                    if (operation.endsWith("drain")) {
                        n.worldFill(mouth, fluid, side.getOpposite());
                        n.after(tick + 900, () -> {
                            var saved = FluidKinetics.state(n.pipe(inlet)); double pending = 0;
                            var counted = new IdentityHashMap<OpenEndedPipe, Boolean>();
                            for (var c : NativeFluidTestNetwork.connections(n.pipe(inlet))) {
                                var nativeConnection = (IPipePressure)c;
                                for (var source : new FlowSource[]{nativeConnection.differentialcreate$source(), nativeConnection.differentialcreate$retainedSource()})
                                    if (source instanceof OpenEndedPipe retained && counted.put(retained, true) == null)
                                        pending += retained.provideHandler().getCapability().getFluidInTank(0).getAmount();
                            }
                            var tail = saved.buffers.get(side); if (tail != null && !tail.delivery) pending += tail.amount;
                            n.expect(pending < 1e-7, "already extracted world stock enters physical cells before the exterior face reopens");
                            n.expect(!n.state(inlet).getValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side)), "the physical world face is still actually closed");
                            n.expect(h.getLevel().getFluidState(n.absolute(mouth)).isSource(), "finishing owned stock must not take the new bucket behind the closed face");
                        });
                        n.after(tick + 1000, () -> n.put(inlet, n.state(inlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), true)));
                    } else n.after(tick + 120, () -> n.put(inlet, n.state(inlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), true)));
                }
                case "waterlogged_after_intake" -> {
                    var trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, true);
                    n.put(mouth, trapdoor);
                    n.expect(((net.minecraft.world.level.block.SimpleWaterloggedBlock)trapdoor.getBlock()).placeLiquid(h.getLevel(), n.absolute(mouth), trapdoor,
                        Fluids.WATER.getSource(false)), "an actual native waterlogging action introduces the second real world bucket");
                    n.introduced.merge(Fluids.WATER, 1000d, Double::sum); n.kind(new FluidStack(Fluids.WATER, 1)).input += 1000;
                }
                default -> throw new IllegalArgumentException(operation);
            }
            n.after(tick + (operation.endsWith("drain") ? 4500 : 1800), () -> {
                n.expect(n.amount(sink, fluid) == (operation.equals("waterlogged_after_intake") || operation.endsWith("drain") ? 2000 : 1000),
                    "the pre-existing real whole-bucket cache survives the endpoint change and reaches the receiver");
                n.done("actual_cached_world_intake=" + cached + ", actual_source_fraction=" + buffered + ", native_endpoint_action=" + operation);
            });
        });
    }
    static void outflow(GameTestHelper h, String operation, Fluid fluid) {
        String name = "structure_outflow_" + operation + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 8, 4); var outlet = p(6, 3, 4); var mouth = outlet.east(); var pump = p(3, 3, 4);
        n.tank(source, 1, 1); n.route(source, p(0, 3, 4), outlet); n.port(outlet, Direction.EAST);
        n.pump(pump, Direction.EAST); n.build(); n.timeout(3998);
        // Contain the actual world output; no regeneration or secondary sources.
        for (var side : Direction.values()) if (side != Direction.WEST) n.put(mouth.relative(side), Blocks.BARRIER.defaultBlockState());
        n.worldSources.put(mouth, fluid); boolean[] acted = {false};
        n.after(20, () -> { n.fill(source, fluid, 1000); n.speed(pump, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0]) return;
            var connection = (IPipePressure)n.pipe(outlet).interfaces.get(Direction.EAST);
            var src = connection.differentialcreate$source(); if (!(src instanceof OpenEndedPipe)) src = connection.differentialcreate$retainedSource();
            if (!(src instanceof OpenEndedPipe end)) return;
            double outside = end.serializeNBT(h.getLevel().registryAccess()).getDouble("DifferentialDischargeAmount");
            if (!(outside > 100 && outside < 800)) return;
            acted[0] = true; int tick = (int)h.getTick();
            System.out.println("FLUID_STRUCTURE_OUTSIDE name=" + name + " tick=" + tick + " actual_already_discharged=" + outside);
            if (operation.equals("seal_mouth")) n.put(mouth, Blocks.BARRIER.defaultBlockState());
            else n.put(outlet, n.state(outlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.EAST), false));
            if (operation.equals("close_reload")) n.after(tick + 20, () -> n.reload(outlet));
            n.after(tick + 120, () -> {
                if (operation.equals("seal_mouth")) n.put(mouth, Blocks.AIR.defaultBlockState());
                else n.put(outlet, n.state(outlet).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.EAST), true));
            });
            n.after(tick + 2000, () -> {
                var actual = h.getLevel().getFluidState(n.absolute(mouth));
                n.expect(actual.isSource() && actual.getType().isSame(fluid) && n.body() < 1e-7,
                    "the resumed actual output completes exactly the original world bucket, without duplicating or forgetting its earlier discharge");
                n.done("actual_discharge_before_mutation=" + outside + ", outflow_action=" + operation + ", world_source=one_bucket");
            });
        });
    }
}
