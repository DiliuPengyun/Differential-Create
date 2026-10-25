package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.minecraft.world.level.chunk.LevelChunk;

/** Actual native fixtures. No synthetic pipe stock, pressure or clock advancement.
 * Large structures live beyond the harness box and own only their placed blocks
 * and force-load requests. Their inventories are audited separately per fluid. */
final class NativeFluidTestNetwork {
    record Tank(BlockPos base, int width, int height) {}
    final GameTestHelper h;
    final String name;
    final BlockPos offset;
    final Map<BlockPos, EnumSet<Direction>> ports = new LinkedHashMap<>();
    final Map<BlockPos, Direction> pumps = new LinkedHashMap<>();
    final Map<BlockPos, BlockPos> motors = new LinkedHashMap<>();
    final List<Tank> tanks = new ArrayList<>();
    final Set<BlockPos> tankBlocks = new HashSet<>();
    final Map<BlockPos, Direction> machines = new LinkedHashMap<>();
    final List<BlockPos> placed = new ArrayList<>();
    final Map<ChunkPos, Boolean> forced = new LinkedHashMap<>();
    final Map<Fluid, Double> introduced = new LinkedHashMap<>(), collected = new LinkedHashMap<>();
    final Map<BlockPos, Fluid> worldSources = new LinkedHashMap<>();
    final Map<ChunkPos, Map<Fluid, Double>> suspended = new LinkedHashMap<>();
    final Map<ChunkPos, LevelChunk> physicalChunks = new LinkedHashMap<>();
    static final class Kind {
        final FluidStack identity; double input, output;
        Kind(FluidStack identity) { this.identity = identity.copyWithAmount(1); }
    }
    final List<Kind> kinds = new ArrayList<>();
    Consumer<ChunkEvent.Unload> unloadListener;
    double maximumError, maximumFlow;
    boolean closed;

    NativeFluidTestNetwork(GameTestHelper h, String name) { this(h, name, new BlockPos(0, 0, 2048)); }
    NativeFluidTestNetwork(GameTestHelper h, String name, BlockPos offset) { this.h = h; this.name = name; this.offset = offset; }
    static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    BlockPos absolute(BlockPos p) { return h.absolutePos(p.offset(offset)); }
    void expect(boolean value, String message) { h.assertTrue(value, name + ": " + message); }
    void tank(BlockPos base, int width, int height) {
        tanks.add(new Tank(base, width, height));
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) for (int y = 0; y < height; y++)
            tankBlocks.add(base.offset(x, y, z));
    }
    boolean channel(BlockPos at) { return !tankBlocks.contains(at) && !machines.containsKey(at); }
    void port(BlockPos p, Direction side) { ports.computeIfAbsent(p, key -> EnumSet.noneOf(Direction.class)).add(side); }
    void route(BlockPos... corners) {
        for (int i = 1; i < corners.length; i++) {
            var at = corners[i - 1]; var end = corners[i];
            int x = end.getX() - at.getX(), y = end.getY() - at.getY(), z = end.getZ() - at.getZ();
            expect((x != 0 ? 1 : 0) + (y != 0 ? 1 : 0) + (z != 0 ? 1 : 0) == 1, "an actual route follows one block axis");
            var direction = x != 0 ? x > 0 ? Direction.EAST : Direction.WEST
                : y != 0 ? y > 0 ? Direction.UP : Direction.DOWN : z > 0 ? Direction.SOUTH : Direction.NORTH;
            while (!at.equals(end)) { var next = at.relative(direction); port(at, direction); port(next, direction.getOpposite()); at = next; }
        }
    }
    void pump(BlockPos p, Direction facing) {
        pumps.put(p, facing);
        var cog = facing.getAxis() == Direction.Axis.Y ? p.east() : p.above();
        motors.put(p, cog.relative(Direction.get(Direction.AxisDirection.POSITIVE, facing.getAxis())));
    }
    void force(BlockPos pos) {
        var chunk = new ChunkPos(absolute(pos));
        if (forced.containsKey(chunk)) return;
        boolean previous = h.getLevel().getForcedChunks().contains(chunk.toLong());
        forced.put(chunk, previous); h.getLevel().setChunkForced(chunk.x, chunk.z, true);
    }
    void put(BlockPos pos, BlockState state) {
        force(pos); h.getLevel().setBlockAndUpdate(absolute(pos), state);
        if (!placed.contains(pos)) placed.add(pos);
    }
    BlockState state(BlockPos pos) { return h.getLevel().getBlockState(absolute(pos)); }
    BlockEntity entity(BlockPos pos) {
        var absolute = absolute(pos); var key = new ChunkPos(absolute);
        var chunk = h.getLevel().getChunkSource().getChunkNow(key.x, key.z);
        if (chunk != null) physicalChunks.put(key, chunk);
        else chunk = physicalChunks.get(key); // Inspect an owned, frozen non-ticking chunk without loading it.
        return chunk == null ? null : chunk.getBlockEntity(absolute);
    }
    FluidTransportBehaviour pipe(BlockPos pos) { var be = entity(pos); return be instanceof SmartBlockEntity smart ? smart.getBehaviour(FluidTransportBehaviour.TYPE) : null; }
    static List<PipeConnection> connections(FluidTransportBehaviour pipe) {
        var result = new ArrayList<PipeConnection>(); var seen = new IdentityHashMap<PipeConnection, Boolean>();
        if (pipe.interfaces != null) for (var c : pipe.interfaces.values()) if (seen.put(c, true) == null) result.add(c);
        for (var c : FluidKinetics.state(pipe).retainedPorts.values()) if (seen.put(c, true) == null) result.add(c);
        return result;
    }
    FluidTankBlockEntity tankEntity(BlockPos base) {
        expect(entity(base) instanceof FluidTankBlockEntity, "the real tank is loaded: " + base);
        return ((FluidTankBlockEntity) entity(base)).getControllerBE();
    }
    void build() {
        for (var pos : tankBlocks) put(pos, AllBlocks.FLUID_TANK.getDefaultState());
        for (var entry : ports.entrySet()) {
            var p = entry.getKey(); if (!channel(p)) continue;
            var state = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
            for (var d : Direction.values()) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(d), entry.getValue().contains(d));
            if (pumps.containsKey(p)) state = AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, pumps.get(p));
            put(p, state);
        }
        for (var entry : pumps.entrySet()) {
            var p = entry.getKey(); var facing = entry.getValue();
            var cog = facing.getAxis() == Direction.Axis.Y ? p.east() : p.above();
            put(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, facing.getAxis()));
            put(motors.get(p), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,
                Direction.get(Direction.AxisDirection.NEGATIVE, facing.getAxis())));
            speed(p, 0);
        }
        // Native initialization is setup only. Forced, loaded far blocks may
        // start outside the harness' entity radius; no fluid state is seeded.
        for (var p : placed) if (entity(p) instanceof SmartBlockEntity be) be.initialize();
        FluidKinetics.invalidate(h.getLevel());
    }
    void installChangedPipes() {
        for (var e : ports.entrySet()) if (!tankBlocks.contains(e.getKey()) && !pumps.containsKey(e.getKey())) {
            var block = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
            for (var d : Direction.values()) block = block.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(d), e.getValue().contains(d));
            if (state(e.getKey()) != block) { put(e.getKey(), block); if (entity(e.getKey()) instanceof SmartBlockEntity be) be.initialize(); }
        }
        FluidKinetics.invalidate(h.getLevel());
    }
    /** Set local shaft rotation through the real motor and meshing cog.
     * The pump arrow selects the outlet independently of this rotation sign. */
    void speed(BlockPos pump, int rpm) {
        var motor = (CreativeMotorBlockEntity)entity(motors.get(pump)); var facing = pumps.get(pump);
        if (rpm == 0 || facing == null) { motor.generatedSpeed.setValue(rpm); return; }
        double motorSign = KineticBlockEntity.convertToDirection(1, motor.getBlockState().getValue(BlockStateProperties.FACING));
        // This fixture always places one small meshing cog (-1), with its
        // motor on that cog's shaft (+1). A player can still change the native
        // motor setting after the cog fractures; never dereference a missing
        // transmission block or pretend the disconnected pump is driven.
        motor.generatedSpeed.setValue(-(int)motorSign * rpm);
    }
    void replacePumpWithPipe(BlockPos pos) {
        expect(pumps.containsKey(pos), "the native replaced cell is a pump");
        FluidTransportBehaviour.cacheFlows(h.getLevel(), absolute(pos));
        var next = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var d : Direction.values()) next = next.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(d), ports.get(pos).contains(d));
        put(pos, next); FluidTransportBehaviour.loadFlows(h.getLevel(), absolute(pos));
        if (entity(pos) instanceof SmartBlockEntity smart) smart.initialize(); pumps.remove(pos); FluidKinetics.invalidate(h.getLevel());
    }
    void fill(BlockPos base, Fluid fluid, int amount) {
        fill(base, new FluidStack(fluid, amount));
    }
    Kind kind(FluidStack stack) {
        for (var kind : kinds) if (FluidStack.isSameFluidSameComponents(stack, kind.identity)) return kind;
        var next = new Kind(stack); kinds.add(next); return next;
    }
    void fill(BlockPos base, FluidStack offered) {
        var tank = tankEntity(base).getTankInventory();
        int actual = tank.fill(offered, FluidAction.EXECUTE);
        expect(actual == offered.getAmount(), "native source accepts the counted input: " + actual + " / " + offered.getAmount() + " capacity=" + tank.getCapacity());
        introduced.merge(offered.getFluid(), (double) actual, Double::sum); kind(offered).input += actual; FluidKinetics.invalidate(h.getLevel());
    }
    int removeTank(BlockPos base, Fluid fluid, int amount) {
        var removed = tankEntity(base).getTankInventory().drain(new FluidStack(fluid, amount), FluidAction.EXECUTE);
        collected.merge(fluid, (double) removed.getAmount(), Double::sum); FluidKinetics.invalidate(h.getLevel());
        if (!removed.isEmpty()) kind(removed).output += removed.getAmount();
        return removed.getAmount();
    }
    int amount(BlockPos base, Fluid fluid) {
        var s = tankEntity(base).getTankInventory().getFluid(); return s.getFluid().isSame(fluid) ? s.getAmount() : 0;
    }
    double volume(BlockPos pos) {
        var pipe = pipe(pos); return pipe == null ? 0 : FluidKinetics.state(pipe).volume();
    }
    void after(int tick, Runnable action) {
        h.runAtTickTime(tick, () -> guarded(action));
    }
    void watch(Runnable action) {
        h.onEachTick(() -> { if (!closed) guarded(action); });
    }
    void guarded(Runnable action) {
        if (closed) return;
        try { action.run(); } catch (RuntimeException | Error failure) { dump(); cleanup(); throw failure; }
    }
    void worldFill(BlockPos pos, Fluid fluid, Direction pipeSide) {
        for (var d : Direction.values()) if (d != pipeSide) put(pos.relative(d), Blocks.BARRIER.defaultBlockState());
        put(pos, (fluid.isSame(Fluids.WATER) ? Blocks.WATER : Blocks.LAVA).defaultBlockState());
        worldSources.put(pos, fluid); introduced.merge(fluid, 1000d, Double::sum); kind(new FluidStack(fluid, 1)).input += 1000; FluidKinetics.invalidate(h.getLevel());
    }
    double body() {
        double value = 0;
        for (var p : ports.keySet()) if (channel(p)) {
            var pipe = pipe(p); if (pipe != null) value += FluidKinetics.state(pipe).volume();
        }
        return value;
    }
    int furthestWetX() {
        int value = Integer.MIN_VALUE;
        for (var p : ports.keySet()) if (channel(p)) {
            var pipe = pipe(p);
            if (pipe != null && FluidKinetics.state(pipe).volume() > 1e-8) value = Math.max(value, p.getX());
        }
        return value;
    }
    void timeout(int tick) {
        h.runAtTickTime(tick, () -> {
            if (closed) return;
            System.out.println("FLUID_COVERAGE_TIMEOUT name=" + name + " tick=" + h.getTick() + " body=" + body() + " furthest=" + furthestWetX());
            dump();
            cleanup(); h.fail(name + ": required outcome did not happen before the declared horizon");
        });
    }
    double total(Fluid fluid) {
        return total(fluid, null);
    }
    private static boolean matches(FluidStack actual, Fluid fluid, FluidStack identity) {
        return actual.getFluid().isSame(fluid) && (identity == null || FluidStack.isSameFluidSameComponents(actual, identity));
    }
    double total(Fluid fluid, FluidStack identity) {
        double sum = identity == null ? collected.getOrDefault(fluid, 0d) : kinds.stream()
            .filter(k -> FluidStack.isSameFluidSameComponents(k.identity, identity)).mapToDouble(k -> k.output).sum();
        var ends = new IdentityHashMap<OpenEndedPipe, Boolean>();
        for (var entry : ports.entrySet()) {
            var p = entry.getKey(); if (!channel(p)) continue;
            var chunk = new ChunkPos(absolute(p));
            if (suspended.containsKey(chunk) && h.getLevel().getChunkSource().getChunkNow(chunk.x, chunk.z) == null) continue;
            var pipe = pipe(p);
            expect(pipe != null, "the actual loaded channel exists: " + p);
            var s = FluidKinetics.state(pipe); double v = s.volume();
            double cap = pumps.containsKey(p) ? 100 : 50 * entry.getValue().size();
            expect(Double.isFinite(v) && v >= 0 && v <= cap + 1e-6, "actual pipe stock obeys its declared capacity: " + p + " / " + v + " / " + cap);
            expect(v == 0 || !s.fluid.isEmpty(), "a positive body has an actual fluid identity");
            if (s.phases == null) { if (matches(s.fluid, fluid, identity)) sum += v; }
            else for (var part : s.parcels()) if (!part.identity().isEmpty() && matches(part.identity(), fluid, identity)) sum += part.amount();
            for (var b : s.buffers.values()) {
                expect(Double.isFinite(b.amount) && b.amount >= 0 && (b.amount == 0 || !b.fluid.isEmpty()), "owned port inventory is finite and typed");
                for (var part : b.parcels()) if (matches(part.identity(), fluid, identity)) sum += part.amount();
            }
            for (double q : s.outward.values()) { expect(Double.isFinite(q), "edge flow is finite"); maximumFlow = Math.max(maximumFlow, Math.abs(q)); }
            for (var connection : connections(pipe)) {
                for (var source : new FlowSource[]{((IPipePressure) connection).differentialcreate$source(), ((IPipePressure) connection).differentialcreate$retainedSource()}) {
                if (source instanceof OpenEndedPipe end && ends.put(end, true) == null) {
                    var stock = end.provideHandler().getCapability().getFluidInTank(0);
                    if (matches(stock, fluid, identity)) sum += stock.getAmount();
                    var tag = end.serializeNBT(h.getLevel().registryAccess());
                    var kind = FluidStack.parseOptional(h.getLevel().registryAccess(), tag.getCompound("DifferentialDischarge"));
                    if (matches(kind, fluid, identity)) sum += tag.getDouble("DifferentialDischargeAmount");
                }
                }
            }
        }
        var controllers = new HashSet<BlockPos>();
        for (var t : tanks) {
            var chunk = new ChunkPos(absolute(t.base));
            if (suspended.containsKey(chunk) && h.getLevel().getChunkSource().getChunkNow(chunk.x, chunk.z) == null) continue;
            var be = tankEntity(t.base);
            if (controllers.add(be.getBlockPos())) { var s = be.getTankInventory().getFluid(); if (matches(s, fluid, identity)) sum += s.getAmount(); }
        }
        for (var machine : machines.entrySet()) {
            var handler = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, absolute(machine.getKey()), machine.getValue());
            expect(handler != null, "the actual native machine exposes its declared fluid capability");
            for (int slot = 0; slot < handler.getTanks(); slot++) {
                var stock = handler.getFluidInTank(slot);
                if (matches(stock, fluid, identity)) sum += stock.getAmount();
            }
        }
        for (var entry : suspended.entrySet()) if (h.getLevel().getChunkSource().getChunkNow(entry.getKey().x, entry.getKey().z) == null)
            sum += entry.getValue().getOrDefault(fluid, 0d);
        for (var entry : worldSources.entrySet()) {
            var absolute = absolute(entry.getKey()); var key = new ChunkPos(absolute);
            if (suspended.containsKey(key) && h.getLevel().getChunkSource().getChunkNow(key.x, key.z) == null) continue;
            var chunk = h.getLevel().getChunkSource().getChunkNow(key.x, key.z);
            if (chunk == null) chunk = physicalChunks.get(key);
            var state = chunk.getFluidState(absolute);
            if (entry.getValue().isSame(fluid) && state.isSource() && state.getType().isSame(fluid)) sum += 1000;
        }
        return sum;
    }
    void observeUnloads() {
        unloadListener = event -> {
            if (event.getLevel() != h.getLevel() || !(event.getChunk() instanceof LevelChunk chunk) || !forced.containsKey(chunk.getPos())) return;
            var inventory = new LinkedHashMap<Fluid, Double>();
            var controllers = new HashSet<BlockPos>();
            var ends = new IdentityHashMap<OpenEndedPipe, Boolean>();
            for (var be : chunk.getBlockEntities().values()) {
                var relative = be.getBlockPos().subtract(absolute(BlockPos.ZERO));
                if (ports.containsKey(relative) && !tankBlocks.contains(relative)) {
                    var pipe = be instanceof SmartBlockEntity smart ? smart.getBehaviour(FluidTransportBehaviour.TYPE) : null;
                    if (pipe != null) {
                        var saved = FluidKinetics.state(pipe);
                        if (saved.phases == null) { if (!saved.fluid.isEmpty()) inventory.merge(saved.fluid.getFluid(), saved.volume(), Double::sum); }
                        else for (var p : saved.parcels()) if (!p.identity().isEmpty()) inventory.merge(p.identity().getFluid(), p.amount(), Double::sum);
                        for (var b : saved.buffers.values()) for (var p : b.parcels()) inventory.merge(p.identity().getFluid(), p.amount(), Double::sum);
                        for (var c : connections(pipe))
                            for (var source : new FlowSource[]{((IPipePressure)c).differentialcreate$source(), ((IPipePressure)c).differentialcreate$retainedSource()})
                                if (source instanceof OpenEndedPipe end && ends.put(end, true) == null) {
                                    var stock = end.provideHandler().getCapability().getFluidInTank(0);
                                    if (!stock.isEmpty()) inventory.merge(stock.getFluid(), (double)stock.getAmount(), Double::sum);
                                    var tag = end.serializeNBT(h.getLevel().registryAccess());
                                    var kind = FluidStack.parseOptional(h.getLevel().registryAccess(), tag.getCompound("DifferentialDischarge"));
                                    if (!kind.isEmpty()) inventory.merge(kind.getFluid(), tag.getDouble("DifferentialDischargeAmount"), Double::sum);
                                }
                    }
                }
                if (tankBlocks.contains(relative) && be instanceof FluidTankBlockEntity tank && tank.isController()
                    && controllers.add(tank.getBlockPos())) {
                    var stock = tank.getTankInventory().getFluid();
                    if (!stock.isEmpty()) inventory.merge(stock.getFluid(), (double) stock.getAmount(), Double::sum);
                }
            }
            for (var source : worldSources.entrySet()) if (new ChunkPos(absolute(source.getKey())).equals(chunk.getPos())) {
                var world = chunk.getFluidState(absolute(source.getKey()));
                if (world.isSource() && world.getType().isSame(source.getValue())) inventory.merge(source.getValue(), 1000d, Double::sum);
            }
            suspended.put(chunk.getPos(), inventory);
            System.out.println("FLUID_COVERAGE_UNLOAD name=" + name + " chunk=" + chunk.getPos() + " stock=" + inventory);
        };
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, unloadListener);
    }
    void dump() {
        if (!Boolean.getBoolean("differentialcreate.coverage.audit")) return;
        for (var t : tanks) {
            var chunk = new ChunkPos(absolute(t.base)); if (h.getLevel().getChunkSource().getChunkNow(chunk.x, chunk.z) == null) continue;
            System.out.println("FLUID_COVERAGE_DETAIL name=" + name + " tank=" + t.base + " stock=" + tankEntity(t.base).getTankInventory().getFluid());
        }
        for (var p : ports.keySet()) if (!tankBlocks.contains(p)) {
            var chunk = new ChunkPos(absolute(p)); if (h.getLevel().getChunkSource().getChunkNow(chunk.x, chunk.z) == null) continue;
            var pipe = pipe(p); if (pipe == null) continue;
            var s = FluidKinetics.state(pipe);
            System.out.println("FLUID_COVERAGE_DETAIL name=" + name + " pos=" + p + " volume=" + s.volume() + " fluid=" + s.fluid
                + " buffers=" + s.buffers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().amount).toList() + " flows=" + s.outward
                + " pump_flow=" + s.pumpFlow
                + " phases=" + s.parcels() + " total=" + (s.phases == null ? -1 : s.phases.volume()) + "/" + s.queueCapacity
                + (pipe.blockEntity instanceof com.simibubi.create.content.fluids.pump.PumpBlockEntity pump ? " rpm=" + pump.getSpeed() : ""));
            if (pipe.interfaces != null) for (var c : pipe.interfaces.values()) {
                var src = ((IPipePressure)c).differentialcreate$source();
                if (src instanceof OpenEndedPipe end) System.out.println("FLUID_COVERAGE_END name=" + name + " pos=" + p + " data=" + end.serializeNBT(h.getLevel().registryAccess()));
            }
        }
    }
    void releaseChunks(Set<ChunkPos> chunks) {
        for (var chunk : chunks) { expect(forced.containsKey(chunk) && !forced.get(chunk), "only owned force-load tickets are released");
            h.getLevel().setChunkForced(chunk.x, chunk.z, false); }
        FluidKinetics.invalidate(h.getLevel());
    }
    void restoreChunks(Set<ChunkPos> chunks) {
        for (var chunk : chunks) h.getLevel().setChunkForced(chunk.x, chunk.z, true);
        for (var chunk : chunks) h.getLevel().getChunkSource().getChunk(chunk.x, chunk.z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        FluidKinetics.invalidate(h.getLevel());
    }
    void audit() {
        var fluids = new HashSet<>(introduced.keySet()); fluids.add(Fluids.WATER); fluids.add(Fluids.LAVA);
        for (var fluid : fluids) {
            double expected = introduced.getOrDefault(fluid, 0d), actual = total(fluid), error = Math.abs(actual - expected);
            maximumError = Math.max(maximumError, error);
            expect(error < 1e-6, "actual " + fluid + " remains conserved at tick " + h.getTick() + ": " + actual + " / " + expected);
        }
        if (kinds.stream().anyMatch(k -> !k.identity.getComponentsPatch().isEmpty())) for (var k : kinds) {
            double actual = total(k.identity.getFluid(), k.identity), error = Math.abs(actual - k.input); maximumError = Math.max(maximumError, error);
            expect(error < 1e-6, "actual data-component fluid identity is separately conserved: " + k.identity.getComponentsPatch()
                + " / " + actual + " / " + k.input);
        }
    }
    void edge(BlockPos a, BlockPos b, boolean open) {
        var d = Direction.fromDelta(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()); expect(d != null, "changed cells are adjacent");
        for (var p : List.of(a, b)) {
            var side = p.equals(a) ? d : d.getOpposite();
            expect(AllBlocks.ENCASED_FLUID_PIPE.has(state(p)), "port changes preserve the actual pipe entity");
            put(p, state(p).setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), open));
        }
        FluidKinetics.invalidate(h.getLevel());
    }
    void reload(BlockPos p) {
        var level = h.getLevel(); var old = entity(p); var tag = old.saveWithFullMetadata(level.registryAccess()); var block = state(p);
        level.removeBlockEntity(absolute(p)); var next = BlockEntity.loadStatic(absolute(p), block, tag, level.registryAccess());
        expect(next != null, "native block entity reload succeeds"); level.setBlockEntity(next);
        if (next instanceof SmartBlockEntity be) be.initialize(); FluidKinetics.invalidate(level);
    }
    void cleanup() {
        if (closed) return; closed = true;
        if (unloadListener != null) NeoForge.EVENT_BUS.unregister(unloadListener);
        for (int i = placed.size() - 1; i >= 0; i--) h.getLevel().setBlockAndUpdate(absolute(placed.get(i)), Blocks.AIR.defaultBlockState());
        for (var entry : forced.entrySet()) if (!entry.getValue()) h.getLevel().setChunkForced(entry.getKey().x, entry.getKey().z, false);
        FluidKinetics.invalidate(h.getLevel());
    }
    void done(String dimensions) {
        audit();
        System.out.println("FLUID_COVERAGE_PASS name=" + name + " tick=" + h.getTick() + " pipes=" + ports.keySet().stream().filter(this::channel).count()
            + " error=" + maximumError + " max_flow=" + maximumFlow + " dimensions=" + dimensions);
        cleanup(); h.succeed();
    }
}
