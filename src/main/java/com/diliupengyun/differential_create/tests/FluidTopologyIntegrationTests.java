package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real, initially dry world networks. Explicit casing ports distinguish a
 * crossing from a junction. Only counted native tanks/world buckets introduce
 * fluid; ordinary server ticks do all priming, redistribution and discharge. */
@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class FluidTopologyIntegrationTests {
    private static final String TEMPLATE = "kineticphysicstests.physics_test";
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_descendingSpatialSnakeTransfersWater", timeoutTicks = 1640)
    public static void descendingSpatialSnakeTransfersWater(GameTestHelper h) { snake(h, Fluids.WATER); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_descendingSpatialSnakeTransfersLava", timeoutTicks = 1640)
    public static void descendingSpatialSnakeTransfersLava(GameTestHelper h) { snake(h, Fluids.LAVA); }
    private static void snake(GameTestHelper h, Fluid fluid) {
        var n = new Network(h, "spatial_snake_" + fluid);
        BlockPos source = p(3, 10, 3), sink = p(12, 1, 5);
        n.tank(source, 2); n.tank(sink, 2);
        n.route(source, p(3, 9, 3), p(10, 9, 3), p(10, 6, 3), p(10, 6, 10),
            p(5, 6, 10), p(5, 3, 10), p(5, 3, 5), p(12, 3, 5), sink.above());
        n.build(); n.start(source, fluid, 4000);
        h.runAfterDelay(1600, () -> { n.expect(n.amount(sink, fluid) > 3000, "the descending bends actually transfer most of the supplied stock"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_tallReverseSDoesNotClimbWithoutEnoughHead", timeoutTicks = 1640)
    public static void tallReverseSDoesNotClimbWithoutEnoughHead(GameTestHelper h) { reverseS(h, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_naturallyPrimedPumpFeedsTheTallReverseS", timeoutTicks = 1640)
    public static void naturallyPrimedPumpFeedsTheTallReverseS(GameTestHelper h) { reverseS(h, true); }
    private static void reverseS(GameTestHelper h, boolean driven) {
        var n = new Network(h, "reverse_s_" + (driven ? "driven" : "gravity"));
        BlockPos source = p(3, 5, 3), sink = p(12, 1, 3), pump = source.below();
        n.tank(source, 2); n.tank(sink, 2);
        n.route(source, p(3, 2, 3), p(8, 2, 3), p(8, 9, 3), p(12, 9, 3), sink.above());
        if (driven) n.pump(pump, Direction.DOWN, p(4, 5, 3));
        n.build(); n.start(source, Fluids.WATER, 10000);
        // Negative-facing motor and the meshing cog cancel their signs;
        // Positive shaft rotation therefore pumps along the downward arrow.
        if (driven) h.runAfterDelay(40, () -> n.speed(p(4, 5, 3), 64));
        h.runAfterDelay(1600, () -> {
            if (driven) n.expect(n.amount(sink, Fluids.WATER) > 3000 && n.peak(p(8, 9, 3)) > 1, "a real wet driven pump sends water over the crest");
            else n.expect(n.amount(sink, Fluids.WATER) == 0,
                "an unpowered low reservoir cannot establish sustained delivery over the much higher crest; transient inertia may raise its front");
            n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_combWithBlindVerticalTeethStillTransfersWater", timeoutTicks = 1640)
    public static void combWithBlindVerticalTeethStillTransfersWater(GameTestHelper h) { comb(h, Fluids.WATER); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_combWithBlindVerticalTeethStillTransfersLava", timeoutTicks = 1640)
    public static void combWithBlindVerticalTeethStillTransfersLava(GameTestHelper h) { comb(h, Fluids.LAVA); }
    private static void comb(GameTestHelper h, Fluid fluid) {
        var n = new Network(h, "blind_comb_" + fluid);
        BlockPos source = p(3, 10, 8), sink = p(13, 1, 8);
        n.tank(source, 2); n.tank(sink, 2);
        n.route(source, p(3, 5, 8), p(13, 5, 8), sink.above());
        for (int x : new int[]{4, 6, 8, 10, 12}) {
            n.route(p(x, 5, 8), p(x, x % 4 == 0 ? 8 : 3, 8));
            n.route(p(x, 5, 8), p(x, 5, x % 4 == 0 ? 4 : 12));
        }
        n.build(); n.start(source, fluid, 12000);
        double[] beforeReopening = new double[1];
        h.runAfterDelay(120, () -> n.edge(p(13, 4, 8), p(13, 3, 8), false));
        h.runAfterDelay(650, () -> {
            beforeReopening[0] = n.amount(sink, fluid);
            n.edge(p(13, 4, 8), p(13, 3, 8), true);
        });
        h.runAfterDelay(1600, () -> {
            n.expect(n.amount(sink, fluid) > 3000 && n.amount(sink, fluid) > beforeReopening[0] + 500,
                "blind teeth do not prevent the reopened lower outlet receiving actual fluid");
            n.expect(n.peak(p(8, 8, 8)) == 0, "the closed high tooth retains its trapped incompressible air"); n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_closedFigureEightFillsWithWater", timeoutTicks = 1440)
    public static void closedFigureEightRetainsAirAgainstWater(GameTestHelper h) { figureEight(h, Fluids.WATER, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_closedFigureEightFillsWithLava", timeoutTicks = 1440)
    public static void closedFigureEightRetainsAirAgainstLava(GameTestHelper h) { figureEight(h, Fluids.LAVA, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_isolatingTheWetFigureEightKeepsItsActualStock", timeoutTicks = 1440)
    public static void isolatingTheWetFigureEightKeepsItsActualStock(GameTestHelper h) { figureEight(h, Fluids.WATER, true); }
    private static void figureEight(GameTestHelper h, Fluid fluid, boolean isolate) {
        var n = new Network(h, "figure_eight_" + fluid + "_isolate_" + isolate);
        BlockPos source = p(3, 10, 3), gate = p(3, 8, 3), below = gate.below();
        n.tank(source, 2); n.route(source, p(3, 3, 3));
        n.route(p(3, 3, 3), p(8, 3, 3), p(8, 3, 8), p(3, 3, 8), p(3, 3, 3));
        n.route(p(8, 3, 8), p(13, 3, 8), p(13, 3, 13), p(8, 3, 13), p(8, 3, 8));
        n.build(); n.start(source, fluid, 10000);
        double[] isolated = new double[2];
        if (isolate) {
            h.runAfterDelay(80, () -> {
                var receiver = p(13, 2, 13); n.tank(receiver, 1); n.route(p(13,3,13), receiver); n.build();
            });
            h.runAfterDelay(600, () -> {
                n.ports.get(gate).remove(Direction.DOWN); n.ports.get(below).remove(Direction.UP);
                n.ports.get(p(13,3,13)).remove(Direction.DOWN); n.build();
            });
            h.runAfterDelay(620, () -> { isolated[0] = n.amount(source, fluid); isolated[1] = n.pipeStock(fluid); });
        }
        h.runAfterDelay(1400, () -> {
            if (isolate) {
                n.expect(n.peak(p(13,3,13)) > 20, "the temporary receiver vents air and establishes real wet flow before isolation");
                n.expect(Math.abs(n.amount(source, fluid) - isolated[0]) < 1e-6
                    && Math.abs(n.pipeStock(fluid) - isolated[1]) < 1e-6, "closing both the inlet and vent retains the isolated wet stock");
            } else n.expect(n.pipeStock(fluid) == 0 && n.amount(source, fluid) == 10000,
                "closed lobes cannot remove trapped air through their only liquid inlet");
            n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_stackedRingsWithOffsetRisersReachTheLowerTank", timeoutTicks = 2040)
    public static void stackedRingsWithOffsetRisersReachTheLowerTank(GameTestHelper h) {
        var n = new Network(h, "offset_stacked_rings"); BlockPos source = p(3, 10, 3), sink = p(13, 1, 13);
        n.tank(source, 2); n.tank(sink, 2);
        for (int y : new int[]{3, 6, 9}) n.route(p(3, y, 3), p(13, y, 3), p(13, y, 13), p(3, y, 13), p(3, y, 3));
        n.route(source, p(3, 6, 3)); n.route(p(13, 9, 3), p(13, 3, 3));
        n.route(p(3, 6, 13), p(3, 3, 13)); n.route(p(13, 3, 13), sink.above());
        n.build(); n.start(source, Fluids.WATER, 12000);
        h.runAfterDelay(2000, () -> { n.expect(n.amount(sink, Fluids.WATER) > 1000, "offset ring connections produce real downhill delivery"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_threeDimensionalCageRedistributesWithoutCreatingWater", timeoutTicks = 2040)
    public static void threeDimensionalCageRedistributesWithoutCreatingWater(GameTestHelper h) {
        var n = new Network(h, "three_dimensional_cage"); BlockPos source = p(3, 10, 3), sink = p(13, 1, 13);
        n.tank(source, 2); n.tank(sink, 2);
        for (int y : new int[]{3, 6, 9}) n.route(p(3, y, 3), p(13, y, 3), p(13, y, 13), p(3, y, 13), p(3, y, 3));
        for (int x : new int[]{3, 13}) for (int z : new int[]{3, 13}) n.route(p(x, 3, z), p(x, 9, z));
        n.route(source, p(3, 9, 3)); n.route(p(13, 3, 13), sink.above());
        n.route(p(3, 6, 3), p(8, 6, 3), p(8, 6, 13), p(13, 6, 13));
        n.build(); n.start(source, Fluids.WATER, 14000);
        h.runAfterDelay(2000, () -> { n.expect(n.amount(sink, Fluids.WATER) > 1000, "the cage has actual flow through to its low outlet"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_ladderWithThreeRungsAndTwoLoopsTransfersWater", timeoutTicks = 1640)
    public static void ladderWithThreeRungsAndTwoLoopsTransfersWater(GameTestHelper h) {
        var n = new Network(h, "three_rung_ladder"); BlockPos source = p(4, 10, 8), sink = p(12, 1, 8);
        n.tank(source, 2); n.tank(sink, 2); n.route(source, p(4, 3, 8)); n.route(p(12, 8, 8), sink.above());
        for (int y : new int[]{3, 5, 8}) n.route(p(4, y, 8), p(12, y, 8));
        n.build(); n.start(source, Fluids.WATER, 10000);
        h.runAfterDelay(1600, () -> { n.expect(n.amount(sink, Fluids.WATER) > 3000 && n.peak(p(8, 5, 8)) > 1, "multiple wet rungs feed the outlet"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_unequalBraidedRoutesSplitAndRejoin", timeoutTicks = 1640)
    public static void unequalBraidedRoutesSplitAndRejoin(GameTestHelper h) {
        var n = new Network(h, "unequal_braid"); BlockPos source = p(3, 10, 8), sink = p(12, 1, 8);
        n.tank(source, 2); n.tank(sink, 2); n.route(source, p(3, 8, 8)); n.route(p(12, 5, 8), sink.above());
        n.route(p(3, 8, 8), p(12, 8, 8), p(12, 5, 8));
        n.route(p(3, 8, 8), p(3, 8, 3), p(7, 8, 3), p(7, 6, 3), p(12, 6, 3), p(12, 6, 8), p(12, 5, 8));
        n.route(p(3, 8, 8), p(3, 8, 13), p(6, 8, 13), p(6, 5, 13), p(12, 5, 13), p(12, 5, 8));
        n.build(); n.start(source, Fluids.WATER, 12000);
        h.runAfterDelay(1600, () -> { n.expect(n.amount(sink, Fluids.WATER) > 3000
            && n.peak(p(7, 8, 3)) > 1 && n.peak(p(6, 8, 13)) > 1, "the long unequal routes are used as well as the direct run"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_fourWayHydraFeedsEveryLowOutlet", timeoutTicks = 1640)
    public static void fourWayHydraFeedsEveryLowOutlet(GameTestHelper h) { hydra(h, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_closingOneHydraArmDoesNotCloseTheOtherOutlets", timeoutTicks = 1640)
    public static void closingOneHydraArmDoesNotCloseTheOtherOutlets(GameTestHelper h) { hydra(h, true); }
    private static void hydra(GameTestHelper h, boolean close) {
        var n = new Network(h, "four_outlet_hydra_close_" + close);
        BlockPos source = p(8, 10, 8), hub = p(8, 8, 8);
        n.tank(source, 2); n.route(source, hub);
        var sinks = List.of(p(3, 1, 8), p(13, 1, 8), p(8, 1, 3), p(8, 1, 13));
        for (var sink : sinks) { n.tank(sink, 2); n.route(hub, p(sink.getX(), 8, sink.getZ()), sink.above()); }
        n.build(); n.start(source, Fluids.WATER, 12000);
        if (close) {
            h.runAfterDelay(60, () -> n.edge(hub, hub.east(), false));
            h.runAfterDelay(120, () -> n.edge(hub, hub.east(), true));
            h.runAfterDelay(500, () -> n.fill(source, Fluids.WATER, 4000));
            h.runAfterDelay(900, () -> {
                for(var side:List.of(Direction.WEST,Direction.NORTH,Direction.SOUTH)) n.edge(hub,hub.relative(side),false);
                n.fill(source,Fluids.WATER,4000);
            });
        }
        h.runAfterDelay(1600, () -> {
            for (var sink : sinks) n.expect(n.amount(sink, Fluids.WATER) > 500, "every hydra outlet receives actual fluid: " + sink);
            if (!close) {
                double min = sinks.stream().mapToDouble(sink -> n.amount(sink, Fluids.WATER)).min().orElseThrow();
                double max = sinks.stream().mapToDouble(sink -> n.amount(sink, Fluids.WATER)).max().orElseThrow();
                n.expect(max - min < 2, "the four equivalent outlets agree to within integer receipt granularity: " + min + " / " + max);
            }
            n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_sixFaceHubFillsAllFiveBlindArms", timeoutTicks = 1440)
    public static void sixFaceHubPreservesAllFiveBlindAirArms(GameTestHelper h) {
        var n = new Network(h, "six_face_blind_hub"); BlockPos source = p(8, 10, 8), hub = p(8, 5, 8);
        n.tank(source, 2); n.route(source, hub);
        var tips = List.of(p(3, 5, 8), p(13, 5, 8), p(8, 5, 3), p(8, 5, 13), p(8, 2, 8));
        for (var tip : tips) n.route(hub, tip);
        n.build(); n.start(source, Fluids.WATER, 10000);
        h.runAfterDelay(1400, () -> {
            for (var tip : tips) n.expect(n.volume(tip) == 0, "the blind arm retains its real air inventory: " + tip);
            n.expect(n.volume(hub) == 0 && n.amount(source, Fluids.WATER) == 10000,
                "no blind arm can provide the air discharge needed to admit water"); n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_waffleMeshFeedsItsOppositeCorner", timeoutTicks = 2040)
    public static void waffleMeshFeedsItsOppositeCorner(GameTestHelper h) { waffle(h, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_waffleMeshKeepsWorkingAcrossWetLinkClosures", timeoutTicks = 2040)
    public static void waffleMeshKeepsWorkingAcrossWetLinkClosures(GameTestHelper h) { waffle(h, true); }
    private static void waffle(GameTestHelper h, boolean close) {
        var n = new Network(h, "waffle_close_" + close); BlockPos source = p(3, 10, 3), sink = p(12, 1, 12);
        n.tank(source, 2); n.tank(sink, 2);
        for (int value : new int[]{3, 6, 9, 12}) { n.route(p(3, 5, value), p(12, 5, value)); n.route(p(value, 5, 3), p(value, 5, 12)); }
        n.route(source, p(3, 5, 3)); n.route(p(12, 5, 12), sink.above());
        n.build(); n.start(source, Fluids.WATER, 14000);
        if (close) for (int tick : new int[]{80, 160, 240, 320})
            h.runAfterDelay(tick, () -> n.edge(p(6, 5, 6), p(7, 5, 6), tick % 160 == 0));
        h.runAfterDelay(2000, () -> { n.expect(n.amount(sink, Fluids.WATER) > 3000 && n.peak(p(9, 5, 9)) > 1,
            "the mesh and its alternative routes perform actual transfer"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_crossingPipesOnDifferentLevelsDoNotMixTheirFluids", timeoutTicks = 1640)
    public static void crossingPipesOnDifferentLevelsDoNotMixTheirFluids(GameTestHelper h) { nearMiss(h, true); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_touchingParallelPipesDoNotInventSideConnections", timeoutTicks = 1640)
    public static void touchingParallelPipesDoNotInventSideConnections(GameTestHelper h) { nearMiss(h, false); }
    private static void nearMiss(GameTestHelper h, boolean overpass) {
        var n = new Network(h, overpass ? "two_fluid_overpass" : "two_fluid_parallel_neighbours");
        BlockPos a = p(3, 10, overpass ? 8 : 5), b = p(overpass ? 8 : 3, 10, overpass ? 3 : 11);
        BlockPos x = p(13, 1, overpass ? 8 : 5), z = p(overpass ? 8 : 13, 1, overpass ? 13 : 11);
        for (var tank : List.of(a, b, x, z)) n.tank(tank, 2);
        if (overpass) {
            n.route(a, p(3, 6, 8), p(13, 6, 8), x.above());
            n.route(b, p(8, 5, 3), p(8, 5, 13), z.above());
        } else {
            n.route(a, p(3, 6, 5), p(3, 6, 7), p(13, 6, 7), p(13, 6, 5), x.above());
            n.route(b, p(3, 6, 11), p(3, 6, 8), p(13, 6, 8), p(13, 6, 11), z.above());
        }
        n.build(); h.runAfterDelay(20, () -> { n.fill(a, Fluids.WATER, 4000); n.fill(b, Fluids.LAVA, 4000); }); n.watch();
        h.runAfterDelay(1600, () -> {
            n.expect(n.amount(x, Fluids.WATER) > 3000 && n.amount(z, Fluids.LAVA) > 3000, "both independent lines deliver their actual fluid");
            n.expect(n.amount(x, Fluids.LAVA) == 0 && n.amount(z, Fluids.WATER) == 0, "geometric proximity does not connect the lines"); n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_joiningTwoWetWaterLoopsPreservesBothInventories", timeoutTicks = 1440)
    public static void joiningTwoWetWaterLoopsPreservesBothInventories(GameTestHelper h) { mergeLoops(h, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_joiningWetWaterAndLavaLoopsNeverRetypesOwnedLiquid", timeoutTicks = 1440)
    public static void joiningWetWaterAndLavaLoopsNeverRetypesOwnedLiquid(GameTestHelper h) { mergeLoops(h, true); }
    private static void mergeLoops(GameTestHelper h, boolean different) {
        var n = new Network(h, "merge_wet_loops_different_" + different); BlockPos a = p(3, 10, 3), b = p(13, 10, 13);
        n.tank(a, 2); n.tank(b, 2);
        n.route(a, p(3, 4, 3)); n.route(p(3, 4, 3), p(7, 4, 3), p(7, 4, 7), p(3, 4, 7), p(3, 4, 3));
        n.route(b, p(13, 4, 13)); n.route(p(9, 4, 9), p(13, 4, 9), p(13, 4, 13), p(9, 4, 13), p(9, 4, 9));
        n.tank(p(7,3,7),1); n.tank(p(9,3,9),1);
        n.route(p(7,4,7),p(7,3,7)); n.route(p(9,4,9),p(9,3,9));
        n.build(); h.runAfterDelay(20, () -> { n.fill(a, Fluids.WATER, 6000); n.fill(b, different ? Fluids.LAVA : Fluids.WATER, 6000); }); n.watch();
        h.runAfterDelay(300, () -> { n.route(p(7, 4, 7), p(9, 4, 7), p(9, 4, 9)); n.build();
            n.fill(a,Fluids.WATER,4000); });
        h.runAfterDelay(1400, () -> {
            n.expect(n.peak(p(7, 4, 7)) > 99 && n.peak(p(9, 4, 9)) > 99, "both original loops were wet before their real connection");
            n.expect(n.peak(p(8, 4, 7)) > 1, "the newly built bridge is genuinely invaded"); n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_joiningDifferentWetLinesKeepsBothIndependentOutletsWorking", timeoutTicks = 1640)
    public static void joiningDifferentWetLinesPreservesIdentityAtBlockedReceivers(GameTestHelper h) {
        var n = new Network(h, "merge_active_different_lines");
        BlockPos a = p(3, 10, 3), b = p(13, 10, 13), x = p(7, 1, 7), z = p(9, 1, 9);
        for (var tank : List.of(a, b, x, z)) n.tank(tank, 2);
        n.route(a, p(3, 4, 3), p(7, 4, 3), p(7, 4, 7), x.above());
        n.route(b, p(13, 4, 13), p(9, 4, 13), p(9, 4, 9), z.above());
        n.build(); h.runAfterDelay(20, () -> { n.fill(a, Fluids.WATER, 14000); n.fill(b, Fluids.LAVA, 14000); }); n.watch();
        double[] before = new double[2];
        for (int tick = 200; tick <= 700; tick += 100) h.runAfterDelay(tick, () -> {
            int refill = Math.min(5000, 16000 - (int) n.amount(a, Fluids.WATER));
            if (refill > 0) n.fill(a, Fluids.WATER, refill);
            int lavaRefill = Math.min(5000, 16000 - (int)n.amount(b,Fluids.LAVA));
            if(lavaRefill>0) n.fill(b,Fluids.LAVA,lavaRefill);
        });
        h.runAfterDelay(400, () -> {
            before[0] = n.amount(x, Fluids.WATER); before[1] = n.amount(z, Fluids.LAVA);
            n.expect(n.volume(p(7, 4, 7)) > 0 && n.volume(p(9, 4, 9)) > 0, "both actual line ends are wet before the bridge is built");
            n.route(p(7, 4, 7), p(9, 4, 7), p(9, 4, 9)); n.build();
        });
        boolean[] rejectedHead = {false}, coexisted = {false};
        h.onEachTick(() -> {
            if(h.getTick()<400) return;
            coexisted[0] |= n.ports.keySet().stream().filter(pos->!n.tankBlocks.contains(pos)).anyMatch(pos->{
                var saved=FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(),n.absolute(pos)));
                return saved.volume(new FluidStack(Fluids.WATER,1))>0 && saved.volume(new FluidStack(Fluids.LAVA,1))>0;
            });
            for(var target:List.of(x,z)) {
                var pipe=FluidPropagator.getPipe(h.getLevel(),n.absolute(target.above(2)));
                var saved=FluidKinetics.state(pipe); var head=saved.phases.peek(saved.front(Direction.DOWN));
                FluidTankBlockEntity tank=h.getBlockEntity(n.map(target));
                if(head!=null && !head.identity().isEmpty()
                    && tank.getTankInventory().fill(head.identity().copyWithAmount(1),FluidAction.SIMULATE)==0) rejectedHead[0]=true;
            }
        });
        h.runAfterDelay(1600, () -> {
            n.expect(rejectedHead[0], "joining unlike supplies can put an incompatible real FIFO head against an occupied native receiver");
            n.expect(n.amount(x,Fluids.LAVA)==0 && n.amount(z,Fluids.WATER)==0,
                "blocked receivers cannot silently replace their originally accepted fluid");
            n.expect(coexisted[0], "different identities really coexist during transfer through the joined pipe queue without in-pipe reaction");
            n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_addingADryBypassToTheWetNetworkDoesNotCreateOrLoseWater", timeoutTicks = 1640)
    public static void addingADryBypassToTheWetNetworkDoesNotCreateOrLoseWater(GameTestHelper h) {
        var n = new Network(h, "wet_network_dry_bypass"); BlockPos source = p(3, 10, 8), sink = p(13, 1, 8);
        n.tank(source, 2); n.tank(sink, 2); n.route(source, p(3, 6, 8), p(13, 6, 8), sink.above());
        n.build(); n.start(source, Fluids.WATER, 12000);
        h.runAfterDelay(80, () -> { n.route(p(5, 6, 8), p(5, 6, 3), p(11, 6, 3), p(11, 6, 8)); n.build(); });
        h.runAfterDelay(140, () -> { n.reload(p(5, 6, 8)); n.reload(p(11, 6, 3)); });
        h.runAfterDelay(1600, () -> { n.expect(n.amount(sink, Fluids.WATER) > 3000 && n.peak(p(8, 6, 3)) > 1,
            "new dry capacity and wet junction reloads preserve real downstream transfer"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_asymmetricSourcesFeedTheSameLoopWithWater", timeoutTicks = 1640)
    public static void asymmetricSourcesFeedTheSameLoopWithWater(GameTestHelper h) { multiSource(h, Fluids.WATER); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_asymmetricSourcesFeedTheSameLoopWithLava", timeoutTicks = 1640)
    public static void asymmetricSourcesFeedTheSameLoopWithLava(GameTestHelper h) { multiSource(h, Fluids.LAVA); }
    private static void multiSource(GameTestHelper h, Fluid fluid) {
        var n = new Network(h, "asymmetric_sources_" + fluid); BlockPos a = p(3, 10, 3), b = p(13, 8, 13), sink = p(8, 1, 8);
        n.tank(a, 2); n.tank(b, 2); n.tank(sink, 2);
        n.route(p(3, 4, 3), p(13, 4, 3), p(13, 4, 13), p(3, 4, 13), p(3, 4, 3));
        n.route(a, p(3, 4, 3)); n.route(b, p(13, 4, 13)); n.route(p(8, 4, 3), p(8, 4, 8), sink.above());
        n.build(); h.runAfterDelay(20, () -> { n.fill(a, fluid, 7000); n.fill(b, fluid, 5000); }); n.watch();
        h.runAfterDelay(1600, () -> { n.expect(n.amount(sink, fluid) > 3000 && n.amount(a, fluid) < 7000 && n.amount(b, fluid) < 5000,
            "two unequal actual reservoirs both participate without duplicated supply"); n.done(); });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_twinWorldInletsShareOnlyTheRealUTubeCapacity", timeoutTicks = 1440)
    public static void equalImmersedWorldHeadsRemainStill(GameTestHelper h) {
        var n = new Network(h, "twin_world_u_1100"); BlockPos a = p(4, 10, 8), b = p(12, 10, 8);
        n.route(a.below(), p(4, 8, 8), p(12, 8, 8), b.below());
        n.mouth(a.below(), Direction.UP); n.mouth(b.below(), Direction.UP); n.build();
        n.worldCell(a, Direction.DOWN); n.worldCell(b, Direction.DOWN);
        h.runAfterDelay(20, () -> { n.worldFill(a, Fluids.WATER); n.worldFill(b, Fluids.WATER); }); n.watch();
        h.runAfterDelay(1400, () -> {
            int remaining = (h.getBlockState(n.map(a)).getFluidState().isSource() ? 1 : 0) + (h.getBlockState(n.map(b)).getFluidState().isSource() ? 1 : 0);
            n.expect(remaining == 2 && n.pipeStock(Fluids.WATER) == 0,
                "equal world heads and stationary gravity-free air establish no initial flow and preserve both buckets"); n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_fallingBucketLeavesTheLowMouthOfTheThreeDimensionalNetwork", timeoutTicks = 1640)
    public static void fallingBucketLeavesTheLowMouthOfTheThreeDimensionalNetwork(GameTestHelper h) {
        var n = new Network(h, "world_bucket_spatial_outlet"); BlockPos a = p(3, 11, 3), b = p(13, 1, 13);
        n.route(a.below(), p(3, 8, 3), p(13, 8, 3), p(13, 5, 3), p(13, 5, 13), b.above());
        n.mouth(a.below(), Direction.UP); n.mouth(b.above(), Direction.DOWN); n.build();
        n.worldCell(a, Direction.DOWN); n.worldCell(b, Direction.UP);
        h.runAfterDelay(20, () -> n.worldFill(a, Fluids.WATER)); n.watch();
        h.runAfterDelay(1600, () -> {
            n.expect(!h.getBlockState(n.map(a)).getFluidState().isSource(), "the real world bucket is actually accepted");
            n.expect(h.getBlockState(n.map(b)).getFluidState().isSource() && n.pipeStock(Fluids.WATER) < 1e-6,
                "the entire bucket truly leaves the spatial descending network and forms a source"); n.done();
        });
    }

    @GameTest(template = TEMPLATE, batch = "fluid_topologies_seededSpatialMaze13HasRealDownhillTransfer", timeoutTicks = 2040)
    public static void seededSpatialMaze13HasRealDownhillTransfer(GameTestHelper h) { maze(h, 13, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_seededSpatialMaze97HasRealDownhillTransfer", timeoutTicks = 2040)
    public static void seededSpatialMaze97HasRealDownhillTransfer(GameTestHelper h) { maze(h, 97, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_seededSpatialMaze2026HasRealDownhillTransfer", timeoutTicks = 2040)
    public static void seededSpatialMaze2026HasRealDownhillTransfer(GameTestHelper h) { maze(h, 2026, false); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_mirroredSpatialMaze13PreservesItsBehaviour", timeoutTicks = 2040)
    public static void mirroredSpatialMaze13PreservesItsBehaviour(GameTestHelper h) { maze(h, 13, true); }
    @GameTest(template = TEMPLATE, batch = "fluid_topologies_mirroredSpatialMaze97PreservesItsBehaviour", timeoutTicks = 2040)
    public static void mirroredSpatialMaze97PreservesItsBehaviour(GameTestHelper h) { maze(h, 97, true); }
    private static void maze(GameTestHelper h, int seed, boolean mirror) {
        var n = new Network(h, "seeded_maze_" + seed + "_mirror_" + mirror, mirror);
        BlockPos source = p(4, 10, 4), sink = p(12, 1, 12);
        n.tank(source, 2); n.tank(sink, 2);
        // A guaranteed descending backbone makes throughput an expectation.
        // The fixed seed adds loops, elevated diversions and blind termini.
        n.route(source, p(4, 9, 4), p(12, 9, 4), p(12, 3, 4), p(12, 3, 12), sink.above());
        var rng = new Random(seed);
        for (int y : new int[]{3, 6, 9}) for (int x : new int[]{4, 8, 12}) for (int z : new int[]{4, 8, 12}) {
            var node = p(x, y, z);
            if (x < 12 && rng.nextBoolean()) n.route(node, p(x + 4, y, z));
            if (z < 12 && rng.nextBoolean()) n.route(node, p(x, y, z + 4));
            if (y < 9 && rng.nextBoolean()) n.route(node, p(x, y + 3, z));
        }
        // Nine actual risers and the lower comb join every random horizontal
        // island to the backbone, rather than silently testing dry islands.
        for (int x : new int[]{4, 8, 12}) for (int z : new int[]{4, 8, 12}) n.route(p(x, 3, z), p(x, 9, z));
        for (int x : new int[]{4, 8, 12}) n.route(p(x, 3, 4), p(x, 3, 12));
        n.route(p(4, 3, 4), p(12, 3, 4));
        n.build(); n.start(source, Fluids.WATER, 14000);
        h.runAfterDelay(2000, () -> { n.expect(n.amount(sink, Fluids.WATER) > 1000, "the deterministic spatial maze uses its real low outlet"); n.done(); });
    }

    private static final class Network {
        final GameTestHelper h;
        final String name;
        final boolean mirror;
        final Map<BlockPos, EnumSet<Direction>> ports = new LinkedHashMap<>();
        final Set<BlockPos> tankBlocks = new HashSet<>();
        final List<BlockPos> tanks = new ArrayList<>(), world = new ArrayList<>();
        final Map<BlockPos, Direction> pumps = new LinkedHashMap<>();
        final Map<BlockPos, Direction> motors = new LinkedHashMap<>();
        final Map<Fluid, Double> introduced = new LinkedHashMap<>();
        final Map<BlockPos, Double> peaks = new LinkedHashMap<>();
        double maximumError, maximumFlow;
        Network(GameTestHelper h, String name) { this(h, name, false); }
        Network(GameTestHelper h, String name, boolean mirror) {
            this.h = h; this.name = name; this.mirror = mirror;
            var bounds = h.getBounds();
            for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
                for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++) h.getLevel().setChunkForced(x, z, true);
        }
        BlockPos map(BlockPos pos) { return mirror ? p(16 - pos.getX(), pos.getY(), pos.getZ()) : pos; }
        Direction map(Direction side) { return mirror && side.getAxis() == Direction.Axis.X ? side.getOpposite() : side; }
        BlockPos absolute(BlockPos pos) { return h.absolutePos(map(pos)); }
        void expect(boolean condition, String message) { h.assertTrue(condition, name + ": " + message); }
        void tank(BlockPos base, int height) { tanks.add(base); for (int y = 0; y < height; y++) tankBlocks.add(base.above(y)); }
        void mouth(BlockPos pos, Direction side) { ports.computeIfAbsent(pos, key -> EnumSet.noneOf(Direction.class)).add(side); }
        void route(BlockPos... corners) {
            for (int i = 1; i < corners.length; i++) {
                BlockPos at = corners[i - 1], end = corners[i];
                int dx = end.getX() - at.getX(), dy = end.getY() - at.getY(), dz = end.getZ() - at.getZ();
                expect((dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0) == 1, "each route leg follows one actual block axis");
                Direction direction = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST)
                    : dy != 0 ? (dy > 0 ? Direction.UP : Direction.DOWN) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                while (!at.equals(end)) {
                    var next = at.relative(direction); mouth(at, direction); mouth(next, direction.getOpposite()); at = next;
                }
            }
        }
        void pump(BlockPos pos, Direction facing, BlockPos motor) {
            pumps.put(pos, facing); motors.put(motor, Direction.get(Direction.AxisDirection.NEGATIVE, facing.getAxis()));
        }
        void build() {
            for (var pos : tankBlocks) h.setBlock(map(pos), AllBlocks.FLUID_TANK.getDefaultState());
            for (var entry : ports.entrySet()) {
                var pos = entry.getKey(); if (tankBlocks.contains(pos)) continue;
                var block = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
                for (var side : Direction.values()) block = block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(map(side)), entry.getValue().contains(side));
                if (pumps.containsKey(pos)) block = AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, map(pumps.get(pos)));
                h.setBlock(map(pos), block);
            }
            for (var entry : pumps.entrySet()) {
                var cog = entry.getValue().getAxis() == Direction.Axis.Y ? entry.getKey().east() : entry.getKey().above();
                h.setBlock(map(cog), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, entry.getValue().getAxis()));
            }
            for (var entry : motors.entrySet()) {
                h.setBlock(map(entry.getKey()), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, map(entry.getValue())));
                speed(entry.getKey(), 0);
            }
            FluidKinetics.invalidate(h.getLevel());
        }
        void edge(BlockPos a, BlockPos b, boolean open) {
            Direction side = Direction.fromDelta(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ());
            expect(side != null, "a changed link joins adjacent actual cells");
            for (var pos : List.of(a, b)) {
                var direction = pos.equals(a) ? side : side.getOpposite();
                var block = h.getBlockState(map(pos)); expect(AllBlocks.ENCASED_FLUID_PIPE.has(block), "port changes retain the native pipe entity");
                h.setBlock(map(pos), block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(map(direction)), open));
            }
            FluidKinetics.invalidate(h.getLevel());
        }
        void speed(BlockPos pos, int speed) { CreativeMotorBlockEntity motor = h.getBlockEntity(map(pos)); motor.generatedSpeed.setValue(speed); }
        void fill(BlockPos pos, Fluid fluid, int amount) {
            FluidTankBlockEntity tank = h.getBlockEntity(map(pos));
            expect(tank.getTankInventory().fill(new FluidStack(fluid, amount), FluidAction.EXECUTE) == amount, "the native source receives exactly the counted stock");
            introduced.merge(fluid, (double) amount, Double::sum); FluidKinetics.invalidate(h.getLevel());
        }
        void start(BlockPos source, Fluid fluid, int amount) { h.runAfterDelay(20, () -> fill(source, fluid, amount)); watch(); }
        double amount(BlockPos pos, Fluid fluid) {
            FluidTankBlockEntity tank = h.getBlockEntity(map(pos)); var stock = tank.getTankInventory().getFluid();
            return stock.getFluid().isSame(fluid) ? stock.getAmount() : 0;
        }
        void worldCell(BlockPos pos, Direction connected) {
            world.add(pos); h.setBlock(map(pos), Blocks.AIR);
            for (var side : Direction.values()) if (side != connected && side != Direction.UP) h.setBlock(map(pos.relative(side)), Blocks.STONE);
        }
        void worldFill(BlockPos pos, Fluid fluid) {
            h.setBlock(map(pos), fluid.isSame(Fluids.WATER) ? Blocks.WATER : Blocks.LAVA);
            introduced.merge(fluid, 1000d, Double::sum); FluidKinetics.invalidate(h.getLevel());
        }
        void reload(BlockPos pos) {
            BlockEntity old = h.getBlockEntity(map(pos)); var tag = old.saveWithFullMetadata(h.getLevel().registryAccess());
            var block = h.getBlockState(map(pos)); var level = h.getLevel(); level.removeBlockEntity(absolute(pos));
            var replacement = BlockEntity.loadStatic(absolute(pos), block, tag, level.registryAccess());
            expect(replacement != null, "the real pipe can be reloaded"); level.setBlockEntity(replacement);
            if (replacement instanceof SmartBlockEntity smart) smart.initialize(); FluidKinetics.invalidate(level);
        }
        double volume(BlockPos pos) { return FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), absolute(pos))).volume(); }
        double peak(BlockPos pos) { return peaks.getOrDefault(pos, 0d); }
        double pipeStock(Fluid fluid) {
            double value = 0; var ends = new IdentityHashMap<OpenEndedPipe, Boolean>();
            for (var pos : ports.keySet()) if (!tankBlocks.contains(pos)) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), absolute(pos)); expect(pipe != null, "the placed network cell exists: " + pos);
                var saved = FluidKinetics.state(pipe); double volume = saved.volume();
                if (!(Double.isFinite(volume) && volume >= 0 && volume <= 300 + 1e-6))
                    expect(false, "each native cell keeps finite bounded actual inventory: " + pos + " / " + volume);
                if (saved.phases == null) { if (saved.fluid.getFluid().isSame(fluid)) value += volume; }
                else for (var part : saved.parcels()) if (part.identity().getFluid().isSame(fluid)) value += part.amount();
                for (var buffer : saved.buffers.values()) {
                    expect(Double.isFinite(buffer.amount) && buffer.amount >= 0, "native interface reservations are finite and nonnegative");
                    for (var part : buffer.parcels()) if (part.identity().getFluid().isSame(fluid)) value += part.amount();
                }
                for (double flow : saved.outward.values()) {
                    if (!Double.isFinite(flow)) expect(false, "native edge flows remain finite");
                    maximumFlow = Math.max(maximumFlow, Math.abs(flow));
                }
                if (volume > peaks.getOrDefault(pos, 0d)) peaks.put(pos, volume);
                if (pipe.interfaces != null) for (var connection : pipe.interfaces.values()) {
                    connection.manageSource(h.getLevel(), pipe.getPos(), pipe.blockEntity);
                    var source = ((IPipePressure) connection).differentialcreate$source();
                    if (source instanceof OpenEndedPipe end && ends.put(end, true) == null) {
                        var stock = end.provideHandler().getCapability().getFluidInTank(0);
                        if (stock.getFluid().isSame(fluid)) value += stock.getAmount();
                    }
                }
            }
            return value;
        }
        double total(Fluid fluid) {
            double value = pipeStock(fluid); var controllers = new HashSet<BlockPos>();
            for (var pos : tanks) {
                FluidTankBlockEntity tank = h.getBlockEntity(map(pos)); if (controllers.add(tank.getController())) value += amount(pos, fluid);
            }
            for (var pos : world) { var stock = h.getBlockState(map(pos)).getFluidState(); if (stock.isSource() && stock.getType().isSame(fluid)) value += 1000; }
            var ends = new IdentityHashMap<OpenEndedPipe, Boolean>();
            for (var pos : ports.keySet()) if (!tankBlocks.contains(pos)) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), absolute(pos));
                if (pipe.interfaces != null) for (var connection : pipe.interfaces.values()) {
                    var source = ((IPipePressure) connection).differentialcreate$source();
                    if (source instanceof OpenEndedPipe end && ends.put(end, true) == null) {
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
                if (h.getTick() <= 20) return;
                for (var entry : introduced.entrySet()) {
                    double actual = total(entry.getKey()), error = Math.abs(actual - entry.getValue()); maximumError = Math.max(maximumError, error);
                    expect(error < 1e-6, "actual " + entry.getKey() + " is conserved at tick " + h.getTick() + ": " + actual + " / " + entry.getValue());
                }
                if (Boolean.getBoolean("differentialcreate.topologyAudit") && h.getTick() % 400 == 0) {
                    var stock = new LinkedHashMap<BlockPos, String>(); for (var pos : tanks) stock.put(pos, amount(pos, Fluids.WATER) + "W/" + amount(pos, Fluids.LAVA) + "L");
                    System.out.println("FLUID_TOPOLOGY_SAMPLE name=" + name + " tick=" + h.getTick() + " tanks=" + stock + " introduced=" + introduced + " error=" + maximumError);
                    if (Boolean.getBoolean("differentialcreate.topologyDetails")) for (var pos : ports.keySet()) if (!tankBlocks.contains(pos)) {
                        var pipe = FluidPropagator.getPipe(h.getLevel(), absolute(pos)); var saved = FluidKinetics.state(pipe);
                        System.out.println("FLUID_TOPOLOGY_CELL name=" + name + " tick=" + h.getTick() + " pos=" + pos + " volume=" + saved.volume()
                            + " fluid=" + saved.fluid + " buffers=" + saved.buffers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().amount).toList()
                            + " flows=" + saved.outward);
                    }
                }
            });
        }
        void done() {
            long pipes = ports.keySet().stream().filter(pos -> !tankBlocks.contains(pos)).count();
            System.out.println("FLUID_TOPOLOGY_PASS name=" + name + " tick=" + h.getTick() + " pipes=" + pipes
                + " water=" + total(Fluids.WATER) + " lava=" + total(Fluids.LAVA) + " max_flow=" + maximumFlow + " error=" + maximumError);
            h.succeed();
        }
    }
}
