package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.nio.file.Path;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.storage.LevelResource;
import com.simibubi.create.AllFluids;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.potion.PotionFluidHandler;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.alchemy.PotionContents;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Opt-in exhaustive declared partitions plus seeded native operation scenarios.
 * Long scenes run sequentially in independent owned regions and normal ticks. */
@GameTestHolder("differentialcreate_coverage")
@PrefixGameTestTemplate(false)
public final class FluidCoverageIntegrationTests {
    private static final String TEMPLATE = "differentialcreate_coverage:kineticphysicstests.physics_test";
    private FluidCoverageIntegrationTests() {}
    @GameTestGenerator
    public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (int length : new int[]{16, 64, 256, 1024}) for (boolean lava : new boolean[]{false, true}) for (boolean driven : new boolean[]{false, true}) {
            String name = "distance_straight_" + length + "_" + (lava ? "lava" : "water") + "_" + (driven ? "driven" : "gravity");
            add(tests, name, 5000 + 80 * length, h -> distance(h, name, length, lava ? Fluids.LAVA : Fluids.WATER, driven, false));
        }
        for (int length : new int[]{64, 256}) for (boolean lava : new boolean[]{false, true}) for (boolean driven : new boolean[]{false, true}) {
            String name = "distance_bent_" + length + "_" + (lava ? "lava" : "water") + "_" + (driven ? "driven" : "gravity");
            add(tests, name, 6000 + 100 * length, h -> distance(h, name, length, lava ? Fluids.LAVA : Fluids.WATER, driven, true));
        }
        for (int room : new int[]{999, 1000, 1001}) for (boolean lava : new boolean[]{false, true}) {
            String name = "whole_bucket_room_" + room + "_" + (lava ? "lava" : "water");
            add(tests, name, 2000, h -> bucketRoom(h, name, room, lava ? Fluids.LAVA : Fluids.WATER));
        }
        for (String mode : new String[]{"series", "opposing", "dry_gap"}) for (int rpm : new int[]{-64, 0, 64, 256}) for (boolean lava : new boolean[]{false, true}) {
            String name = "pumps_" + mode + "_rpm_" + (rpm < 0 ? "minus" + -rpm : rpm) + "_" + (lava ? "lava" : "water");
            add(tests, name, 3600, h -> pumps(h, name, mode, rpm, lava ? Fluids.LAVA : Fluids.WATER));
        }
        for (int seed = 0; seed < 24; seed++) for (boolean lava : new boolean[]{false, true}) {
            int picked = seed; String name = "adversarial_seed_" + seed + "_" + (lava ? "lava" : "water");
            add(tests, name, 4800, h -> adversarial(h, name, picked, lava ? Fluids.LAVA : Fluids.WATER));
        }
        for (String part : new String[]{"middle", "source", "sink", "driven_source"}) for (boolean lava : new boolean[]{false, true}) {
            String name = "chunk_unload_" + part + "_" + (lava ? "lava" : "water");
            add(tests, name, 12000, h -> unload(h, name, part, lava ? Fluids.LAVA : Fluids.WATER));
        }
        add(tests, "budget_many_networks", 7600, FluidCoverageIntegrationTests::manyNetworks);
        for (String kind : new String[]{"water", "lava", "honey", "milk", "tea", "chocolate", "potion"})
            add(tests, "native_material_64_" + kind, 12000, h -> material(h, kind));
        for (boolean merge : new boolean[]{false, true})
            add(tests, "component_identity_" + (merge ? "wet_merge" : "separate"), 3000, h -> componentIdentity(h, merge));
        for (boolean lava : new boolean[]{false, true})
            add(tests, "retained_world_intake_reload_" + (lava ? "lava" : "water"), 2200, h -> retainedIntake(h, lava ? Fluids.LAVA : Fluids.WATER));
        for (boolean lava : new boolean[]{false, true})
            add(tests, "native_boiler_pipe_" + (lava ? "rejects_lava" : "feeds_water"), 1500, h -> boiler(h, lava ? Fluids.LAVA : Fluids.WATER));
        for (int length : new int[]{64, 256, 1024}) for (boolean lava : new boolean[]{false, true}) {
            String name = "one_bucket_partial_long_" + length + "_" + (lava ? "lava" : "water");
            add(tests, name, 7000 + length * 20, h -> sparse(h, name, length, lava ? Fluids.LAVA : Fluids.WATER));
        }
        for (boolean lava : new boolean[]{false, true})
            add(tests, "world_cached_chunk_unload_" + (lava ? "lava" : "water"), 7000, h -> worldCachedUnload(h, lava ? Fluids.LAVA : Fluids.WATER));
        String restart = System.getProperty("differentialcreate.coverage.restart", "");
        if (restart.equals("prepare")) add(tests, "restart_prepare", 2200, h -> restart(h, false));
        if (restart.equals("resume")) add(tests, "restart_resume", 7400, h -> restart(h, true));
        return tests;
    }
    private static void add(Collection<TestFunction> tests, String name, int timeout, Consumer<GameTestHelper> run) {
        String selected = System.getProperty("differentialcreate.coverage.select", ".*");
        if (!name.matches(selected)) return;
        tests.add(new TestFunction("coverage_" + name, name, TEMPLATE, timeout, 0, true, run));
    }
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static void worldCachedUnload(GameTestHelper h, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "world_cached_chunk_unload_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava"));
        var inlet = p(0, 10, 3); var sink = p(512, 0, 3);
        n.tank(sink, 1, 2); n.route(inlet.above(), inlet, p(0, 3, 3), p(512, 3, 3), sink.above()); n.ports.remove(inlet.above());
        n.build(); n.timeout(6998); n.observeUnloads();
        var centre = new ChunkPos(n.absolute(inlet)); var released = new HashSet<ChunkPos>();
        for (var chunk : n.forced.keySet()) if (Math.abs(chunk.x - centre.x) <= 13) released.add(chunk);
        boolean[] removedTickets = {false}, restored = {false}; long[] releasedAt = {0};
        n.after(40, () -> n.worldFill(inlet.above(), fluid, Direction.DOWN));
        n.watch(() -> {
            if (h.getTick() <= 40) return;
            if (!removedTickets[0] || restored[0]) n.audit();
            if (!removedTickets[0] && !h.getLevel().getFluidState(n.absolute(inlet.above())).isSource()) {
                var c = (IPipePressure)n.pipe(inlet).getConnection(Direction.UP);
                var end = c.differentialcreate$source() instanceof OpenEndedPipe open ? open : (OpenEndedPipe)c.differentialcreate$retainedSource();
                int cached = end.provideHandler().getCapability().getFluidInTank(0).getAmount();
                if (cached > 100) {
                    removedTickets[0] = true; releasedAt[0] = h.getTick(); n.releaseChunks(released);
                    System.out.println("FLUID_COVERAGE_CACHED_UNLOAD name=" + n.name + " cached_before=" + cached + " tick=" + h.getTick());
                }
            }
            if (removedTickets[0] && !restored[0] && h.getTick() > releasedAt[0] + 500
                && n.suspended.containsKey(centre) && h.getLevel().getChunkSource().getChunkNow(centre.x, centre.z) == null) {
                n.audit(); n.restoreChunks(released); restored[0] = true;
            }
            if (restored[0] && n.amount(sink, fluid) > 0) {
                n.expect(n.total(fluid) >= 1000 - 1e-6, "actual cached whole-world intake survives real chunk unload and resumes remote delivery");
                n.done("world_intake_cache=actual,native_chunk_unload=true,distance=512,fluid=" + fluid + ",delivered=" + n.amount(sink, fluid));
            }
        });
    }
    private static void sparse(GameTestHelper h, String name, int length, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 10, 3); var sink = p(length, 0, 3);
        n.tank(source, 1, 1); n.tank(sink, 1, 2); n.route(source, p(0, 3, 3), p(length, 3, 3), sink.above());
        n.build(); n.timeout(6998 + length * 20); n.after(40, () -> n.fill(source, fluid, 1000));
        n.watch(() -> {
            if (h.getTick() <= 40) return; n.audit();
            int received = n.amount(sink, fluid);
            if (received > 0 && n.amount(source, fluid) == 0) {
                n.expect(n.body() < n.ports.size() * 50, "one real bucket remains far below the long network's body capacity");
                n.done("distance=" + length + ",finite_input=1000,startup=empty,drive=gravity,delivered=" + received + ",body_remaining=" + n.body());
            }
        });
    }
    private static void boiler(GameTestHelper h, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "native_boiler_pipe_" + (fluid.isSame(Fluids.WATER) ? "feeds_water" : "rejects_lava"));
        var source = p(-10, 12, 4); var target = p(0, 1, 3); var shaft = p(0, 2, 8);
        n.tank(source, 1, 4); n.tank(target, 3, 3);
        n.route(source, p(-10, 5, 4), p(1, 5, 4), p(1, 3, 4)); n.build(); n.timeout(1498);
        for (int x = 0; x < 3; x++) for (int z = 3; z < 6; z++) n.put(p(x, 0, z), AllBlocks.BLAZE_BURNER.getDefaultState()
            .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        n.put(p(0, 2, 6), AllBlocks.STEAM_ENGINE.getDefaultState().setValue(SteamEngineBlock.FACE, AttachFace.WALL)
            .setValue(SteamEngineBlock.FACING, Direction.SOUTH));
        n.put(shaft, AllBlocks.POWERED_SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        for (var point : List.of(p(0, 2, 6), shaft)) if (n.entity(point) instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity be) be.initialize();
        java.lang.reflect.Field meter, sampleIndex, samples; java.lang.reflect.Method fuel;
        try {
            meter = BoilerData.class.getDeclaredField("gatheredSupply"); meter.setAccessible(true);
            sampleIndex = BoilerData.class.getDeclaredField("currentIndex"); sampleIndex.setAccessible(true);
            samples = BoilerData.class.getDeclaredField("supplyOverTime"); samples.setAccessible(true);
            fuel = BlazeBurnerBlockEntity.class.getDeclaredMethod("tryUpdateFuel", ItemStack.class, boolean.class, boolean.class); fuel.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        int[] consumed = {0}, completedSamples = {0}, previousSample = {0}; double[] peakSupply = {0}, peakRpm = {0};
        n.after(40, () -> {
            n.expect(n.tankEntity(target).boiler.isActive(), "real engines turn the formed native tank into a boiler");
            for (int x = 0; x < 3; x++) for (int z = 3; z < 6; z++) try {
                n.expect((boolean)fuel.invoke(n.entity(p(x, 0, z)), new ItemStack(Items.COAL), false, false), "real coal starts every native burner");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            n.fill(source, fluid, 20000);
            try { previousSample[0] = sampleIndex.getInt(n.tankEntity(target).boiler); }
            catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        });
        n.watch(() -> {
            if (h.getTick() <= 40) return;
            var data = n.tankEntity(target).boiler;
            int accepted;
            try {
                int current = meter.getInt(data), index = sampleIndex.getInt(data);
                if (index != previousSample[0]) {
                    var history = (float[])samples.get(data);
                    int justCompleted = Math.floorMod(index - 1, history.length);
                    completedSamples[0] += Math.round(history[justCompleted] * 5);
                }
                accepted = completedSamples[0] + current - consumed[0]; previousSample[0] = index;
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            // Native gatheredSupply increments only for executed fills and
            // resets when its five-tick sample index advances. Include the
            // completed native sample and current meter, since the physical
            // clock can run after a GameTest callback. Never write either.
            n.expect(accepted >= 0, "the actual native receipt meter advances or samples without a negative debit");
            if (accepted > 0) {
                n.expect(fluid.isSame(Fluids.WATER), "the native boiler consumes only actual water");
                consumed[0] += accepted; n.collected.merge(Fluids.WATER, (double)accepted, Double::sum);
            }
            peakSupply[0] = Math.max(peakSupply[0], data.waterSupply);
            peakRpm[0] = Math.max(peakRpm[0], Math.abs(((PoweredShaftBlockEntity)n.entity(shaft)).getSpeed()));
            n.audit();
        });
        n.after(1200, () -> {
            if (fluid.isSame(Fluids.WATER)) n.expect(consumed[0] > 1000 && peakSupply[0] > 0 && peakRpm[0] > 1,
                "counted pipe water and actual coal produce measured boiler receipts and real powered-shaft motion");
            else n.expect(consumed[0] == 0 && peakSupply[0] == 0, "real hot boiler refuses lava without deleting its pipe stock");
            n.done("native_boiler=true,fluid=" + fluid + ",consumed_water=" + consumed[0] + ",peak_native_supply=" + peakSupply[0] + ",peak_shaft_rpm=" + peakRpm[0]);
        });
    }
    private static void retainedIntake(GameTestHelper h, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, "retained_world_intake_reload_" + (fluid.isSame(Fluids.LAVA) ? "lava" : "water"));
        var inlet = p(3, 14, 3); var sink = p(3, 1, 3); n.tank(sink, 1, 1);
        n.route(inlet.above(), inlet, sink); n.ports.remove(inlet.above()); n.build(); n.timeout(2198);
        boolean[] reloaded = {false};
        n.after(40, () -> { n.fill(sink, fluid, 8000); n.worldFill(inlet.above(), fluid, Direction.DOWN); });
        n.watch(() -> {
            if (h.getTick() <= 40) return; n.audit();
            var connection = (IPipePressure) n.pipe(inlet).getConnection(Direction.UP);
            if (!reloaded[0] && connection.differentialcreate$source() == null
                && connection.differentialcreate$retainedSource() instanceof OpenEndedPipe end
                && end.provideHandler().getCapability().getFluidInTank(0).getAmount() > 0) {
                System.out.println("FLUID_COVERAGE_RETAINED_CACHE name=" + n.name + " tick=" + h.getTick()
                    + " cached=" + end.provideHandler().getCapability().getFluidInTank(0).getAmount());
                reloaded[0] = true; n.reload(inlet); n.audit();
            }
        });
        n.after(400, () -> { n.expect(reloaded[0], "reload actually occurred in the native retained-source window with real intake stock"); n.removeTank(sink, fluid, Integer.MAX_VALUE); });
        n.after(1900, () -> { n.expect(n.amount(sink, fluid) == 1000, "all actual cached world liquid survived reload and reached the receiver"); n.done("world_intake=retained_native_source,reload=wet,fluid=" + fluid); });
    }
    private static FluidStack materialStack(String kind) {
        return switch (kind) {
            case "water" -> new FluidStack(Fluids.WATER, 1);
            case "lava" -> new FluidStack(Fluids.LAVA, 1);
            case "honey" -> new FluidStack((Fluid) AllFluids.HONEY.getSource(), 1);
            case "tea" -> new FluidStack(AllFluids.TEA.get(), 1);
            case "chocolate" -> new FluidStack((Fluid) AllFluids.CHOCOLATE.getSource(), 1);
            case "milk" -> FluidUtil.getFluidContained(new ItemStack(Items.MILK_BUCKET)).orElseThrow();
            case "potion" -> PotionFluidHandler.getFluidFromPotionItem(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            default -> throw new IllegalArgumentException(kind);
        };
    }
    static void material(GameTestHelper h, String kind) {
        var n = new NativeFluidTestNetwork(h, "native_material_64_" + kind);
        var source = p(0, 10, 3); var sink = p(64, -1, 3); var offered = materialStack(kind).copyWithAmount(18000);
        n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, p(0, 3, 3), p(64, 3, 3), sink.above(3));
        // The receiving tank's upper cell lies at y=2.
        n.build(); n.timeout(11998); n.after(40, () -> n.fill(source, offered));
        n.watch(() -> {
            if (h.getTick() <= 40) return; n.audit();
            var received = n.tankEntity(sink).getTankInventory().getFluid();
            if (received.getAmount() >= 1000) {
                n.expect(FluidStack.isSameFluidSameComponents(received, offered), "native fluid identity and data components reach the actual distant tank unchanged");
                n.done("native_material=" + kind + ",distance=64,startup=empty,delivered=" + received.getAmount());
            }
        });
    }
    static void componentIdentity(GameTestHelper h, boolean merge) {
        var n = new NativeFluidTestNetwork(h, "component_identity_" + (merge ? "wet_merge" : "separate"));
        var a = new FluidStack(Fluids.WATER, 6000); var b = new FluidStack(Fluids.WATER, 6000);
        var tagA = new CompoundTag(); tagA.putString("native_test_kind", "A"); var tagB = new CompoundTag(); tagB.putString("native_test_kind", "B");
        a.set(DataComponents.CUSTOM_DATA, CustomData.of(tagA)); b.set(DataComponents.CUSTOM_DATA, CustomData.of(tagB));
        n.tank(p(0, 10, 3), 1, 2); n.tank(p(28, 10, 3), 1, 2); n.tank(p(10, 0, 3), 1, 2); n.tank(p(18, 0, 3), 1, 2);
        n.route(p(0, 10, 3), p(0, 3, 3), p(10, 3, 3), p(10, 1, 3));
        n.route(p(28, 10, 3), p(28, 3, 3), p(18, 3, 3), p(18, 1, 3)); n.build(); n.timeout(2998);
        n.after(40, () -> { n.fill(p(0, 10, 3), a); n.fill(p(28, 10, 3), b); });
        if (merge) n.after(400, () -> { n.route(p(10, 3, 3), p(18, 3, 3)); n.installChangedPipes(); });
        n.after(650, () -> { n.reload(p(6, 3, 3)); n.reload(p(22, 3, 3)); });
        n.watch(() -> { if (h.getTick() > 40) n.audit(); });
        n.after(2600, () -> {
            for (var identity : List.of(a, b)) {
                var at = identity == a ? p(10, 0, 3) : p(18, 0, 3); var actual = n.tankEntity(at).getTankInventory().getFluid();
                n.expect(actual.getAmount() > 1000 && FluidStack.isSameFluidSameComponents(actual, identity),
                    "same fluid with different real components is neither renamed nor mixed during native reload or wet merge");
            }
            n.done("fluid_components=different_custom_data,wet_merge=" + merge + ",reload=true");
        });
    }
    private static void restart(GameTestHelper h, boolean resume) {
        Path manifestPath = h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("fluid-coverage-restart.nbt");
        CompoundTag manifest = new CompoundTag();
        if (resume) try { manifest = NbtIo.readCompressed(manifestPath, NbtAccounter.unlimitedHeap()); }
        catch (IOException failure) { throw new IllegalStateException("restart must load the actual previous owned world's manifest", failure); }
        var offset = p(0, 0, 4096);
        if (resume) {
            int[] xyz = manifest.getIntArray("Origin");
            offset = new BlockPos(xyz[0], xyz[1], xyz[2]).subtract(h.absolutePos(BlockPos.ZERO));
        }
        var n = new NativeFluidTestNetwork(h, resume ? "restart_resume" : "restart_prepare", offset);
        for (int z : new int[]{3, 19}) {
            var source = p(0, 10, z); var sink = p(64, 0, z);
            n.tank(source, 1, 4); n.tank(sink, 1, 3); n.route(source, p(0, 3, z), p(64, 3, z), sink.above(2));
        }
        if (!resume) n.build();
        else {
            for (var point : n.tankBlocks) { n.force(point); n.placed.add(point); }
            for (var point : n.ports.keySet()) if (!n.tankBlocks.contains(point)) { n.force(point); n.placed.add(point); }
            n.restoreChunks(Set.copyOf(n.forced.keySet()));
            n.introduced.put(Fluids.WATER, manifest.getDouble("Water")); n.introduced.put(Fluids.LAVA, manifest.getDouble("Lava"));
            n.expect(n.body() > 1000, "actual region-file pipe stock survived a completely new JVM");
            n.audit();
        }
        n.timeout(resume ? 7398 : 2198);
        n.watch(() -> { if (h.getTick() > 40) n.audit(); });
        if (!resume) {
            n.after(40, () -> { n.fill(p(0, 10, 3), Fluids.WATER, 18000); n.fill(p(0, 10, 19), Fluids.LAVA, 18000); });
            n.after(500, () -> {
                for (var point : n.ports.keySet()) if (!n.tankBlocks.contains(point)) for (var direction : Direction.values()) {
                    var next = point.relative(direction);
                    if (n.ports.containsKey(next) && !n.tankBlocks.contains(next) && point.compareTo(next) < 0) n.edge(point, next, false);
                }
            });
            n.after(900, () -> {
                n.audit(); n.expect(n.body() > 1000, "the saved world contains real wet native channels");
                var tag = new CompoundTag(); var origin = n.absolute(BlockPos.ZERO);
                tag.putIntArray("Origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
                tag.putDouble("Water", n.introduced.get(Fluids.WATER)); tag.putDouble("Lava", n.introduced.get(Fluids.LAVA));
                tag.putDouble("Body", n.body()); tag.putLong("PreviousJvm", ProcessHandle.current().pid());
                for (var point : n.ports.keySet()) if (n.entity(point) != null) n.entity(point).setChanged();
                try { NbtIo.writeCompressed(tag, manifestPath); } catch (IOException failure) { throw new IllegalStateException(failure); }
                h.getLevel().getServer().saveEverything(true, true, false);
                System.out.println("FLUID_COVERAGE_PASS name=restart_prepare tick=" + h.getTick() + " body=" + n.body()
                    + " water=" + n.total(Fluids.WATER) + " lava=" + n.total(Fluids.LAVA) + " jvm=" + ProcessHandle.current().pid() + " error=" + n.maximumError);
                n.closed = true; h.succeed();
            });
        } else {
            long previousJvm = manifest.getLong("PreviousJvm"); double savedBody = manifest.getDouble("Body");
            n.expect(ProcessHandle.current().pid() != previousJvm, "resume really runs in a different server process");
            n.expect(Math.abs(n.body() - savedBody) < 1e-6, "all actual closed pipe inventory was loaded from disk exactly");
            int[] previousReceipt = {n.amount(p(64, 0, 3), Fluids.WATER), n.amount(p(64, 0, 19), Fluids.LAVA)};
            n.after(40, () -> {
                for (var point : n.ports.keySet()) if (!n.tankBlocks.contains(point)) for (var direction : n.ports.get(point)) {
                    var next = point.relative(direction);
                    if (n.ports.containsKey(next) && !n.tankBlocks.contains(next) && point.compareTo(next) < 0) n.edge(point, next, true);
                }
            });
            n.after(6500, () -> {
                n.expect(n.amount(p(64, 0, 3), Fluids.WATER) > previousReceipt[0] + 1000
                    && n.amount(p(64, 0, 19), Fluids.LAVA) > previousReceipt[1] + 1000, "both saved fluid networks resume real native transfer after server restart");
                n.done("server_restart=new_jvm,previous_jvm=" + previousJvm + ",fluids=water_and_lava,saved_body=" + savedBody);
            });
        }
    }
    private static void bucketRoom(GameTestHelper h, String name, int room, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, name); var sink = p(3, 1, 3); var inlet = p(3, 10, 3);
        n.tank(sink, 1, 1); n.route(inlet.above(), inlet, sink); n.ports.remove(inlet.above()); n.build(); n.timeout(1998);
        int capacity = n.ports.entrySet().stream().filter(e -> !n.tankBlocks.contains(e.getKey())).mapToInt(e -> 50 * e.getValue().size()).sum();
        n.after(40, () -> {
            n.expect(capacity == 900, "the real receiving pipe capacity is exactly 900 mB");
            n.fill(sink, fluid, 8000 + capacity - room); n.worldFill(inlet.above(), fluid, Direction.DOWN);
        });
        n.watch(() -> { if (h.getTick() > 40) n.audit(); });
        n.after(240, () -> {
            boolean present = h.getLevel().getFluidState(n.absolute(inlet.above())).isSource();
            n.expect(present == (room < 1000), "whole-world intake distinguishes insufficient, exact and surplus actual space");
            if (room < 1000) n.expect(n.body() == 0, "insufficient space leaves the entire pipe network dry");
            n.removeTank(sink, fluid, Integer.MAX_VALUE);
        });
        n.after(1900, () -> {
            n.expect(!h.getLevel().getFluidState(n.absolute(inlet.above())).isSource(), "freed real space admits the actual bucket");
            n.expect(n.amount(sink, fluid) > 500, "the taken bucket actually reaches the native receiver"); n.done("whole_bucket_remaining_space=" + room + ",fluid=" + fluid);
        });
    }
    private static void pumps(GameTestHelper h, String name, String mode, int rpm, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 10, 2); var sink = p(18, -1, 2);
        var first = p(4, 4, 3); var second = p(12, mode.equals("dry_gap") ? 80 : 4, 3);
        n.tank(source, 3, 4); n.tank(sink, 3, 2); n.route(p(1, 10, 3), p(1, 4, 3), p(4, 4, 3));
        if (mode.equals("dry_gap")) n.route(first, p(8, 4, 3), p(8, 80, 3), second, p(19, 80, 3), p(19, 1, 3), p(19, 0, 3));
        else n.route(first, second, p(19, 4, 3), p(19, 0, 3));
        n.pump(first, Direction.EAST); n.pump(second, mode.equals("opposing") ? Direction.WEST : Direction.EAST);
        n.build(); n.timeout(3598);
        n.after(40, () -> { n.fill(source, fluid, 18000); n.speed(first, rpm); n.speed(second, rpm); });
        n.watch(() -> {
            if (h.getTick() <= 40) return; n.audit();
            for (var point : n.pumps.keySet()) {
                var state = (IPumpDynamics) n.entity(point);
                n.expect(Double.isFinite(state.differentialcreate$head()) && Double.isFinite(state.differentialcreate$flow()), "all driven wet or dry pump states remain finite");
            }
        });
        n.after(300, () -> {
            if (mode.equals("dry_gap") && Math.abs(rpm) <= 64) {
                n.expect(n.volume(second) == 0 && ((IPumpDynamics)n.entity(second)).differentialcreate$head() == 0,
                    "an unprimed remote pump cannot self-prime across a crest above the available wet head");
            }
            if (rpm != 0) n.expect(Math.abs(((PumpBlockEntity)n.entity(first)).getSpeed()) > Math.abs(rpm) * .8, "the actual native motor drove the shaft");
            n.speed(first, 0); n.speed(second, 0);
        });
        n.after(400, () -> { n.replacePumpWithPipe(first); n.replacePumpWithPipe(second); });
        n.after(3300, () -> {
            if (!mode.equals("dry_gap")) n.expect(n.amount(sink, fluid) > 1000, "series or opposing pumps leave a working gravity path after stopping");
            else n.expect(n.amount(sink, fluid) == 0 || rpm == 256, "gravity alone cannot lift fluid over an eighty-block crest");
            n.done("pumps=" + mode + ",rpm=" + rpm + ",fluid=" + fluid);
        });
    }
    static void adversarial(GameTestHelper h, String name, int seed, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, name); var random = new Random(0xD1FfL + seed);
        var source = p(0, 12, 8); var sink = p(30, -1, 8); var pump = p(4, 4, 9);
        n.tank(source, 3, 4); n.tank(sink, 3, 4);
        n.route(p(1, 12, 9), p(1, 4, 9), p(31, 4, 9), p(31, 2, 9));
        for (int i = 0; i < 14; i++) {
            int x = 6 + random.nextInt(20), other = 6 + random.nextInt(20); if (x == other) other = other == 25 ? 24 : other + 1;
            int y = 5 + random.nextInt(6), z = random.nextBoolean() ? 4 : 14;
            n.route(p(x, 4, 9), p(x, y, 9), p(x, y, z), p(other, y, z), p(other, y, 9), p(other, 4, 9));
            if (random.nextBoolean()) n.route(p(x, y, z), p(x, y + 2, z));
        }
        n.pump(pump, Direction.EAST); n.build(); n.timeout(4798);
        n.after(40, () -> { n.fill(source, fluid, 36000); n.speed(pump, 64); });
        int[] operations = {0}; boolean[] gate = {true};
        n.watch(() -> {
            if (h.getTick() <= 40) return; n.audit();
            if (h.getTick() >= 180 && h.getTick() < 1200 && h.getTick() % 31 == 0) {
                int operation = random.nextInt(4);
                if (operation == 0) { gate[0] = !gate[0]; n.edge(p(22, 4, 9), p(23, 4, 9), gate[0]); }
                else if (operation == 1) {
                    repairAdversarialDrive(n, pump);
                    n.speed(pump, new int[]{-64, 0, 16, 64, 256}[random.nextInt(5)]);
                }
                else if (operation == 2) n.reload(p(16, 4, 9));
                else { int x = 7 + random.nextInt(15); n.reload(p(x, 4, 9)); }
                operations[0]++;
            }
        });
        n.after(1240, () -> { n.edge(p(22, 4, 9), p(23, 4, 9), true); repairAdversarialDrive(n, pump); n.speed(pump, 64); });
        n.after(4500, () -> {
            n.expect(operations[0] >= 30 && n.amount(sink, fluid) > 2000, "after hostile, reproducible native operations the irregular wet graph resumes actual delivery");
            System.out.println("FLUID_COVERAGE_OPERATIONS name=" + name + " seed=" + seed + " operations=" + operations[0]); n.done("seed=" + seed + ",fluid=" + fluid + ",loop_branches=14,operations=" + operations[0]);
        });
    }
    /** Aggressive real reversals can fracture the mechanical drive. Restoring
     * transport includes replacing its actual broken motor/cog, with new rotors
     * starting normally; no pipe inventory, head or physical speed is assigned. */
    private static void repairAdversarialDrive(NativeFluidTestNetwork n, BlockPos pump) {
        var cog = pump.above(); var motor = n.motors.get(pump); int replaced = 0;
        for (var at : new BlockPos[]{cog, motor}) if (n.entity(at) == null) {
            n.expect(n.state(at).isAir(), "repair only replaces a genuinely removed mechanical drive component");
            n.put(at, at.equals(cog) ? AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)
                : AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
            if (n.entity(at) instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) smart.initialize();
            replaced++;
        }
        if (replaced > 0) System.out.println("FLUID_COVERAGE_DRIVE_REPAIR name=" + n.name + " tick=" + n.h.getTick() + " actual_replaced=" + replaced);
    }
    static void unload(GameTestHelper h, String name, String part, Fluid fluid) {
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 10, 3); var sink = p(512, 0, 3);
        n.tank(source, 1, 16); n.tank(sink, 1, 16); n.route(source, p(0, 3, 3), p(512, 3, 3), sink.above(2));
        // The far end is the receiver's upper block: avoid replacing it with a pipe.
        boolean driven = part.equals("driven_source"); var pump = driven ? source.below(2) : p(480, 3, 3);
        // Maintain a real transport drive through the long horizontal route.
        // A finite unpowered slug may legitimately stop in dense virtual air;
        // this scenario tests resume after unload, not indefinite free travel.
        n.pump(pump, driven ? Direction.DOWN : Direction.EAST);
        n.build(); n.timeout(11998); n.observeUnloads();
        var released = new HashSet<ChunkPos>();
        int centre = part.equals("source") || driven ? 0 : part.equals("sink") ? 512 : 256;
        int centreChunkX = new ChunkPos(n.absolute(p(centre, 3, 3))).x;
        // Minecraft also retains non-ticking generation dependencies around a
        // forced chunk. A thirteen-chunk buffer removes those too, so the
        // central chunk truly unloads rather than only ceasing entity ticks.
        for (var chunk : n.forced.keySet()) if (Math.abs(chunk.x - centreChunkX) <= 13) released.add(chunk);
        long[] loadedAgain = {-1}; int[] receipt = {0}; boolean[] reallyUnloaded = {false};
        n.after(40, () -> { n.fill(source, fluid, 120000); n.speed(pump, 64); });
        n.after(1400, () -> {
            n.audit(); n.expect(n.body() > 100, "there is real stock before chunk release");
            if (driven) n.expect(n.volume(pump) >= .1 && Math.abs(((PumpBlockEntity)n.entity(pump)).getSpeed()) > 100,
                "a genuinely wetted native driven pump is inside the released chunks");
            n.releaseChunks(released);
        });
        n.watch(() -> {
            if (h.getTick() <= 40) return;
            if (h.getTick() <= 1400 || loadedAgain[0] > 0) n.audit();
            if (h.getTick() > 1400 && loadedAgain[0] < 0) {
                var centreChunk = new ChunkPos(n.absolute(p(centre, 3, 3)));
                if (h.getTick() % 500 == 0 && Boolean.getBoolean("differentialcreate.coverage.audit"))
                    System.out.println("FLUID_COVERAGE_CHUNK name=" + name + " tick=" + h.getTick() + " chunk=" + centreChunk
                        + " present=" + (h.getLevel().getChunkSource().getChunkNow(centreChunk.x, centreChunk.z) != null)
                        + " ticking=" + h.getLevel().getChunkSource().isPositionTicking(centreChunk.toLong())
                        + " forced=" + h.getLevel().getForcedChunks().contains(centreChunk.toLong()) + " recorded=" + n.suspended.size()
                        + " debug=" + h.getLevel().getChunkSource().getChunkDebugData(centreChunk));
                if (h.getLevel().getChunkSource().getChunkNow(centreChunk.x, centreChunk.z) == null && n.suspended.containsKey(centreChunk)) {
                    reallyUnloaded[0] = true;
                    n.expect(n.suspended.containsKey(centreChunk), "a native unload event captured the saved inventory");
                    n.audit();
                    if (h.getTick() >= 2400) {
                        n.restoreChunks(released);
                        // Capture during the FULL -> entity-ticking transition.
                        // A tank-only neighbour must wake the cached outlet even
                        // without a pipe registering inside its restored chunk.
                        FluidKinetics.capture(h.getLevel());
                        loadedAgain[0] = h.getTick(); receipt[0] = n.amount(sink, fluid);
                        System.out.println("FLUID_COVERAGE_RELOAD name=" + name + " tick=" + loadedAgain[0] + " unloaded_chunks=" + n.suspended.size());
                    }
                }
            }
            if (loadedAgain[0] > 0 && h.getTick() >= loadedAgain[0] + 300 && n.amount(sink, fluid) >= receipt[0] + 64) {
                n.expect(reallyUnloaded[0], "actual unloaded chunks reload with preserved stock and resume downstream transfer");
                System.out.println("FLUID_COVERAGE_CHUNK_DELIVERY name=" + name + " before=" + receipt[0] + " after=" + n.amount(sink, fluid));
                n.done("native_chunk_lifecycle=" + part + ",distance=512,fluid=" + fluid);
            }
        });
    }
    static void manyNetworks(GameTestHelper h) {
        var networks = new ArrayList<NativeFluidTestNetwork>();
        for (int i = 0; i < 32; i++) {
            var n = new NativeFluidTestNetwork(h, "budget_network_" + i, p(0, 0, 2048 + 8 * i));
            var source = p(0, 8, 0); var sink = p(128, 0, 0); n.tank(source, 1, 4); n.tank(sink, 1, 3);
            n.route(source, p(0, 3, 0), p(128, 3, 0), sink.above(2)); n.build(); networks.add(n);
        }
        int[] checkpoints = new int[32];
        h.runAfterDelay(40, () -> { for (var n : networks) n.fill(p(0, 8, 0), Fluids.WATER, 28000); });
        h.onEachTick(() -> {
            if (h.getTick() <= 40 || networks.get(0).closed) return;
            try { for (var n : networks) n.audit(); }
            catch (RuntimeException | Error failure) { for (var n : networks) n.cleanup(); throw failure; }
        });
        h.runAtTickTime(2200, () -> {
            for (int i = 0; i < networks.size(); i++) {
                var n = networks.get(i); checkpoints[i] = n.amount(p(128, 0, 0), Fluids.WATER);
                // A fast current model may have already filled a receiver. Free
                // actual native room and add counted supply, then require NEW
                // receipts from every component in the second observation.
                n.removeTank(p(128, 0, 0), Fluids.WATER, Integer.MAX_VALUE);
                n.fill(p(0, 8, 0), Fluids.WATER, Math.min(12000, n.tankEntity(p(0, 8, 0)).getTankInventory().getCapacity()
                    - n.amount(p(0, 8, 0), Fluids.WATER)));
            }
        });
        h.runAtTickTime(7300, () -> {
            try {
                double error = 0; int least = Integer.MAX_VALUE;
                for (int i = 0; i < networks.size(); i++) {
                    var n = networks.get(i); int received = n.amount(p(128, 0, 0), Fluids.WATER) + (int)Math.round(n.collected.getOrDefault(Fluids.WATER, 0d));
                    n.expect(received > checkpoints[i] + 20, "every independent component makes new actual receipts while sharing the world budget");
                    least = Math.min(least, received); error = Math.max(error, n.maximumError); n.audit();
                }
                System.out.println("FLUID_COVERAGE_PASS name=budget_many_networks tick=" + h.getTick() + " networks=32 pipes="
                    + networks.stream().mapToInt(n -> n.ports.size() - 2).sum() + " least_received=" + least + " error=" + error);
                for (var n : networks) n.cleanup(); h.succeed();
            } catch (RuntimeException | Error failure) { for (var n : networks) n.cleanup(); throw failure; }
        });
    }
    private static void distance(GameTestHelper h, String name, int length, Fluid fluid, boolean driven, boolean bent) {
        var n = new NativeFluidTestNetwork(h, name);
        var source = p(0, 10, 2); var sink = p(length + 2, -2, bent ? 11 : 2);
        n.tank(source, 3, 4); n.tank(sink, 3, 4);
        var line = p(1, 3, 3); var end = p(length + 3, 3, bent ? 12 : 3);
        n.route(p(1, 10, 3), line);
        if (bent) n.route(line, p(length / 2, 3, 3), p(length / 2, 6, 3), p(length / 2, 6, 12),
            p(length, 6, 12), p(length, 3, 12), end, end.below(2));
        else n.route(line, end, end.below(2));
        var pump = line.east(3);
        if (driven) n.pump(pump, Direction.EAST);
        n.build();
        n.timeout(bent ? 5998 + 100 * length : 4998 + 80 * length);
        long[] arrival = {-1}, draining = {-1}, pocketAt = {-1}; boolean[] removed = {false}; double[] stoppedBody = {0}, pocketBody = {0};
        n.after(40, () -> {
            n.expect(n.tankEntity(source).getTankInventory().getCapacity() == 288000, "the actual 3x3x4 source forms before supply");
            n.expect(n.tankEntity(sink).getTankInventory().getCapacity() == 288000, "the actual wide receiver forms before supply");
            n.expect(n.body() == 0, "the entire long network really starts empty");
            int supplied = (int) (n.ports.keySet().stream().filter(pos -> !n.tankBlocks.contains(pos)).count() * 100 + 8000);
            n.fill(source, fluid, supplied);
            if (driven) n.speed(pump, 64);
        });
        n.watch(() -> {
            if (h.getTick() <= 40) return;
            try {
                n.audit(); int received = n.amount(sink, fluid);
                if (received > 0 && arrival[0] < 0) arrival[0] = h.getTick();
                if (!removed[0] && received >= 2000) {
                    removed[0] = true; draining[0] = h.getTick();
                    n.removeTank(source, fluid, Integer.MAX_VALUE);
                    if (driven) n.speed(pump, 0);
                    stoppedBody[0] = n.body();
                }
                if (Boolean.getBoolean("differentialcreate.coverage.audit") && h.getTick() % 400 == 0)
                    System.out.println("FLUID_COVERAGE_PROGRESS name=" + name + " tick=" + h.getTick() + " source=" + n.amount(source, fluid)
                        + " received=" + received + " body=" + n.body() + " furthest=" + n.furthestWetX() + " arrival=" + arrival[0] + " draining=" + draining[0] + " error=" + n.maximumError);
                boolean drained = length >= 256 ? h.getTick() > draining[0] + 2000 && n.body() < stoppedBody[0] - 50 : n.body() < 1e-8;
                if (bent && length < 256 && removed[0]) {
                    boolean downstreamEmpty = n.ports.keySet().stream().filter(point -> !n.tankBlocks.contains(point)
                        && (point.getX() > length / 2 || point.getZ() > 3)).allMatch(point -> n.volume(point) < 1e-8);
                    if (downstreamEmpty && pocketAt[0] < 0) { pocketAt[0] = h.getTick(); pocketBody[0] = n.body(); }
                    if (!downstreamEmpty || pocketAt[0] >= 0 && Math.abs(n.body() - pocketBody[0]) > 1e-6) pocketAt[0] = -1;
                    drained |= pocketAt[0] >= 0 && h.getTick() >= pocketAt[0] + 200;
                    if (drained) for (var point : n.ports.keySet()) if (!n.tankBlocks.contains(point) && n.volume(point) > 1e-8)
                        n.expect(point.getY() <= 6 && point.getX() <= length / 2 && point.getZ() == 3,
                            "all settled inventory is in the actual low pocket before the raised crest");
                }
                if (removed[0] && h.getTick() > draining[0] + 40 && drained) {
                    n.expect(received >= 2000 && arrival[0] > 40, "actual counted source fluid reaches the remote native tank");
                    System.out.println("FLUID_COVERAGE_DISTANCE name=" + name + " blocks=" + length + " arrival_tick=" + arrival[0] + " delivered_mb=" + received
                        + " drain_ticks=" + (h.getTick() - draining[0]) + " body_remaining=" + n.body()
                        + " drain_outcome=" + (length >= 256 ? "continues" : bent && n.body() > 1e-8 ? "settled_low_pocket" : "empty"));
                    n.done("distance=" + length + ",startup=empty,fluid=" + fluid + ",drive=" + (driven ? "wet_pump" : "gravity") + ",shape=" + (bent ? "bent" : "straight"));
                }
            } catch (RuntimeException | Error failure) { n.cleanup(); throw failure; }
        });
    }
}
