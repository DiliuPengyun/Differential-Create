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
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
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
 * including the coarse fluid update, pump loads, inventory commits and publication.
 * World generation, placement, warmup and teardown are outside the timings. */
@GameTestHolder("differentialcreate_perf")
@PrefixGameTestTemplate(false)
public final class FluidNetworkPerformanceTests {
    private record Case(String kind, int size, int pumps) { boolean mixed() { return kind.equals("mixed") || kind.equals("endurance"); } }
    private static final List<Case> CASES = List.of(
        new Case("straight", 64, 1), new Case("straight", 256, 1), new Case("straight", 1024, 1),
        new Case("branches", 16, 0), new Case("branches", 64, 0), new Case("branches", 256, 0),
        new Case("loops", 5, 4), new Case("loops", 10, 16), new Case("loops", 19, 64),
        new Case("slugs", 5, 0), new Case("slugs", 10, 0), new Case("slugs", 19, 0),
        new Case("idle", 64, 0), new Case("idle", 256, 0), new Case("idle", 1024, 0),
        new Case("mixed", 5, 4), new Case("mixed", 10, 16), new Case("mixed", 19, 64),
        new Case("endurance", 5, 4), new Case("mutation", 5, 4), new Case("mutation", 19, 64));
    private static final BlockPos ORIGIN = new BlockPos(4, 3, 4);

    @GameTest(template = "physics_test", timeoutTicks = 600)
    public static void fluidNetworks(GameTestHelper h) {
        String selected = System.getProperty("differentialcreate.perf.case", "all");
        if (!List.of("all", "straight", "branches", "loops", "slugs", "idle", "mixed", "endurance", "mutation").contains(selected))
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
                double[] phaseStock = scene.phaseStock();
                var actualPumps = scene.channels.keySet().stream().map(h::getBlockEntity)
                    .filter(IPumpDynamics.class::isInstance).map(IPumpDynamics.class::cast).toList();
                FluidKinetics.invalidate(h.getLevel());
                long begin = System.nanoTime();
                var captured = FluidKinetics.capture(h.getLevel());
                double captureMs = (System.nanoTime() - begin) / 1e6;
                h.assertTrue(captured.pipeCount() == scene.channels.size(),
                    "only the current scene participates in the clock: " + captured.pipeCount() + " / " + scene.channels.size());
                // The same evolving scene is warmed and measured. No restored frozen
                // snapshot hides redistribution or topology/cache changes.
                double movement = 0;
                int maximumParts = 0;
                for (int tick = 0; tick < (test.kind.equals("endurance") ? 1200 : 24); tick++) {
                    MachiningClock.advance(h.getLevel());
                    movement = Math.max(movement, scene.maximumFlow());
                    maximumParts = Math.max(maximumParts, scene.parts());
                }
                captured.resetMetrics();
                var measuredQueues = scene.queueSnapshot();
                double[] timings = new double[96];
                double acceptedPumpFlow = 0;
                long mutationSteps = 0, mutationIterations = 0, mutationRetries = 0;
                double mutationShortest = Double.POSITIVE_INFINITY;
                for (int tick = 0; tick < timings.length; tick++) {
                    long start = System.nanoTime();
                    if (test.kind.equals("mutation")) scene.changeGate((tick & 1) != 0);
                    MachiningClock.advance(h.getLevel());
                    timings[tick] = (System.nanoTime() - start) / 1e6;
                    if (test.kind.equals("mutation")) {
                        captured = FluidKinetics.capture(h.getLevel());
                        var update = captured.metrics();
                        mutationSteps += update.acceptedSteps(); mutationIterations += update.jointIterations(); mutationRetries += update.retries();
                        mutationShortest = Math.min(mutationShortest, update.shortestMinutes());
                    }
                    movement = Math.max(movement, scene.maximumFlow());
                    for (var pump : actualPumps) acceptedPumpFlow = Math.max(acceptedPumpFlow, Math.abs(pump.differentialcreate$flow()));
                    maximumParts = Math.max(maximumParts, scene.parts());
                }
                double after = scene.volume();
                if(test.kind.equals("slugs") || test.mixed()) h.assertTrue(scene.channels.keySet().stream().anyMatch(pos ->
                    !measuredQueues.get(pos).equals(FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(),h.absolutePos(pos))).parcels())),
                    "the measured interval must change actual ordered inventory after warmup, not only retain saved motion");
                h.assertTrue(Math.abs(after - initial) < 1e-5,
                    "benchmark conserves inventory in " + test + ": " + after + " / " + initial);
                if (test.kind.equals("idle")) h.assertTrue(movement == 0 && !captured.active(), "idle air has no solver work or invented motion");
                else h.assertTrue(movement > 1e-6, "a bounded-work benchmark must move actual liquid: " + test);
                if (!actualPumps.isEmpty()) h.assertTrue(acceptedPumpFlow > 1e-6,
                    "the measured interval has newly accepted native pump flow, not just old saved pipe velocity: " + test);
                if (test.kind.equals("slugs") || test.mixed()) h.assertTrue(scene.channels.keySet().stream().anyMatch(pos ->
                    Math.abs(FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos))).volume()
                        - scene.initialWater.get(pos)) > 1e-6),
                    "the benchmark redistributes real liquid instead of displaying a deferred saved velocity");
                if (test.mixed()) {
                    var finalStock = scene.phaseStock();
                    for(int phase=0;phase<phaseStock.length;phase++) h.assertTrue(Math.abs(finalStock[phase]-phaseStock[phase])<1e-6,
                        "mixed circulation preserves each separate real fluid and virtual air");
                    h.assertTrue(maximumParts < 128 * scene.channels.size(), "loop queues do not proliferate microscopic interfaces: " + maximumParts);
                }
                for (var pos : scene.channels.keySet()) {
                    var state = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                    h.assertTrue(state.outward.values().stream().allMatch(Double::isFinite)
                        && Double.isFinite(state.pumpFlow), "finite motion after the benchmark");
                    // Full coarse cells may differ by a few ulps after a pressure
                    // solve. Check real inventory as well as the rendered fraction.
                    if (test.kind.equals("loops") || test.kind.equals("mutation")) h.assertTrue(Math.abs(state.volume() - scene.initialWater.get(pos)) < 1e-7
                        && state.contents.values().stream().allMatch(c -> c.fraction() >= 1 - 1e-9)
                        && (!(pipeIsPump(h, pos)) || state.core.fraction() >= 1 - 1e-9),
                        "a full closed loop cannot invent empty gaps at " + pos + ": volume=" + state.volume()
                            + ", initial=" + scene.initialWater.get(pos) + ", contents=" + state.contents
                            + ", core=" + state.core);
                }
                double mean = Arrays.stream(timings).average().orElseThrow();
                var metrics = captured.metrics();
                if (test.kind.equals("mutation")) {
                    metrics = new FluidKinetics.Frame.Metrics(mutationSteps, mutationIterations, mutationRetries, mutationShortest);
                    h.assertTrue(mutationSteps == timings.length && mutationIterations == timings.length && mutationRetries == 0,
                        "every actual gate change executes one bounded full clock update without history retries");
                }
                Arrays.sort(timings);
                System.out.printf(Locale.ROOT,
                    "FLUID_NETWORK_PERF kind=%s pipes=%d pumps=%d ticks=%d median_ms=%.6f p95_ms=%.6f mean_ms=%.6f max_ms=%.6f capture_ms=%.6f volume_error_mb=%.9g max_flow_mb_tick=%.9g accepted_pump_flow=%.9g steps=%d joint_iterations=%d retries=%d shortest_tick=%.9g max_parcels=%d%n",
                    test.kind, scene.channels.size(), scene.motors.size(), timings.length,
                    .5 * (timings[47] + timings[48]), timings[(int) Math.ceil(.95 * timings.length) - 1], mean,
                    timings[timings.length - 1], captureMs, after - initial, movement, acceptedPumpFlow, metrics.acceptedSteps(), metrics.jointIterations(),
                    metrics.retries(), metrics.shortestMinutes() / com.diliupengyun.differential_create.kinetics.RotationMath.TICK_MINUTES, maximumParts);
                long[] encoding=new long[3]; int bytes=0;
                for(int sample=0;sample<3;sample++) {
                    long start=System.nanoTime(); bytes=scene.clientBytes(); encoding[sample]=System.nanoTime()-start;
                }
                Arrays.sort(encoding);
                System.out.printf(Locale.ROOT,"FLUID_CLIENT_TAG_PERF kind=%s pipes=%d bytes=%d median_ms=%.6f%n",
                    test.kind,scene.channels.size(),bytes,encoding[1]/1e6);
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
        final Map<BlockPos, Double> initialWater = new LinkedHashMap<>();
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
            if (test.kind.equals("straight") || test.kind.equals("idle")) {
                for (int x = 0; x < test.size; x++) channels.put(ORIGIN.east(x), List.of(Direction.WEST, Direction.EAST));
                if (!test.kind.equals("idle")) { tank(ORIGIN.west(), 4); tank(ORIGIN.east(test.size), 4); }
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
                var state = test.kind.equals("mutation") ? AllBlocks.ENCASED_FLUID_PIPE.getDefaultState() : AllBlocks.FLUID_PIPE.getDefaultState();
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
        void changeGate(boolean open) {
            BlockPos a = ORIGIN.south(), b = a.south();
            h.setBlock(a, h.getBlockState(a).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.SOUTH), open));
            h.setBlock(b, h.getBlockState(b).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(Direction.NORTH), open));
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
                if (test.kind.equals("idle")) { initialWater.put(entry.getKey(), 0d); continue; }
                state.outward.clear();
                for (var side : pipe.interfaces.keySet()) state.contents.put(side, test.kind.equals("slugs") ? PipeContents.EMPTY : PipeContents.FULL);
                if (test.kind.equals("slugs")) {
                    int x = entry.getKey().getX() - ORIGIN.getX(), z = entry.getKey().getZ() - ORIGIN.getZ(), last = 2 * test.size - 2;
                    // Seed an actual divergence-free circulation. A uniform
                    // eastward gradient in a CLOSED full air/water mesh must
                    // correctly be projected to zero by incompressibility.
                    for (var side : pipe.interfaces.keySet()) state.outward.put(side,
                        z == 0 && side == Direction.EAST || x == last && side == Direction.SOUTH
                        || z == last && side == Direction.WEST || x == 0 && side == Direction.NORTH ? 12d
                        : z == 0 && side == Direction.WEST || x == last && side == Direction.NORTH
                        || z == last && side == Direction.EAST || x == 0 && side == Direction.SOUTH ? -12d : 0d);
                    if ((entry.getKey().getX() - ORIGIN.getX()) % 4 == 1 && (entry.getKey().getZ() - ORIGIN.getZ()) % 4 == 0)
                        state.contents.put(Direction.WEST, PipeContents.of(.2, .8));
                }
                if (pipe.blockEntity instanceof com.simibubi.create.content.fluids.pump.PumpBlockEntity) {
                    state.core = PipeContents.FULL; state.pumpFlow = 0; state.pumpFacing = Direction.EAST;
                }
                // Seed the benchmark explicitly through the legacy migration
                // entry, rather than changing a display field under a saved
                // conserved queue. Functional structure tests never seed stock.
                boolean pump = pipeIsPump(h, entry.getKey());
                double actual = state.contents.values().stream().mapToDouble(c -> c.fraction() * (pump ? 25 : 50)).sum()
                    + (pump ? state.core.fraction() * 50 : 0);
                var sides = java.util.EnumSet.noneOf(Direction.class); sides.addAll(pipe.interfaces.keySet());
                state.adoptMacro(actual, pump, sides, 0);
                state.prepareQueue(Math.max(25, (pump ? 50 : 0) + sides.size() * (pump ? 25 : 50)), pump, sides);
                if (test.mixed()) {
                    state.phases = new com.diliupengyun.differential_create.kinetics.FluidQueue<>(com.diliupengyun.differential_create.kinetics.FluidMaterials::same);
                    int offset = Math.floorMod(entry.getKey().getX() + entry.getKey().getZ(), 3);
                    for (int part = 0; part < 16; part++) {
                        var identity = switch ((part + offset) % 3) {
                            case 0 -> FluidStack.EMPTY;
                            case 1 -> water;
                            default -> new FluidStack(Fluids.LAVA, 1);
                        };
                        state.phases.add(identity, state.queueCapacity / 16, false);
                    }
                    state.adoptQueue(pump, sides);
                }
                initialWater.put(entry.getKey(), state.volume());
            }
            // Preserve eastward drive under the fixed right-hand X frame:
            // the east-facing motors mesh once before reaching each pump.
            for (var motor : motors) motor.generatedSpeed.setValue(-64);
            NetworkTopology.flush();
            for (var motor : motors) {
                var ratios = ((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$getRatios();
                double rpm = SourcePhysics.curve(motor).noLoadSpeed(); float reference = ratios.get(motor);
                ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = rpm * ratio / reference);
            }
            FluidKinetics.invalidate(h.getLevel());
        }
        double maximumFlow() {
            double maximum = 0;
            for (var pos : channels.keySet()) {
                var state = FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos)));
                for (double rate : state.outward.values()) maximum = Math.max(maximum, Math.abs(rate));
            }
            return maximum;
        }
        double volume() {
            double value = FluidKinetics.ownedVolume(h.getLevel());
            for (var pos : tanks) {
                FluidTankBlockEntity tank = h.getBlockEntity(pos); value += tank.getTankInventory().getFluidAmount();
            }
            return value;
        }
        int parts() {
            int count=0;
            for(var pos:channels.keySet()) count+=FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(),h.absolutePos(pos))).phases.size();
            return count;
        }
        Map<BlockPos,List<com.diliupengyun.differential_create.kinetics.FluidQueue.Part<FluidStack>>> queueSnapshot() {
            var result=new LinkedHashMap<BlockPos,List<com.diliupengyun.differential_create.kinetics.FluidQueue.Part<FluidStack>>>();
            for(var pos:channels.keySet()) result.put(pos,FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(),h.absolutePos(pos))).parcels());
            return result;
        }
        int clientBytes() {
            var bytes=new java.io.ByteArrayOutputStream();
            try(var output=new java.io.DataOutputStream(bytes)) {
                for(var pos:channels.keySet()) net.minecraft.nbt.NbtIo.write(h.getBlockEntity(pos).getUpdateTag(h.getLevel().registryAccess()),output);
            } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            return bytes.size();
        }
        double[] phaseStock() {
            double[] amount=new double[3];
            for(var pos:channels.keySet()) for(var p:FluidKinetics.state(FluidPropagator.getPipe(h.getLevel(),h.absolutePos(pos))).parcels())
                amount[p.identity().isEmpty()?0:p.identity().getFluid()==Fluids.WATER?1:2]+=p.amount();
            return amount;
        }
        void remove() {
            for (int i = placed.size() - 1; i >= 0; i--) h.setBlock(placed.get(i), Blocks.AIR.defaultBlockState());
            NetworkTopology.flush(); FluidKinetics.invalidate(h.getLevel());
        }
    }
}
