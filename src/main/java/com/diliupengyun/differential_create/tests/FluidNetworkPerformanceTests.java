package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.MachiningClock;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Opt-in server benchmark. Times the real common physics clock, not a solver
 * kernel extrapolated to TPS. Each sample advances one complete 50-ms game tick,
 * including front events, pump/shaft coupling, inventory commits and publication.
 * World generation, placement, warmup and teardown are outside the timings. */
@GameTestHolder("differentialcreate_perf")
@PrefixGameTestTemplate(false)
public final class FluidNetworkPerformanceTests {
    private record Case(String kind, int size, int pumps) {}
    private static final List<Case> CASES = List.of(
        new Case("straight", 64, 1), new Case("straight", 256, 1), new Case("straight", 1024, 1),
        new Case("branches", 16, 0), new Case("branches", 64, 0), new Case("branches", 256, 0),
        new Case("loops", 5, 4), new Case("loops", 10, 16), new Case("loops", 19, 64),
        new Case("slugs", 5, 0), new Case("slugs", 10, 0), new Case("slugs", 19, 0));
    private static final BlockPos ORIGIN = new BlockPos(4, 3, 4);

    @GameTest(template = "physics_test", timeoutTicks = 600)
    public static void fluidNetworks(GameTestHelper h) {
        String selected = System.getProperty("differentialcreate.perf.case", "all");
        if (!List.of("all", "straight", "branches", "loops", "slugs").contains(selected))
            throw new IllegalArgumentException("Unknown fluid performance case: " + selected);
        run(h, 0);
    }

    private static void run(GameTestHelper h, int index) {
        if (index == CASES.size()) { h.succeed(); return; }
        Case test = CASES.get(index);
        String selected = System.getProperty("differentialcreate.perf.case", "all");
        if (!selected.equals("all") && !selected.equals(test.kind)) { run(h, index + 1); return; }
        var scene = new Scene(h, test);
        try { scene.build(); }
        catch (RuntimeException | Error failure) { scene.remove(); throw failure; }
        h.runAfterDelay(12, () -> {
            try {
                scene.fill();
                double initial = scene.volume();
                FluidKinetics.invalidate(h.getLevel());
                long begin = System.nanoTime();
                var captured = FluidKinetics.capture(h.getLevel());
                double captureMs = (System.nanoTime() - begin) / 1e6;
                h.assertTrue(captured.pipeCount() == scene.channels.size(),
                    "only the current scene participates in the clock: " + captured.pipeCount() + " / " + scene.channels.size());
                // The same evolving scene is warmed and measured. No restored frozen
                // snapshot hides contact/cache changes or repeated front events.
                for (int tick = 0; tick < 24; tick++) {
                    MachiningClock.advance(h.getLevel());
                    if (test.kind.equals("slugs") && tick % 6 == 5)
                        System.out.println("FLUID_NETWORK_WARMUP pipes=" + scene.channels.size() + " tick=" + (tick + 1) + " metrics=" + captured.metrics());
                }
                captured.resetMetrics();
                double[] timings = new double[96];
                for (int tick = 0; tick < timings.length; tick++) {
                    long start = System.nanoTime();
                    MachiningClock.advance(h.getLevel());
                    timings[tick] = (System.nanoTime() - start) / 1e6;
                }
                double after = scene.volume();
                h.assertTrue(Math.abs(after - initial) < 1e-5,
                    "benchmark conserves inventory in " + test + ": " + after + " / " + initial);
                for (var pos : scene.channels.keySet()) {
                    var state = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                    h.assertTrue(state.outward.values().stream().allMatch(Double::isFinite)
                        && Double.isFinite(state.pumpFlow), "finite motion after the benchmark");
                    if (test.kind.equals("loops")) h.assertTrue(state.contents.values().stream().allMatch(c -> c.fraction() == 1)
                        && (!(pipeIsPump(h, pos)) || state.core.fraction() == 1), "a full closed loop cannot invent empty gaps");
                }
                double mean = Arrays.stream(timings).average().orElseThrow();
                var metrics = captured.metrics();
                Arrays.sort(timings);
                System.out.printf(Locale.ROOT,
                    "FLUID_NETWORK_PERF kind=%s pipes=%d pumps=%d ticks=%d median_ms=%.6f p95_ms=%.6f mean_ms=%.6f max_ms=%.6f capture_ms=%.6f volume_error_mb=%.9g steps=%d joint_iterations=%d retries=%d shortest_tick=%.9g%n",
                    test.kind, scene.channels.size(), scene.motors.size(), timings.length,
                    .5 * (timings[47] + timings[48]), timings[(int) Math.ceil(.95 * timings.length) - 1], mean,
                    timings[timings.length - 1], captureMs, after - initial, metrics.acceptedSteps(), metrics.jointIterations(),
                    metrics.retries(), metrics.shortestMinutes() / com.diliupengyun.differential_create.kinetics.RotationMath.TICK_MINUTES);
            } finally { scene.remove(); }
            run(h, index + 1);
        });
    }

    private static boolean pipeIsPump(GameTestHelper h, BlockPos pos) {
        return h.getBlockEntity(pos) instanceof com.simibubi.create.content.fluids.pump.PumpBlockEntity;
    }

    private static final class Scene {
        final GameTestHelper h;
        final Case test;
        final Map<BlockPos, List<Direction>> channels = new LinkedHashMap<>();
        final List<BlockPos> placed = new ArrayList<>(), tanks = new ArrayList<>();
        final List<CreativeMotorBlockEntity> motors = new ArrayList<>();
        Scene(GameTestHelper h, Case test) { this.h = h; this.test = test; }
        void force(BlockPos pos) {
            BlockPos absolute = h.absolutePos(pos);
            h.getLevel().setChunkForced(absolute.getX() >> 4, absolute.getZ() >> 4, true);
        }
        void tank(BlockPos pos, int height) {
            for (int y = 0; y < height; y++) {
                force(pos.above(y)); h.setBlock(pos.above(y), AllBlocks.FLUID_TANK.getDefaultState()); placed.add(pos.above(y));
            }
            tanks.add(pos);
        }
        void build() {
            if (test.kind.equals("straight")) {
                for (int x = 0; x < test.size; x++) channels.put(ORIGIN.east(x), List.of(Direction.WEST, Direction.EAST));
                tank(ORIGIN.west(), 4); tank(ORIGIN.east(test.size), 4);
            } else if (test.kind.equals("branches")) {
                for (int x = 0; x <= 2 * test.size; x++) channels.put(ORIGIN.east(x), x % 2 == 0 && x < 2 * test.size
                    ? List.of(Direction.WEST, Direction.EAST, Direction.SOUTH) : List.of(Direction.WEST, Direction.EAST));
                for (int j = 0; j < test.size; j++) {
                    for (int z = 1; z <= 2; z++) channels.put(ORIGIN.east(2 * j).south(z), List.of(Direction.NORTH, Direction.SOUTH));
                    tank(ORIGIN.east(2 * j).south(3), 1);
                }
                tank(ORIGIN.west(), 4); tank(ORIGIN.east(2 * test.size + 1), 1);
            } else {
                int width = 2 * test.size - 1;
                for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) if (x % 2 == 0 || z % 2 == 0) {
                    var directions = new ArrayList<Direction>();
                    if (z % 2 == 0 && x > 0) directions.add(Direction.WEST);
                    if (z % 2 == 0 && x + 1 < width) directions.add(Direction.EAST);
                    if (x % 2 == 0 && z > 0) directions.add(Direction.NORTH);
                    if (x % 2 == 0 && z + 1 < width) directions.add(Direction.SOUTH);
                    channels.put(ORIGIN.offset(x, 0, z), List.copyOf(directions));
                }
            }
            for (var entry : channels.entrySet()) {
                force(entry.getKey());
                var state = AllBlocks.FLUID_PIPE.getDefaultState();
                for (Direction side : Direction.values()) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), entry.getValue().contains(side));
                h.setBlock(entry.getKey(), state); placed.add(entry.getKey());
            }
            int installed = 0;
            for (var pos : channels.keySet()) if (installed < test.pumps
                && (test.kind.equals("straight") ? pos.equals(ORIGIN.east(test.size / 2))
                    : (pos.getX() - ORIGIN.getX()) % 2 == 1)) {
                h.setBlock(pos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
                BlockPos cog = pos.above(), drive = cog.west();
                h.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
                h.setBlock(drive, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
                placed.add(cog); placed.add(drive);
                CreativeMotorBlockEntity motor = h.getBlockEntity(drive); motor.generatedSpeed.setValue(0); motors.add(motor);
                installed++;
            }
            h.assertTrue(installed == test.pumps, "all benchmark pumps were installed");
        }
        void fill() {
            var water = new FluidStack(Fluids.WATER, 1);
            if (!tanks.isEmpty()) {
                // The first listed branches are sinks; the source is the west tank.
                FluidTankBlockEntity source = h.getBlockEntity(ORIGIN.west());
                source.getTankInventory().fill(water.copyWithAmount(source.getTankInventory().getCapacity()), FluidAction.EXECUTE);
            }
            for (var entry : channels.entrySet()) {
                var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(entry.getKey()));
                // Very long fixtures extend beyond the harness' entity-ticking
                // radius. Initialise their ordinary Create behaviours explicitly
                // during setup, so every loaded channel participates in the timer.
                if (pipe.interfaces == null) pipe.blockEntity.initialize();
                h.assertTrue(pipe.interfaces != null, "benchmark channel has native interfaces at " + entry.getKey());
                FluidKinetics.register(pipe);
                var state = FluidKinetics.state(pipe); state.fluid = water.copy();
                state.outward.clear();
                for (var side : pipe.interfaces.keySet()) state.contents.put(side, test.kind.equals("slugs") ? PipeContents.EMPTY : PipeContents.FULL);
                if (test.kind.equals("slugs")) {
                    for (var side : pipe.interfaces.keySet()) state.outward.put(side, side == Direction.EAST ? 12d : side == Direction.WEST ? -12d : 0d);
                    if ((entry.getKey().getX() - ORIGIN.getX()) % 4 == 1 && (entry.getKey().getZ() - ORIGIN.getZ()) % 4 == 0)
                        state.contents.put(Direction.WEST, PipeContents.of(.2, .8));
                }
                if (pipe.blockEntity instanceof com.simibubi.create.content.fluids.pump.PumpBlockEntity) {
                    state.core = PipeContents.FULL; state.pumpFlow = 0; state.pumpFacing = Direction.EAST;
                }
            }
            for (var motor : motors) motor.generatedSpeed.setValue(64);
            NetworkTopology.flush();
            for (var motor : motors) {
                var ratios = ((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$getRatios();
                double rpm = SourcePhysics.curve(motor).noLoadSpeed(); float reference = ratios.get(motor);
                ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = rpm * ratio / reference);
            }
            FluidKinetics.invalidate(h.getLevel());
        }
        double volume() {
            double value = FluidKinetics.ownedVolume(h.getLevel());
            for (var pos : tanks) {
                FluidTankBlockEntity tank = h.getBlockEntity(pos); value += tank.getTankInventory().getFluidAmount();
            }
            return value;
        }
        void remove() {
            for (int i = placed.size() - 1; i >= 0; i--) h.setBlock(placed.get(i), Blocks.AIR.defaultBlockState());
            NetworkTopology.flush(); FluidKinetics.invalidate(h.getLevel());
        }
    }
}
