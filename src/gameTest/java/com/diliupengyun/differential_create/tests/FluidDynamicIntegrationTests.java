package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Continuous native ticks and the observed low-speed -> faster-speed sequence.
 * No queue prefill, direct pressure assignment or manual physics advancement. */
final class FluidDynamicIntegrationTests {
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static NativeFluidTestNetwork layout(GameTestHelper h, String name) {
        return layout(h, name, false);
    }
    private static NativeFluidTestNetwork layout(GameTestHelper h, String name, boolean drawFromTank) {
        var n = new NativeFluidTestNetwork(h, name);
        var tank = p(0, 5, 3);
        n.tank(tank, 1, 1);
        n.route(p(0, 2, 3), tank);
        n.port(p(0, 2, 3), Direction.DOWN);
        n.route(tank, p(2, 5, 3), p(2, 2, 3));
        n.port(p(2, 2, 3), Direction.DOWN);
        n.pump(p(0, 4, 3), Direction.UP);
        if (drawFromTank) n.pump(p(2, 3, 3), Direction.DOWN);
        // Put the drive below its cog, leaving the top bridge unoccupied.
        n.motors.put(p(0, 4, 3), p(1, 3, 3));
        n.build();
        n.put(p(1, 3, 3), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        ((SmartBlockEntity)n.entity(p(1, 3, 3))).initialize();
        return n;
    }
    static void successiveBuckets(GameTestHelper h, boolean stepSpeed) {
        successiveBuckets(h, stepSpeed, false);
    }
    static void successiveBuckets(GameTestHelper h, boolean stepSpeed, boolean overlapping) {
        String name = "dynamic_" + (overlapping ? "overlapping" : "successive") + "_world_buckets_"
            + (stepSpeed ? "speed_steps" : "fixed_speed");
        var n = layout(h, name);
        var tank = p(0, 5, 3); var pump = p(0, 4, 3); var mouth = p(0, 1, 3);
        n.put(p(2, 1, 3), Blocks.BARRIER.defaultBlockState());
        n.timeout(5998);
        int[] checkpoint = {-1};
        n.after(20, () -> {
            n.worldFill(mouth, Fluids.WATER, Direction.UP);
            // One neighbour sustains native non-source water after extraction,
            // without regenerating a source or offering ambient air to the inlet.
            n.worldFill(mouth.west(), Fluids.WATER, Direction.EAST);
            n.speed(pump, 16);
        });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            n.audit();
            if (h.getTick() % 100 == 0) trace(n, pump, tank, mouth, "watch");
            // Anchor the interaction to actual delivery: far forced chunks can
            // become ticking asynchronously during an accelerated GameTest run.
            if (checkpoint[0] >= 0 || n.amount(tank, Fluids.WATER) < (overlapping ? 500 : 700)) return;
            var outside = h.getLevel().getFluidState(n.absolute(mouth));
            n.expect(!outside.isEmpty() && !outside.isSource(), "a real single-neighbour stream immerses the exhausted mouth");
            checkpoint[0] = n.amount(tank, Fluids.WATER);
            n.expect(checkpoint[0] < 1000 && n.body() > 299.9, "the first bucket partly resides in the full wet lift, partly in the tank");
            // An actual second world bucket, with its own independent ledger.
            // Overlap supplies it while the previous bucket still has real
            // cached and fractional stock; no test stock is seeded in the pipe.
            n.put(mouth, Blocks.WATER.defaultBlockState());
            n.introduced.merge(Fluids.WATER, 1000d, Double::sum);
            n.kind(new net.neoforged.neoforge.fluids.FluidStack(Fluids.WATER, 1)).input += 1000;
            FluidKinetics.invalidate(h.getLevel());
            trace(n, pump, tank, mouth, "second_bucket");
            int started = (int)h.getTick();
            if (stepSpeed) {
                n.after(started + 100, () -> n.speed(pump, 32));
                n.after(started + 300, () -> n.speed(pump, 64));
                n.after(started + 500, () -> n.speed(pump, 128));
                n.after(started + 700, () -> n.speed(pump, 256));
            }
            n.after(started + 1600, () -> {
                trace(n, pump, tank, mouth, "final"); n.audit();
                n.expect(n.amount(tank, Fluids.WATER) == 1700,
                    "two real buckets leave 300 mB in the wet lift and deliver the remaining 1700 mB to the native tank");
                n.done("actual second bucket, persistent immersion, independently counted stock, overlap=" + overlapping + ", speed_steps=" + stepSpeed);
            });
        });
    }
    static void replenishingPond(GameTestHelper h, boolean stepSpeed) {
        String name = "dynamic_replenishing_pond_" + (stepSpeed ? "speed_steps" : "fixed_speed");
        var n = layout(h, name);
        var tank = p(0, 5, 3); var pump = p(0, 4, 3); var mouth = p(0, 1, 3);
        double[] lastOwned = {0}; double[] generated = {0};
        var renewable = new RenewableLedger(); double[] lastIntake = {0};
        boolean[] started = {false};
        n.timeout(7998);
        n.after(20, () -> {
            // Native water source conversion, supported on a floor. Neighbours
            // regenerate extracted source blocks through ordinary fluid ticks.
            for (int x = -1; x <= 3; x++) for (int z = 2; z <= 4; z++) {
                var at = p(x, 1, z);
                n.put(at.below(), Blocks.STONE.defaultBlockState());
                n.put(at, Blocks.WATER.defaultBlockState());
                n.worldSources.put(at, Fluids.WATER);
            }
            for (int x = -2; x <= 4; x++) for (int z = 1; z <= 5; z++)
                if (x == -2 || x == 4 || z == 1 || z == 5) n.put(p(x, 1, z), Blocks.STONE.defaultBlockState());
            n.speed(pump, 16);
            FluidKinetics.invalidate(h.getLevel());
            lastOwned[0] = n.total(Fluids.WATER); lastIntake[0] = renewable.generated(n);
        });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            // Count the actual native renewable withdrawal receipts, including
            // inventory reserved before a deferred transfer. A regenerating
            // pool need not generate whole buckets, but its ledger is exact.
            double total = n.total(Fluids.WATER), delta = total - lastOwned[0];
            double intake = renewable.generated(n);
            n.expect(Math.abs(delta - (intake-lastIntake[0])) < 1e-6,
                "renewable source receipts must exactly explain stock growth: delta=" + delta
                    + ", generated=" + (intake-lastIntake[0]) + ", tick=" + h.getTick());
            lastIntake[0] = intake;
            generated[0] += Math.max(0, delta); lastOwned[0] = total;
            for (var at : n.ports.keySet()) if (!n.tankBlocks.contains(at)) {
                var saved = FluidKinetics.state(n.pipe(at));
                n.expect(Math.abs(saved.phases.volume() - saved.queueCapacity) < 1e-7, "every actual cell keeps its full phase capacity");
            }
            if (h.getTick() % 100 == 0) trace(n, pump, tank, mouth, "pond");
            if (started[0] || n.amount(tank, Fluids.WATER) < 100) return;
            started[0] = true; int firstDelivery = (int)h.getTick();
            if (stepSpeed) {
                // Change speed after enough actual wet ticks for the old
                // low-speed deadlock, matching the recorded interaction order.
                n.after(firstDelivery + 1000, () -> n.speed(pump, 32));
                n.after(firstDelivery + 1200, () -> n.speed(pump, 64));
                n.after(firstDelivery + 1400, () -> n.speed(pump, 128));
                n.after(firstDelivery + 1600, () -> n.speed(pump, 256));
            }
            n.after(firstDelivery + 5000, () -> {
                trace(n, pump, tank, mouth, "final");
                n.expect(n.amount(tank, Fluids.WATER) == 8000 && Math.abs(n.body() - 300) < 1e-7,
                    "a replenishing pond keeps delivering into its available tank, including after speed changes");
                n.expect(FluidKinetics.state(n.pipe(p(1, 5, 3))).volume() == 0,
                    "a full zero-head inventory does not prime its separate, stationary outlet");
                n.expect(FluidKinetics.state(n.pipe(p(0, 2, 3))).buffers.values().stream().allMatch(b -> b.amount == 0),
                    "refusing another world bucket still finishes all already owned stock at its inlet");
                // Renewable intake can reserve the actual remaining space;
                // the tank fills while its unpowered dry outlet remains dry.
                System.out.println("FLUID_DYNAMIC_PASS name=" + name + " generated=" + generated[0] + " tank=" + n.amount(tank, Fluids.WATER));
                n.cleanup(); h.succeed();
            });
        });
    }
    private static final class RenewableLedger {
        private final java.util.Set<com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement> sources
            = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        double generated(NativeFluidTestNetwork n) {
            for (var pos : n.ports.keySet()) if (n.channel(pos)) {
                var pipe = n.pipe(pos);
                if (pipe.interfaces == null) continue;
                for (var connection : pipe.interfaces.values()) {
                    var endpoint = (com.diliupengyun.differential_create.kinetics.IPipePressure) connection;
                    for (var source : new com.simibubi.create.content.fluids.FlowSource[]{endpoint.differentialcreate$source(), endpoint.differentialcreate$retainedSource()})
                        if (source instanceof com.simibubi.create.content.fluids.OpenEndedPipe open
                            && open.provideHandler().getCapability() instanceof com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement world)
                            sources.add(world);
                }
            }
            return sources.stream().mapToDouble(com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement::differentialcreate$renewableIntake).sum();
        }
    }
    static void sharedSourceFraction(GameTestHelper h) {
        String name = "dynamic_pond_shared_source_fraction";
        var n = layout(h, name, true); var tank = p(0, 5, 3); var pump = p(0, 4, 3); var mouth = p(0, 1, 3);
        double[] last = {0}, generated = {0}; int[] closedAt = {-1}; boolean[] checkpoint = {false};
        var renewable = new RenewableLedger(); double[] lastIntake = {0};
        n.timeout(5998);
        n.after(20, () -> {
            for (int x = -1; x <= 3; x++) for (int z = 2; z <= 4; z++) {
                var at = p(x, 1, z); n.put(at.below(), Blocks.STONE.defaultBlockState());
                n.put(at, Blocks.WATER.defaultBlockState()); n.worldSources.put(at, Fluids.WATER);
            }
            for (int x = -2; x <= 4; x++) for (int z = 1; z <= 5; z++)
                if (x == -2 || x == 4 || z == 1 || z == 5) n.put(p(x, 1, z), Blocks.STONE.defaultBlockState());
            n.speed(pump, 256); n.speed(p(2, 3, 3), 32); FluidKinetics.invalidate(h.getLevel());
            last[0] = n.total(Fluids.WATER); lastIntake[0] = renewable.generated(n);
        });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            double current = n.total(Fluids.WATER), delta = current - last[0];
            double intake = renewable.generated(n);
            n.expect(Math.abs(delta - (intake - lastIntake[0])) < 1e-6,
                "actual renewable receipts exactly explain stock growth, including native draining: delta=" + delta
                    + ", intake=" + (intake - lastIntake[0]) + ", tick=" + h.getTick());
            lastIntake[0] = intake;
            generated[0] += Math.max(0, delta); last[0] = current;
            double held = FluidKinetics.state(n.pipe(p(1, 5, 3))).buffers.values().stream()
                .filter(b -> !b.delivery).mapToDouble(b -> b.amount).sum();
            if (closedAt[0] < 0 && n.volume(p(1, 5, 3)) > 1 && held > 1e-6 && held < 1 - 1e-6) {
                // The outlet pump has begun a real integer withdrawal from
                // the zero-head tank. Close its mouth before that fraction is used.
                var outlet = p(2, 1, 3); var block = n.state(outlet);
                var picked = ((BucketPickup)block.getBlock()).pickupBlock(null, h.getLevel(), n.absolute(outlet), block);
                n.expect(picked.is(Items.WATER_BUCKET), "the displaced world source is actually collected in a native water bucket before sealing");
                n.collected.merge(Fluids.WATER, 1000d, Double::sum);
                n.put(outlet, Blocks.BARRIER.defaultBlockState());
                FluidKinetics.invalidate(h.getLevel()); closedAt[0] = (int)h.getTick();
            }
            if (checkpoint[0] || closedAt[0] < 0 || h.getTick() < closedAt[0] + 160
                || n.amount(tank, Fluids.WATER) < 6000 || ((IPumpDynamics)n.entity(pump)).differentialcreate$flow() != 0) return;
            n.expect(held > 1e-6 && held < 1 - 1e-6,
                "an actual non-roundoff native source fraction remains at another port of this same tank");
            // Whole-bucket admission applies to a finite source, not renewable
            // water. Collect the other real pond buckets before testing it.
            for (var at : n.worldSources.keySet()) if (!at.equals(mouth)) {
                var block = n.state(at);
                if (block.getFluidState().isSource()) {
                    var picked = ((BucketPickup) block.getBlock()).pickupBlock(null, h.getLevel(), n.absolute(at), block);
                    n.expect(picked.is(Items.WATER_BUCKET), "each removed pond source is collected as a real bucket");
                    n.collected.merge(Fluids.WATER, 1000d, Double::sum);
                }
                n.put(at, Blocks.BARRIER.defaultBlockState());
            }
            FluidKinetics.invalidate(h.getLevel());
            n.expect(n.state(mouth).getFluidState().isSource(), "one finite source bucket remains at the inlet");
            checkpoint[0] = true; double before = generated[0]; int tick = (int)h.getTick();
            int released = n.amount(tank, Fluids.WATER) - 7000;
            n.expect(n.removeTank(tank, Fluids.WATER, released) == released, "native execution leaves exactly 1000 mB visible receiver room");
            trace(n, pump, tank, mouth, "shared_source_fraction");
            n.after(tick + 80, () -> {
                n.expect(generated[0] == before && n.state(mouth).getFluidState().isSource(),
                    "freeing native space cannot admit a finite bucket before existing shared receipts settle");
                int settled = n.amount(tank, Fluids.WATER) - 7000;
                n.expect(settled >= 0 && settled <= 1, "at most the already owned final mB settles into newly freed space");
                if (settled > 0) n.removeTank(tank, Fluids.WATER, settled);
            });
            n.after(tick + 160, () -> {
                n.expect(generated[0] == before && n.amount(tank, Fluids.WATER) == 7000,
                    "another port's owned source fraction prevents overbooking visible 1000 mB room for a new bucket");
                n.expect(n.state(mouth).getFluidState().isSource(), "the finite world bucket is still present while admission is blocked");
                n.expect(n.removeTank(tank, Fluids.WATER, 1) == 1, "one real additional mB frees enough genuine capacity");
            });
            n.after(tick + 400, () -> {
                trace(n, pump, tank, mouth, "shared_source_fraction_final");
                // The original sub-mB source receipt can settle back into the
                // tank too. Exact total stock is checked on every tick above.
                n.expect(generated[0] == before && n.amount(tank, Fluids.WATER) >= 7999 && n.amount(tank, Fluids.WATER) <= 8000
                    && !n.state(mouth).getFluidState().isSource(),
                    "the finite bucket resumes after sufficient shared receiver room, without generating water or losing pending stock");
                System.out.println("FLUID_DYNAMIC_PASS name=" + name + " generated=" + generated[0] + " tank=" + n.amount(tank, Fluids.WATER));
                n.cleanup(); h.succeed();
            });
        });
    }
    /** Native integer API settlement, separate from the physical dynamic cases. */
    static void sourceAndDeliverySettlement(GameTestHelper h) {
        var n = new NativeFluidTestNetwork(h, "shared_source_and_delivery_settlement"); var tank = p(8, 3, 8);
        n.tank(tank, 1, 1); n.route(p(6, 3, 8), tank); n.route(tank, p(10, 3, 8)); n.build(); n.timeout(1598);
        boolean[] prepared = {false};
        n.after(20, () -> {
            n.fill(tank, Fluids.WATER, 8000);
            var drawn = n.tankEntity(tank).getTankInventory().drain(1, FluidAction.EXECUTE);
            n.expect(drawn.getAmount() == 1, "the cached source and returning receipt came from one actual native integer withdrawal");
            var a = FluidKinetics.state(n.pipe(tank.west())).buffers.computeIfAbsent(Direction.EAST,
                side -> new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer());
            var b = FluidKinetics.state(n.pipe(tank.east())).buffers.computeIfAbsent(Direction.WEST,
                side -> new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer());
            a.add(drawn, .4); a.delivery = false;
            b.add(drawn, .6); b.delivery = true;
            FluidKinetics.invalidate(h.getLevel()); prepared[0] = true; n.audit();
        });
        n.watch(() -> {
            if (!prepared[0]) return;
            n.audit();
            if (n.amount(tank, Fluids.WATER) == 8000) {
                n.expect(n.body() == 0, "integer settlement does not seed either closed air circuit with liquid");
                for (var at : new BlockPos[]{tank.west(), tank.east()})
                    n.expect(FluidKinetics.state(n.pipe(at)).buffers.values().stream().allMatch(b -> b.amount == 0),
                        "the single real mB is returned once, without stranding or duplicating either owned fraction");
                n.done("native integer settlement, actual withdrawal, unused source 0.4 plus returned receipt 0.6, exact independent stock");
            }
        });
    }
    /** Actual alternating world buckets through one native tank and pump.
     * Every bucket must be placed, collected, and independently accounted for;
     * no fractional cache, pipe stock or rotor velocity is assigned. */
    static void alternatingTankBuckets(GameTestHelper h, Fluid first) {
        var n = new NativeFluidTestNetwork(h, "dynamic_tank_alternating_" + (first.isSame(Fluids.WATER) ? "water" : "lava"));
        var pump = p(8, 3, 8); var tank = pump.east(2).above(); var inlet = tank.above();
        var source = inlet.above(); var output = pump.west();
        n.tank(tank, 1, 1); n.route(inlet, tank, tank.below(), pump); n.port(inlet, Direction.UP); n.port(pump, Direction.WEST);
        n.pump(pump, Direction.WEST); n.build(); n.timeout(5998);
        for (var side : Direction.values()) if (side != Direction.EAST) n.put(output.relative(side), Blocks.BARRIER.defaultBlockState());
        n.put(output, Blocks.AIR.defaultBlockState());
        int[] buckets = {0};
        n.after(20, () -> { n.speed(pump, 64); n.worldFill(source, first, Direction.DOWN); });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            Fluid current = (buckets[0] & 1) == 0 ? first : first.isSame(Fluids.WATER) ? Fluids.LAVA : Fluids.WATER;
            var outside = n.h.getLevel().getFluidState(n.absolute(output));
            if (outside.isSource()) n.worldSources.put(output, outside.getType());
            n.audit();
            if (!outside.isSource()) return;
            n.expect(outside.getType().isSame(current), "the next real world bucket has the newly supplied fluid identity");
            n.expect(n.body() < 1e-8 && n.tankEntity(tank).getTankInventory().isEmpty(), "the complete output bucket leaves no native tank or pipe remainder");
            for (var at : n.ports.keySet()) if (n.channel(at))
                n.expect(FluidKinetics.state(n.pipe(at)).buffers.values().stream().allMatch(b -> b.amount < 1e-8),
                    "all actual fractional tank receipts finish before changing fluid");
            var block = n.state(output);
            var taken = ((BucketPickup)block.getBlock()).pickupBlock(null, n.h.getLevel(), n.absolute(output), block);
            n.expect(taken.is(current.isSame(Fluids.WATER) ? Items.WATER_BUCKET : Items.LAVA_BUCKET), "the actual world source is collected as its native bucket");
            n.collected.merge(current, 1000d, Double::sum); n.kind(new FluidStack(current, 1)).output += 1000; n.audit();
            if (++buckets[0] == 6) { n.done("six_actual_alternating_buckets, immersed_fractional_return, no_remaining_stock"); return; }
            Fluid next = (buckets[0] & 1) == 0 ? first : first.isSame(Fluids.WATER) ? Fluids.LAVA : Fluids.WATER;
            n.worldFill(source, next, Direction.DOWN);
        });
    }
    private static void trace(NativeFluidTestNetwork n, BlockPos pump, BlockPos tank, BlockPos mouth, String stage) {
        var dynamics = (IPumpDynamics)n.entity(pump);
        var state = FluidKinetics.state(n.pipe(pump));
        System.out.println("FLUID_DYNAMIC_TRACE name=" + n.name + " stage=" + stage + " tick=" + n.h.getTick()
            + " tank=" + n.amount(tank, Fluids.WATER) + " body=" + n.body()
            + " source=" + n.h.getLevel().getFluidState(n.absolute(mouth))
            + " rpm=" + ((PumpBlockEntity)n.entity(pump)).getSpeed() + " head=" + dynamics.differentialcreate$head()
            + " flow=" + dynamics.differentialcreate$flow()
            + " pending=" + state.buffers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().amount).toList());
    }
}
