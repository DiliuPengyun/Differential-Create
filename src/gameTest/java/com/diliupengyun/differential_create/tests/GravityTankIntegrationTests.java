package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Passive, vented tee structures filled only by actual world buckets. */
final class GravityTankIntegrationTests {
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }

    static void ventedTee(GameTestHelper h, Fluid fluid, int initial) {
        String name = "gravity_tank_tee_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava") + "_" + initial;
        var n = new NativeFluidTestNetwork(h, name);
        var tank = p(0, 1, 8); var hub = p(1, 1, 8);
        var vent = hub.above(2); var inlet = p(2, 4, 8);
        n.tank(tank, 1, 1);
        n.route(tank, hub, p(2, 1, 8), inlet);
        n.route(hub, vent); n.port(vent, Direction.UP); n.port(inlet, Direction.UP);
        n.build(); n.timeout(1798);
        n.after(20, () -> {
            if (initial > 0) n.fill(tank, fluid, initial);
            n.worldFill(inlet.above(), fluid, Direction.DOWN);
        });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            n.audit(); FluidStructureMutationTests.phases(n);
            if (h.getTick() % 200 == 0) {
                System.out.println("GRAVITY_TANK_SAMPLE name=" + name + " tick=" + h.getTick()
                    + " tank=" + n.amount(tank, fluid) + " body=" + n.body()
                    + " right=" + n.volume(inlet.below()) + "/" + n.volume(inlet.below(2))
                    + " hub_flow=" + FluidKinetics.state(n.pipe(hub)).outward);
            }
        });
        n.after(1600, () -> {
            n.expect(!h.getLevel().getFluidState(n.absolute(inlet.above())).isSource(), "the native inlet accepted the actual bucket");
            n.expect(n.amount(tank, fluid) > initial + 500, "the low native tank received most of the real bucket");
            n.expect(n.volume(inlet.below()) + n.volume(inlet.below(2)) < 1,
                "unpressurised elevated liquid does not remain suspended above a vented low tank");
            n.done("passive=true, vents=2, initial_tank=" + initial + ", actual_bucket=1000, final_tank=" + n.amount(tank, fluid));
        });
    }

    static void obstructedInlet(GameTestHelper h, Fluid first) {
        String name = "gravity_tank_obstruction_" + (first.isSame(Fluids.WATER) ? "water" : "lava");
        var second = first.isSame(Fluids.WATER) ? Fluids.LAVA : Fluids.WATER;
        var n = new NativeFluidTestNetwork(h, name);
        var tank = p(0, 1, 8); var hub = p(1, 1, 8); var inlet = p(2, 4, 8);
        n.tank(tank, 1, 1); n.route(tank, hub, p(2, 1, 8), inlet);
        n.route(hub, hub.above(2)); n.port(hub.above(2), Direction.UP); n.port(inlet, Direction.UP);
        n.build(); n.timeout(2598);
        int[] obstructed = {-1}; double[] retained = {0}; int[] received = {0};
        n.after(20, () -> n.worldFill(inlet.above(), first, Direction.DOWN));
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            n.audit(); FluidStructureMutationTests.phases(n);
            if (obstructed[0] >= 0 || n.amount(tank, first) < 200 || n.volume(inlet.below()) < 30
                || h.getLevel().getFluidState(n.absolute(inlet.above())).isSource()) return;
            obstructed[0] = (int)h.getTick();
            n.worldFill(inlet.above(), second, Direction.DOWN);
            n.after(obstructed[0] + 400, () -> {
                n.expect(h.getLevel().getFluidState(n.absolute(inlet.above())).isSource(),
                    "the different world bucket stays outside when the single-fluid native tank rejects it and the actual pipe space is below a bucket");
                retained[0] = n.volume(inlet.below()) + n.volume(inlet.below(2)); received[0] = n.amount(tank, first);
                n.expect(retained[0] > 30, "the immersed inlet cannot supply replacement air to the right liquid column");
                System.out.println("GRAVITY_TANK_OBSTRUCTED name=" + name + " tank=" + received[0] + " elevated=" + retained[0]);
            });
            n.after(obstructed[0] + 600, () -> {
                n.expect(Math.abs(n.volume(inlet.below()) + n.volume(inlet.below(2)) - retained[0]) < 1e-6,
                    "the trapped right column remains stationary during a further observation window");
                var source = inlet.above(); var block = n.state(source);
                var bucket = ((BucketPickup)block.getBlock()).pickupBlock(null, h.getLevel(), n.absolute(source), block);
                n.expect(bucket.is(second.isSame(Fluids.WATER) ? Items.WATER_BUCKET : Items.LAVA_BUCKET), "the player really collects the obstructing world bucket");
                n.collected.merge(second, 1000d, Double::sum); n.kind(new FluidStack(second, 1)).output += 1000;
                FluidKinetics.invalidate(h.getLevel()); n.audit();
            });
            n.after(obstructed[0] + 1600, () -> {
                n.expect(n.volume(inlet.below()) + n.volume(inlet.below(2)) < 1,
                    "exposing the same inlet to air drains the previously trapped elevated liquid");
                n.expect(n.amount(tank, first) > received[0] + 30, "the unchanged tank receives new actual liquid after removing the inlet obstruction");
                n.done("passive=true, different_world_bucket_blocks_air=true, actual_bucket_pickup_resumes=true, final_tank=" + n.amount(tank, first));
            });
        });
    }
}
