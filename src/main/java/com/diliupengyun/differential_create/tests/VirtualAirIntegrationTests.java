package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.FluidMaterials;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.diliupengyun.differential_create.kinetics.PipeHydraulics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual structures advance on native server ticks in owned disposable worlds. */
@GameTestHolder("differentialcreate_air")
@PrefixGameTestTemplate(false)
public final class VirtualAirIntegrationTests {
    private static final String TEMPLATE = "differentialcreate_air:kineticphysicstests.physics_test";
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        add(result, "empty_pipe_reload", 260, VirtualAirIntegrationTests::empty);
        add(result, "air_only_pump", 440, VirtualAirIntegrationTests::airPump);
        add(result, "sealed_pump_zero_power", 440, VirtualAirIntegrationTests::sealedPump);
        for (var facing : Direction.values()) for (int rpm : new int[]{-64, 64}) for (Fluid fluid : new Fluid[]{null, Fluids.WATER, Fluids.LAVA}) {
            String kind = fluid == null ? "air" : fluid.isSame(Fluids.WATER) ? "water" : "lava";
            add(result, "pump_direction_" + facing.getName() + "_" + (rpm > 0 ? "positive" : "negative") + "_" + kind,
                2400, h -> PumpDirectionIntegrationTests.direction(h, facing, rpm, fluid));
        }
        for (var facing : Direction.values()) {
            for (int direction : new int[]{-1, 1}) add(result, "pump_direction_standstill_" + facing.getName() + "_" + (direction > 0 ? "positive" : "negative"),
                900, h -> PumpDirectionIntegrationTests.standstill(h, facing, direction));
            for (var fluid : new Fluid[]{Fluids.WATER, Fluids.LAVA}) {
                String kind = fluid.isSame(Fluids.WATER) ? "water" : "lava";
                add(result, "pump_direction_dynamic_" + facing.getName() + "_" + kind, 6000, h -> PumpDirectionIntegrationTests.dynamic(h, facing, fluid));
                add(result, "pump_direction_wet_reload_" + facing.getName() + "_" + kind, 1200, h -> PumpDirectionIntegrationTests.wetReload(h, facing, fluid));
                if (facing.getAxis() == Direction.Axis.Y) add(result, "pump_direction_dry_top_" + facing.getName() + "_" + kind,
                    500, h -> PumpDirectionIntegrationTests.dryTop(h, facing, fluid));
            }
        }
        add(result, "shared_fractional_native_receipt", 120, PumpPhysicsTests::sharedReceiverCombinesPendingReceipts);
        add(result, "sealed_air_then_vent", 2400, VirtualAirIntegrationTests::vent);
        add(result, "flat_world_source", 280, VirtualAirIntegrationTests::flat);
        add(result, "queue_nbt_components", 40, VirtualAirIntegrationTests::saveQueues);
        add(result, "immersed_flowing_mouth", 1600, VirtualAirIntegrationTests::immersed);
        add(result, "shared_last_native_mb", 1600, VirtualAirIntegrationTests::sharedReceiver);
        for (var fluid : new Fluid[]{Fluids.WATER, Fluids.LAVA}) for (int initial : new int[]{0, 1000})
            add(result, "gravity_tank_tee_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava") + "_" + initial, 1800,
                h -> GravityTankIntegrationTests.ventedTee(h, fluid, initial));
        for (var first : new Fluid[]{Fluids.WATER, Fluids.LAVA})
            add(result, "gravity_tank_obstruction_" + (first.isSame(Fluids.WATER) ? "water" : "lava"), 2600,
                h -> GravityTankIntegrationTests.obstructedInlet(h, first));
        for (boolean stepped : new boolean[]{false, true}) {
            String mode = stepped ? "speed_steps" : "fixed_speed";
            add(result, "dynamic_successive_world_buckets_" + mode, 6000, h -> FluidDynamicIntegrationTests.successiveBuckets(h, stepped));
            add(result, "dynamic_overlapping_world_buckets_" + mode, 6000, h -> FluidDynamicIntegrationTests.successiveBuckets(h, stepped, true));
            add(result, "dynamic_replenishing_pond_" + mode, 8000, h -> FluidDynamicIntegrationTests.replenishingPond(h, stepped));
        }
        add(result, "dynamic_pond_shared_source_fraction", 6000, FluidDynamicIntegrationTests::sharedSourceFraction);
        add(result, "shared_source_and_delivery_settlement", 1600, FluidDynamicIntegrationTests::sourceAndDeliverySettlement);
        for (var first : new Fluid[]{Fluids.WATER, Fluids.LAVA})
            add(result, "dynamic_tank_alternating_" + (first.isSame(Fluids.WATER) ? "water" : "lava"), 6000,
                h -> FluidDynamicIntegrationTests.alternatingTankBuckets(h, first));
        for (var operation : FluidStructureMutationTests.OPERATIONS) for (var state : FluidStructureMutationTests.STATES)
            for (var fluid : new Fluid[]{Fluids.WATER, Fluids.LAVA}) {
                String name = "structure_" + operation + "_" + state + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
                add(result, name, 6000, h -> FluidStructureMutationTests.mutation(h, operation, state, fluid));
            }
        for (var identity : List.of("same", "different", "components")) for (boolean reversed : new boolean[]{false, true})
            add(result, "structure_merge_" + identity + "_" + (reversed ? "right_first" : "left_first"), 6000,
                h -> FluidStructureBoundaryTests.merge(h, identity, reversed));
        for (var fluid : new Fluid[]{Fluids.WATER, Fluids.LAVA}) {
            String kind = fluid.isSame(Fluids.WATER) ? "water" : "lava";
            add(result, "structure_shared_world_room_" + kind, 4000, h -> FluidStructureBoundaryTests.sharedWorldRoom(h, fluid));
            add(result, "structure_moving_bearing_" + kind, 6000, h -> FluidStructureMovementTests.bearing(h, fluid));
            for (boolean loop : new boolean[]{false, true}) add(result, "structure_auto_" + (loop ? "loop" : "branch") + "_" + kind, 6000,
                h -> FluidStructurePlacementTests.automatic(h, loop, fluid));
            for (int length : new int[]{64, 256, 1024})
                add(result, "structure_distant_" + length + "_" + kind, 7000 + 24 * length, h -> FluidStructureScaleTests.distant(h, length, fluid));
            for (var operation : FluidStructureBoundaryTests.TANKS)
                add(result, "structure_tank_" + operation + "_" + kind, 6000, h -> FluidStructureBoundaryTests.tank(h, operation, fluid));
            for (var operation : FluidStructureBoundaryTests.SOURCES)
                add(result, "structure_source_" + operation + "_" + kind, 6000, h -> FluidStructureBoundaryTests.source(h, operation, fluid));
            for (var operation : FluidStructureBoundaryTests.ENDPOINTS)
                add(result, "structure_endpoint_" + operation + "_" + kind, operation.endsWith("drain") ? 12000 : 6000,
                    h -> FluidStructureBoundaryTests.cachedEndpoint(h, operation, fluid));
            for (var operation : FluidStructureBoundaryTests.OUTFLOWS)
                add(result, "structure_outflow_" + operation + "_" + kind, 4000, h -> FluidStructureBoundaryTests.outflow(h, operation, fluid));
            for (var operation : FluidStructureMachineTests.OPERATIONS)
                add(result, "structure_machine_" + operation + "_" + kind, 4000, h -> FluidStructureMachineTests.change(h, operation, fluid));
        }
        add(result, "structure_simultaneous_components", 8000, FluidStructureScaleTests::simultaneous);
        add(result, "structure_endpoint_waterlogged_after_intake_water", 6000,
            h -> FluidStructureBoundaryTests.cachedEndpoint(h, "waterlogged_after_intake", Fluids.WATER));
        for (boolean lava : new boolean[]{false, true}) {
            Fluid fluid = lava ? Fluids.LAVA : Fluids.WATER; String kind = lava ? "lava" : "water";
            for (int length : new int[]{16, 64, 256, 1024}) add(result, "transfer_" + length + "_" + kind, 7000 + length * 20,
                h -> transfer(h, length, fluid, false));
            add(result, "dry_lift_" + kind, 4400, h -> transfer(h, 16, fluid, true));
            for (int room : new int[]{999, 1000, 1001}) add(result, "bucket_room_" + room + "_" + kind, 1600, h -> room(h, room, fluid));
            for (String shape : new String[]{"cage", "braid", "ladder", "six_faces", "blind_comb", "spatial_snake"})
                add(result, "shape_" + shape + "_" + kind, 4200, h -> shape(h, shape, fluid));
            for (String mode : new String[]{"series", "opposing", "reversal", "unpowered"})
                add(result, "pump_pair_" + mode + "_" + kind, 2600, h -> pumpPair(h, mode, fluid));
            for (int seed = 0; seed < 24; seed++) {
                int picked = seed; String name = "adversarial_seed_" + seed + "_" + kind;
                add(result, name, 4800, h -> FluidCoverageIntegrationTests.adversarial(h, name, picked, fluid));
            }
            for (String part : new String[]{"middle", "source", "sink", "driven_source"}) {
                String name = "chunk_unload_" + part + "_" + kind;
                add(result, name, 12000, h -> FluidCoverageIntegrationTests.unload(h, name, part, fluid));
            }
        }
        add(result, "water_lava_queue", 5400, VirtualAirIntegrationTests::twoFluids);
        add(result, "filter_air_and_valve", 4600, VirtualAirIntegrationTests::filterValve);
        add(result, "native_spout_water", 760, FluidMachineIntegrationTests::gravityFeedsSpoutAndFillsWaterBucket);
        add(result, "native_spout_lava", 760, FluidMachineIntegrationTests::gravityFeedsSpoutAndFillsLavaBucket);
        add(result, "native_item_drain", 760, FluidMachineIntegrationTests::itemDrainAndSpoutTransferTheSameRealBucket);
        add(result, "native_basin_filter", 1480, FluidMachineIntegrationTests::filteredPipeSelectsLavaFromMixedBasin);
        for (String kind : new String[]{"water", "lava", "honey", "milk", "tea", "chocolate", "potion"})
            add(result, "native_material_64_" + kind, 12000, h -> FluidCoverageIntegrationTests.material(h, kind));
        for (boolean merged : new boolean[]{false, true})
            add(result, "component_identity_" + (merged ? "wet_merge" : "separate"), 3000, h -> FluidCoverageIntegrationTests.componentIdentity(h, merged));
        add(result, "budget_many_networks", 7600, FluidCoverageIntegrationTests::manyNetworks);
        // Reuse the native machine acceptance scenarios with the current model
        // and the same required assertions; each runs in its own sequential batch.
        reflected(result, "machine_", FluidMachineIntegrationTests.class);
        if (Boolean.getBoolean("differentialcreate.air.referenceTests"))
            for (var type : new Class<?>[]{PumpPhysicsTests.class, OpenPipePhysicsTests.class, FluidInventoryPhysicsTests.class,
                FluidStatePhysicsTests.class, FluidTopologyIntegrationTests.class}) reflected(result, "reference_", type);
        return result;
    }
    private static void reflected(Collection<TestFunction> result, String prefix, Class<?> type) {
        java.util.Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(GameTest.class)).sorted(java.util.Comparator.comparing(java.lang.reflect.Method::getName))
            .forEach(method -> add(result, prefix + method.getName(), method.getAnnotation(GameTest.class).timeoutTicks(), h -> {
                try { method.invoke(null, h); }
                catch (java.lang.reflect.InvocationTargetException failure) {
                    if (failure.getCause() instanceof Error error) throw error;
                    if (failure.getCause() instanceof RuntimeException error) throw error;
                    throw new IllegalStateException(failure.getCause());
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }));
    }
    private static void add(Collection<TestFunction> tests, String name, int timeout, Consumer<GameTestHelper> run) {
        if (java.util.Arrays.stream(System.getProperty("differentialcreate.air.select", ".*").split(",")).anyMatch(name::matches))
            tests.add(new TestFunction("air_" + name, name, TEMPLATE, timeout, 0, true, run));
    }
    private static double air(NativeFluidTestNetwork n) {
        double total = 0;
        for (var pos : n.ports.keySet()) if (!n.tankBlocks.contains(pos) && n.pipe(pos) != null) {
            var saved = FluidKinetics.state(n.pipe(pos)); total += saved.airVolume();
            n.expect(saved.phases != null && Math.abs(saved.phases.volume() - saved.queueCapacity) < 1e-7,
                "each pipe stays full of conserved incompressible phases");
        }
        return total;
    }
    private static void empty(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "empty_pipe_reload"); n.route(p(0, 4, 3), p(6, 4, 3)); n.port(p(0, 4, 3), Direction.WEST); n.port(p(6, 4, 3), Direction.EAST);
        n.build(); n.timeout(258);
        double[] original = {0};
        n.after(20, () -> { original[0] = air(n); n.expect(original[0] == 700, "new straight pipes initialise with exactly their air capacity"); });
        n.after(40, () -> { for (var p : n.ports.keySet()) n.reload(p); });
        n.after(200, () -> { n.expect(air(n) == original[0] && n.body() == 0, "reload never refills or deletes existing air"); n.expect(!FluidKinetics.capture(h.getLevel()).active(n.absolute(p(3, 4, 3))), "this stable air network without a drive sleeps"); n.done("air, reload, idle"); });
    }
    private static void airPump(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "air_only_pump"); var pump = p(4, 4, 3);
        n.route(p(0, 4, 3), p(8, 4, 3)); n.port(p(0, 4, 3), Direction.WEST); n.port(p(8, 4, 3), Direction.EAST); n.pump(pump, Direction.EAST);
        n.build(); n.timeout(438); double[] original = {0}; FluidKinetics.AirAudit[] before = {null};
        n.after(20, () -> { original[0] = air(n); before[0] = FluidKinetics.airAudit(h.getLevel()); n.speed(pump, 64); });
        n.after(260, () -> {
            var state = (IPumpDynamics)n.entity(pump); var after = FluidKinetics.airAudit(h.getLevel());
            n.expect(Math.abs(state.differentialcreate$flow()) > .01 && state.differentialcreate$head() > 0, "a pump drives conserved air before water arrives");
            n.expect(n.body() == 0 && Math.abs(air(n) - original[0] - (after.incoming() - before[0].incoming()) + after.outgoing() - before[0].outgoing()) < 1e-6, "external air import/export ledger closes");
            n.done("air, dry pump, native shaft");
        });
    }
    private static void sealedPump(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "sealed_pump_zero_power"); var pump = p(4,4,3);
        n.route(p(0,4,3),p(8,4,3)); n.pump(pump,Direction.EAST); n.build(); n.timeout(438);
        double[] original = {0};
        n.after(20, () -> { original[0] = air(n); n.speed(pump,64); });
        n.after(260, () -> {
            var state = (IPumpDynamics)n.entity(pump);
            n.expect(state.differentialcreate$head() > 1, "a rotating sealed air pump retains its shutoff pressure");
            n.expect(state.differentialcreate$flow() == 0 && state.differentialcreate$torque() == 0,
                "ideal zero-through-flow pressure requires no continuous hydraulic shaft power");
            n.expect(air(n) == original[0] && n.body() == 0, "sealed pump pressure neither creates nor destroys phases");
            n.done("sealed air, pressure, zero hydraulic power");
        });
    }
    private static void vent(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "sealed_air_then_vent"); BlockPos inlet = p(0, 17, 3), bottom = p(0, 1, 3);
        n.route(inlet, bottom); n.port(inlet, Direction.UP); n.port(bottom, Direction.DOWN); n.build(); n.put(bottom.below(), Blocks.BARRIER.defaultBlockState()); n.timeout(2398);
        n.after(20, () -> n.worldFill(inlet.above(), Fluids.WATER, Direction.DOWN));
        n.after(220, () -> {
            n.expect(h.getLevel().getFluidState(n.absolute(inlet.above())).isSource() && n.body() == 0, "trapped incompressible air refuses a full bucket despite sufficient nominal volume");
            n.put(bottom.below(), Blocks.AIR.defaultBlockState()); n.worldSources.put(bottom.below(), Fluids.WATER); FluidKinetics.invalidate(h.getLevel());
        });
        n.watch(() -> { if (h.getTick() > 225) n.audit(); if (h.getTick() > 240 && !h.getLevel().getFluidState(n.absolute(inlet.above())).isSource()
            && n.body() > 0) { n.expect(air(n) > 0, "vented intake replaces actual air instead of compressing it"); n.done("sealed, world bucket, downstream vent"); } });
    }
    private static void flat(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "flat_world_source"); var inlet = p(0, 3, 3);
        n.route(inlet, p(12, 3, 3)); n.port(inlet, Direction.WEST); n.port(p(12, 3, 3), Direction.EAST); n.build(); n.timeout(278);
        n.after(20, () -> n.worldFill(inlet.west(), Fluids.WATER, Direction.EAST));
        n.after(230, () -> { n.expect(h.getLevel().getFluidState(n.absolute(inlet.west())).isSource() && n.body() == 0, "a flat unpowered network does not actively take world water"); n.done("flat, no drive, bucket intact"); });
    }
    private static void immersed(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "immersed_flowing_mouth"); var pump = p(2, 4, 3); var outside = p(-1, 4, 3);
        n.route(p(0, 4, 3), p(4, 4, 3)); n.port(p(0, 4, 3), Direction.WEST); n.port(p(4, 4, 3), Direction.EAST); n.pump(pump, Direction.EAST);
        // A separate real air circuit intentionally changes the world-wide air
        // ledger. Immersion must be judged at this mouth, not by other networks.
        var airPump = p(18, 4, 3);
        n.route(p(16, 4, 3), p(20, 4, 3)); n.port(p(16, 4, 3), Direction.WEST); n.port(p(20, 4, 3), Direction.EAST); n.pump(airPump, Direction.EAST);
        n.build(); n.timeout(1598); FluidKinetics.AirAudit[] before = {null}; boolean[] observed = {false}; double[] initial = {0};
        for (var d : Direction.values()) if (d != Direction.EAST && d != Direction.WEST)
            n.put(outside.relative(d), Blocks.BARRIER.defaultBlockState());
        n.after(20, () -> {
            // A native source one block behind the mouth sustains flowing water.
            // Establish immersion before enabling the motor, without a one-tick
            // dry inlet or a flowing block that disappears on its fluid tick.
            n.worldFill(outside.west(), Fluids.WATER, Direction.EAST);
            n.put(outside, Fluids.FLOWING_WATER.getFlowing(7, false).createLegacyBlock());
            initial[0] = air(n); n.speed(pump, 64); n.speed(airPump, 64); before[0] = FluidKinetics.airAudit(h.getLevel());
        });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            var external = h.getLevel().getFluidState(n.absolute(outside));
            n.expect(!external.isEmpty() && !external.isSource(), "native flowing water continuously immerses the mouth");
            n.audit();
            n.expect(n.body() == 0 && Math.abs(air(n) - initial[0]) < 1e-7,
                "a submerged non-source mouth neither imports liquid nor changes its full air inventory");
            for (int x = 0; x <= 4; x++)
                n.expect(FluidKinetics.state(n.pipe(p(x, 4, 3))).outward.values().stream().allMatch(q -> q == 0),
                    "the immersed circuit has no accepted air intake, exhaust or internal flow");
            var sealed = (IPumpDynamics)n.entity(pump); var running = (IPumpDynamics)n.entity(airPump);
            if (observed[0] || sealed.differentialcreate$head() <= 1 || running.differentialcreate$flow() <= .01) return;
            observed[0] = true;
            n.after((int)h.getTick() + 120, () -> {
                n.expect(sealed.differentialcreate$head() > 1 && sealed.differentialcreate$flow() == 0,
                    "an actually ticking dry pump retains pressure with its immersed inlet blocked");
                n.expect(running.differentialcreate$flow() > .01 && FluidKinetics.airAudit(h.getLevel()).incoming() > before[0].incoming(),
                    "another actually flowing air circuit may change the world ledger without feeding this immersed mouth");
                n.done("native non-source immersion, actual shutoff head, zero local air flow, concurrent native air control");
            });
        });
    }
    private static void sharedReceiver(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "shared_last_native_mb"); var sink = p(8, 1, 8); n.tank(sink, 1, 1);
        var sources = List.of(p(3, 5, 8), p(13, 5, 8));
        for (var source : sources) { n.tank(source, 1, 1); n.route(source, p(source.getX(), 1, 8), sink); }
        n.build(); n.timeout(1598); n.after(20, () -> { n.fill(sink, Fluids.WATER, 7999); for (var source : sources) n.fill(source, Fluids.WATER, 1000); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); air(n); double pending = 0;
            for (var pipe : List.of(sink.west(), sink.east())) for (var b : FluidKinetics.state(n.pipe(pipe)).buffers.values()) if (b.delivery) pending += b.amount;
            n.expect(pending <= 8000 - n.amount(sink, Fluids.WATER) + 1e-7, "separate components cannot both reserve the same last native mB");
        });
        n.after(1400, () -> { n.expect(n.amount(sink, Fluids.WATER) == 8000, "the one genuine receiver mB is actually delivered"); n.done("two native components, shared nearly-full receiver, fractional receipt reservation"); });
    }
    private static void transfer(GameTestHelper h, int length, Fluid fluid, boolean lift) {
        var n = new NativeFluidTestNetwork(h, (lift ? "dry_lift_" : "transfer_" + length + "_") + (fluid.isSame(Fluids.WATER) ? "water" : "lava"));
        var source = p(0, lift ? 2 : 5, 3); var sink = p(length + 1, lift ? 5 : 1, 3); var pump = p(length / 2, lift ? 5 : 1, 3);
        n.tank(source, 1, 1); n.tank(sink, 1, 1);
        if (lift) n.route(source, source.below(), p(4, 1, 3), p(4, 5, 3), p(length, 5, 3), sink);
        else n.route(source, p(0, 1, 3), p(length, 1, 3), sink);
        n.pump(pump, Direction.EAST); n.build(); n.timeout(lift ? 4398 : 6998 + length * 20);
        n.after(20, () -> { n.fill(source, fluid, 1000); n.speed(pump, 64); });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            n.audit(); air(n);
            if (n.amount(sink, fluid) >= 999 && n.amount(source, fluid) == 0) n.done("dry queue, source below pump=" + lift + ", distance=" + length);
        });
    }
    private static void room(GameTestHelper h, int wanted, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "bucket_room_" + wanted + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava"));
        BlockPos inlet = p(0, 6, 3), sink = p(5, 3, 3); n.tank(sink, 1, 1); n.route(inlet, p(0, 3, 3), sink); n.port(inlet, Direction.UP); n.build(); n.timeout(1598);
        n.after(20, () -> { int capacity = (int)Math.round(air(n)); n.expect(capacity < wanted, "fixture leaves a positive native receiver allowance"); n.fill(sink, fluid, 8000 - (wanted - capacity)); n.worldFill(inlet.above(), fluid, Direction.DOWN); });
        n.after(1400, () -> {
            n.audit(); boolean remains = h.getLevel().getFluidState(n.absolute(inlet.above())).isSource();
            n.expect(remains == (wanted < 1000), "whole-source extraction uses genuine " + wanted + " mB receiving capacity"); n.done("whole bucket, receiving room=" + wanted);
        });
    }
    private static void twoFluids(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "water_lava_queue"); BlockPos source = p(0, 3, 3), sink = p(25, 1, 3), pump = p(2, 1, 3);
        n.tank(source, 1, 1); n.tank(sink, 1, 1); n.route(source, p(0, 1, 3), sink); n.pump(pump, Direction.EAST); n.build(); n.timeout(5398);
        boolean[] lava = {false}, coexist = {false};
        n.after(20, () -> { n.fill(source, Fluids.WATER, 1000); n.speed(pump, 64); });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            if (!lava[0] && n.amount(source, Fluids.WATER) == 0) { lava[0] = true; n.fill(source, Fluids.LAVA, 1000); }
            for (var p : n.ports.keySet()) if (!n.tankBlocks.contains(p) && n.pipe(p) != null) {
                var s = FluidKinetics.state(n.pipe(p)); coexist[0] |= s.volume(new FluidStack(Fluids.WATER, 1)) > 0 && s.volume(new FluidStack(Fluids.LAVA, 1)) > 0;
            }
            if (n.amount(sink, Fluids.LAVA) > 0) n.expect(n.collected.getOrDefault(Fluids.WATER, 0d) >= 999, "lava cannot overtake the earlier water packet");
            n.removeTank(sink, Fluids.WATER, Integer.MAX_VALUE); n.removeTank(sink, Fluids.LAVA, Integer.MAX_VALUE); n.audit(); air(n);
            if (n.collected.getOrDefault(Fluids.LAVA, 0d) >= 999) { n.expect(coexist[0], "one native pipe actually contains both liquids without reacting"); n.done("native multi-fluid FIFO, no internal reaction"); }
        });
    }
    private static void shape(GameTestHelper h, String shape, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "shape_" + shape + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava"));
        var source = p(0, 12, 8); var sink = p(16, 1, 8); var targets = new ArrayList<BlockPos>(); targets.add(sink);
        n.tank(source, 1, 2); n.tank(sink, 1, 2); n.route(source, p(0, 7, 8));
        switch (shape) {
            case "cage" -> {
                for (int y : new int[]{4, 7, 10}) n.route(p(0, y, 8), p(16, y, 8), p(16, y, 20), p(0, y, 20), p(0, y, 8));
                for (int x : new int[]{0, 16}) for (int z : new int[]{8, 20}) n.route(p(x, 4, z), p(x, 10, z));
                n.route(p(16, 4, 8), sink.above());
            }
            case "braid" -> {
                n.route(p(0, 7, 8), p(16, 7, 8), sink.above());
                n.route(p(0, 7, 8), p(0, 7, 2), p(8, 7, 2), p(8, 4, 2), p(16, 4, 2), p(16, 4, 8));
                n.route(p(0, 7, 8), p(0, 7, 16), p(5, 7, 16), p(5, 3, 16), p(16, 3, 16), p(16, 3, 8));
            }
            case "ladder" -> {
                n.route(p(0, 3, 8), p(0, 10, 8)); n.route(p(16, 10, 8), sink.above());
                for (int y : new int[]{3, 5, 7, 10}) n.route(p(0, y, 8), p(16, y, 8));
            }
            case "six_faces" -> {
                var hub = p(0, 7, 8); n.route(hub, p(16, 7, 8), sink.above());
                for (var target : List.of(p(-8, 1, 8), p(0, 1, 0), p(0, 1, 16), p(0, 1, 8))) {
                    targets.add(target); n.tank(target, 1, 2);
                    var corner = p(target.getX(), 7, target.getZ());
                    if (!hub.equals(corner)) n.route(hub, corner);
                    n.route(corner, target.above());
                }
            }
            case "blind_comb" -> {
                n.route(p(0, 7, 8), p(16, 7, 8), sink.above());
                for (int x : new int[]{3, 6, 9, 12}) n.route(p(x, 7, 8), p(x, 7, 15), p(x, 10, 15));
            }
            case "spatial_snake" -> n.route(p(0, 7, 8), p(0, 7, 18), p(4, 7, 18), p(4, 6, 18), p(4, 6, 2),
                p(8, 6, 2), p(8, 5, 2), p(8, 5, 18), p(12, 5, 18), p(12, 4, 18), p(12, 4, 8), p(16, 4, 8), sink.above());
            default -> throw new IllegalArgumentException(shape);
        }
        n.build(); n.timeout(4198); n.after(20, () -> n.fill(source, fluid, shape.equals("six_faces") ? 15000 : 12000));
        n.watch(() -> { if (h.getTick() > 20) { n.audit(); air(n); } });
        if (shape.equals("six_faces")) n.after(1900, () -> {
            // The direct downward leg legitimately wins while its low tank is
            // empty. Filling that real receiver tests backpressure and forces
            // the remaining four branches to serve as actual receiving paths.
            var bottom = p(0, 1, 8); n.expect(n.amount(bottom, fluid) > 1000, "the direct gravity leg first receives real liquid");
            n.fill(bottom, fluid, n.tankEntity(bottom).getTankInventory().getCapacity() - n.amount(bottom, fluid));
            n.fill(source, fluid, n.tankEntity(source).getTankInventory().getCapacity() - n.amount(source, fluid));
        });
        if (shape.equals("six_faces")) n.after(3000, () -> {
            for (var target : targets) if (!target.equals(sink) && !target.equals(p(0, 1, 8)))
                n.expect(n.amount(target, fluid) > 100, "the shorter gravity branches receive real stock after the bottom fills");
            // A long arm can supply air while shorter wet arms drain. Native
            // port closures make that same arm the sole remaining liquid route.
            var hub = p(0, 7, 8);
            for (var side : List.of(Direction.WEST, Direction.NORTH, Direction.SOUTH)) n.edge(hub, hub.relative(side), false);
            n.fill(source, fluid, n.tankEntity(source).getTankInventory().getCapacity() - n.amount(source, fluid));
        });
        n.after(4000, () -> {
            for (var target : targets) n.expect(n.amount(target, fluid) > 100, "every reachable low outlet receives actual stock");
            if (shape.equals("blind_comb")) for (int x : new int[]{3, 6, 9, 12})
                n.expect(n.volume(p(x, 10, 15)) == 0, "sealed air at a blind tip stays owned and uncompressed");
            n.done("irregular " + shape + ", air/no-vacuum and per-fluid native ledgers");
        });
    }
    private static void pumpPair(GameTestHelper h, String mode, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "pump_pair_" + mode + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava"));
        var a = p(0, 3, 3); var b = p(21, 3, 3); var left = p(4, 2, 3); var right = p(16, 2, 3);
        n.tank(a, 1, 2); n.tank(b, 1, 2); n.route(a, a.below(), b.below(), b);
        n.pump(left, Direction.EAST); n.pump(right, mode.equals("opposing") ? Direction.WEST : Direction.EAST);
        n.build(); n.timeout(2598);
        n.after(20, () -> { n.fill(a, fluid, 1000); n.speed(left, mode.equals("unpowered") ? 0 : 64); n.speed(right, mode.equals("unpowered") ? 0 : 64); });
        n.watch(() -> { if (h.getTick() > 20) { n.audit(); air(n); } });
        if (mode.equals("reversal")) {
            n.after(550, () -> {
                n.expect(n.amount(b, fluid) >= 999, "dry pumps prime and send the first real bucket");
                for (var pump : List.of(left, right)) n.speed(pump, -64);
            });
            n.after(2400, () -> { n.expect(n.amount(a, fluid) >= 999 && n.amount(b, fluid) == 0, "real shaft reversal returns the bucket and air in reverse queue order"); n.done("paired native pump direction reversal"); });
        } else n.after(2400, () -> {
            if (mode.equals("series")) n.expect(n.amount(b, fluid) >= 999, "series dry pumps deliver actual stock");
            else if (mode.equals("unpowered")) for (var pump : List.of(left, right))
                n.expect(((com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)n.entity(n.motors.get(pump))).getGeneratedSpeed() == 0,
                    "unpowered motors supply no external drive while liquid may backdrive a free rotor");
            else n.expect(((IPumpDynamics)n.entity(left)).differentialcreate$head() > 0
                && ((IPumpDynamics)n.entity(right)).differentialcreate$head() < 0, "opposed shafts have opposite signed heads on the same world axis");
            n.done("paired " + mode + ", actual native shafts and bottom tank ports");
        });
    }
    private static void filterValve(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "filter_air_and_valve"); BlockPos source = p(0, 5, 3), sink = p(10, 1, 3), pump = p(2, 1, 3), smart = p(5, 1, 3), valve = p(7, 1, 3);
        n.tank(source, 1, 1); n.tank(sink, 1, 1); n.route(source, p(0, 1, 3), sink); n.pump(pump, Direction.EAST); n.build(); n.timeout(4598);
        n.put(smart, AllBlocks.SMART_FLUID_PIPE.getDefaultState().setValue(SmartFluidPipeBlock.FACE, AttachFace.FLOOR).setValue(SmartFluidPipeBlock.FACING, Direction.EAST));
        ((SmartBlockEntity)n.entity(smart)).initialize(); ((SmartBlockEntity)n.entity(smart)).getBehaviour(FilteringBehaviour.TYPE).setFilter(new ItemStack(Items.LAVA_BUCKET));
        var valveState = AllBlocks.FLUID_VALVE.getDefaultState().setValue(FluidValveBlock.FACING, Direction.NORTH).setValue(FluidValveBlock.AXIS_ALONG_FIRST_COORDINATE, false).setValue(FluidValveBlock.ENABLED, true);
        n.put(valve, valveState); ((SmartBlockEntity)n.entity(valve)).initialize();
        var motor = valve.above(); n.put(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
        ((SmartBlockEntity)n.entity(motor)).initialize(); n.motors.put(valve, motor);
        n.after(20, () -> { n.speed(pump, 64); n.speed(valve, -64); });
        n.after(200, () -> {
            n.expect(n.state(valve).getValue(FluidValveBlock.ENABLED), "native valve actually opens, speed=" + ((com.simibubi.create.content.kinetics.base.KineticBlockEntity)n.entity(valve)).getSpeed());
            n.expect(n.pipe(smart).canPullFluidFrom(FluidStack.EMPTY, n.state(smart), Direction.EAST), "native liquid-only filter allows air");
            n.expect(Math.abs(FluidKinetics.state(n.pipe(smart)).outward.getOrDefault(Direction.EAST, 0d)) > .01, "air passes a lava-only smart filter"); n.speed(valve, 64);
        });
        n.after(340, () -> {
            n.expect(!n.state(valve).getValue(FluidValveBlock.ENABLED), "native valve actually closes, speed=" + ((com.simibubi.create.content.kinetics.base.KineticBlockEntity)n.entity(valve)).getSpeed());
            n.expect(Math.abs(((IPumpDynamics)n.entity(pump)).differentialcreate$flow()) < 1e-7, "closed valve blocks conserved air");
            n.speed(valve, -64); n.fill(source, Fluids.WATER, 1000);
        });
        n.after(600, () -> { n.expect(n.amount(sink, Fluids.WATER) == 0, "the liquid filter still rejects water"); ((SmartBlockEntity)n.entity(smart)).getBehaviour(FilteringBehaviour.TYPE).setFilter(new ItemStack(Items.WATER_BUCKET)); });
        n.watch(() -> { if (h.getTick() > 600) { n.audit(); if (n.amount(sink, Fluids.WATER) >= 999) n.done("air filter bypass, valve, change filter"); } });
    }
    private static void saveQueues(GameTestHelper h) {
        var saved = new PipeHydraulics(); saved.fluid = new FluidStack(Fluids.WATER, 1);
        var sides = java.util.EnumSet.of(Direction.DOWN, Direction.UP); saved.adoptMacro(17.75, false, sides, 0);
        saved.prepareQueue(100, false, sides); saved.splitCursor = 17;
        h.assertTrue(saved.volume() == 17.75 && saved.airVolume() == 82.25, "initial finite stock initialises air only once");
        var a = new FluidStack(Fluids.WATER, 1); var b = new FluidStack(Fluids.WATER, 1);
        var tagA = new CompoundTag(); tagA.putString("queue_component", "A"); var tagB = new CompoundTag(); tagB.putString("queue_component", "B");
        a.set(DataComponents.CUSTOM_DATA, CustomData.of(tagA)); b.set(DataComponents.CUSTOM_DATA, CustomData.of(tagB));
        saved.phases.take(17.75, true); saved.phases.add(a, 17.75, true);
        saved.phases.take(10, false); saved.phases.add(b, 10, false); saved.adoptQueue(false, sides);
        var buffer = new PipeHydraulics.Buffer(); buffer.add(new FluidStack(Fluids.WATER, 1), .25); buffer.add(new FluidStack(Fluids.LAVA, 1), .75); buffer.delivery = true;
        saved.buffers.put(Direction.DOWN, buffer);
        for (int reload = 0; reload < 12; reload++) {
            var tag = new CompoundTag(); saved.write(tag, h.getLevel().registryAccess());
            h.assertTrue(tag.getCompound("DCIdealFluid").getInt("QueueVersion")==2,
                "current stock saves use an exact shared palette and volume arrays");
            var next = new PipeHydraulics(); next.read(tag, h.getLevel().registryAccess()); next.prepareQueue(100, false, sides); saved = next;
            h.assertTrue(saved.volume() == 27.75 && saved.airVolume() == 72.25 && saved.phases.volume() == 100, "repeated queue reload preserves both real liquid and air");
            h.assertTrue(saved.splitCursor == 17, "reload preserves rotating outlet priority");
            h.assertTrue(FluidMaterials.same(saved.parcels().get(0).identity(), a) && FluidMaterials.same(saved.parcels().get(2).identity(), b), "same fluid with different components preserves separate queue identities");
            h.assertTrue(saved.buffers.get(Direction.DOWN).parcels().size() == 2 && saved.buffers.get(Direction.DOWN).amount == 1, "fractional boundary identities survive together");
        }
        saved.prepareQueue(50, false, sides);
        h.assertTrue(saved.queueCapacity == 50 && saved.volume() == 27.75 && saved.airVolume() == 22.25, "removing geometry releases only its air and retains real inventory");
        var small = new PipeHydraulics.Buffer(); small.add(new FluidStack(Fluids.WATER, 1), .025);
        h.assertTrue(small.amount == .025, "native integer-boundary numerical zero does not delete a real small parcel");
        h.assertTrue(FluidMaterials.of(FluidStack.EMPTY).isGas() && FluidMaterials.of(FluidStack.EMPTY).gravityForce(100) == 0, "air has positive effective inertia and no gravity"); h.succeed();
    }
}
