package com.diliupengyun.differential_create.kinetics;

import java.lang.ref.WeakReference;
import java.util.*;
import com.diliupengyun.differential_create.mixin.HosePulleyFluidHandlerAccessor;
import com.simibubi.create.content.fluids.*;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Main-thread native adapter: conserved ordered phases with bounded transport substeps. */
public final class FluidKinetics {
    public static final long FLUID_BUDGET_NANOS = 4_000_000;
    private static final Map<Level, Map<BlockPos, WeakReference<FluidTransportBehaviour>>> pipes = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LevelAccessor, Map<BlockPos, PipeHydraulics>> replacements = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Level, Frame> frames = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Level, WorkBudget> budgets = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class WorkBudget {
        long tick = Long.MIN_VALUE, used; int cursor;
        double airInitialised, airIn, airOut;
        final Map<Object, Double> receipts = new IdentityHashMap<>();
        void begin(Level level) { if (tick != PhysicsPreview.time(level)) { tick = PhysicsPreview.time(level); used = 0; } }
    }
    public record AirAudit(double initialised, double incoming, double outgoing) {}
    public static AirAudit airAudit(Level level) {
        var b = budgets.computeIfAbsent(level, key -> new WorkBudget()); return new AirAudit(b.airInitialised, b.airIn, b.airOut);
    }
    private FluidKinetics() {}
    private static Object inventoryKey(IFluidHandler handler) {
        return com.diliupengyun.differential_create.kinetics.portable.DockingSession.inventoryIdentity(handler);
    }
    public static void register(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || !PhysicsPreview.enabled(level) && (level.isClientSide || pipe.blockEntity.isVirtual())) return;
        if (PhysicsPreview.enabled(level) && !ticking(level, pipe.getPos())) return;
        var registry = pipes.computeIfAbsent(level, key -> new HashMap<>()); var old = registry.get(pipe.getPos());
        if (old != null && old.get() == pipe) return;
        // A newly placed channel owns air at construction, including before a
        // far forced chunk reaches its first simulation tick.
        var saved = state(pipe); var arms = EnumSet.noneOf(Direction.class);
        if (pipe.interfaces != null) for (var d : Direction.values())
            if (pipe.interfaces.containsKey(d) && pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), d)) arms.add(d);
        boolean pump = pipe.blockEntity instanceof PumpBlockEntity;
        if (pump) saved.pumpFacing = pipe.blockEntity.getBlockState().getValue(PumpBlock.FACING);
        double air = saved.airVolume(); saved.prepareQueue(Math.max(25, (pump ? 50 : 0) + arms.size() * (pump ? 25 : 50)), pump, arms);
        double change = saved.airVolume() - air; var audit = budgets.computeIfAbsent(level, key -> new WorkBudget());
        if (change > 0) audit.airInitialised += change; else audit.airOut -= change;
        registry.put(pipe.getPos(), new WeakReference<>(pipe)); invalidate(level);
    }
    public static boolean manages(Level level, BlockPos pos) { return (!level.isClientSide || PhysicsPreview.enabled(level)) && pipes.getOrDefault(level, Map.of()).containsKey(pos); }
    public static boolean hasPipes(Level level) { return !pipes.getOrDefault(level, Map.of()).isEmpty(); }
    public static Set<Level> levels() {
        synchronized (pipes) {
            var result = new HashSet<>(pipes.keySet()); result.removeIf(PhysicsPreview::enabled); return result;
        }
    }
    public static void invalidate(Level level) { frames.remove(level); KineticReadCache.invalidateFluids(); }
    public static void clear() { pipes.clear(); replacements.clear(); frames.clear(); budgets.clear(); }
    public static void resetPreview(Level level) {
        if (!PhysicsPreview.enabled(level)) throw new IllegalArgumentException("Not a physics preview");
        pipes.remove(level); replacements.remove(level); frames.remove(level); budgets.remove(level);
    }
    public static void beginTick(Level level) { var b = budgets.computeIfAbsent(level, key -> new WorkBudget()); b.tick = PhysicsPreview.time(level); b.used = 0; }
    public static PipeHydraulics state(FluidTransportBehaviour pipe) { return ((IPipeDynamics) pipe).differentialcreate$fluidState(); }
    private static BlockEntity loadedEntity(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return level.getBlockEntity(pos);
        var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4); return chunk == null ? null : chunk.getBlockEntity(pos);
    }
    private static BlockState loadedState(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return level.getBlockState(pos);
        var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4); return chunk == null ? null : chunk.getBlockState(pos);
    }
    private static FluidTransportBehaviour loadedPipe(Level level, BlockPos pos) {
        return loadedEntity(level, pos) instanceof SmartBlockEntity smart ? smart.getBehaviour(FluidTransportBehaviour.TYPE) : null;
    }
    private static boolean ticking(Level level, BlockPos pos) {
        if (level instanceof PhysicsPreview preview) return preview.differentialcreate$physical(pos);
        return level instanceof ServerLevel server ? server.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)) : level.isLoaded(pos);
    }
    public static void cacheState(LevelAccessor level, BlockPos pos) {
        var pipe = FluidPropagator.getPipe(level, pos);
        if (pipe != null && (!level.isClientSide() || level instanceof PhysicsPreview)) replacements.computeIfAbsent(level, key -> new HashMap<>()).put(pos.immutable(), state(pipe));
    }
    public static void restoreState(LevelAccessor level, BlockPos pos) {
        var cache = replacements.get(level); if (cache == null) return;
        var saved = cache.remove(pos); var pipe = FluidPropagator.getPipe(level, pos);
        if (saved != null && pipe != null) {
            var placeholder = state(pipe);
            if (placeholder != saved && level instanceof Level world)
                budgets.computeIfAbsent(world, key -> new WorkBudget()).airOut += placeholder.airVolume();
            ((IPipeDynamics) pipe).differentialcreate$adoptFluidState(saved); register(pipe);
        }
        if (cache.isEmpty()) replacements.remove(level);
    }
    public static double ownedVolume(Level level) {
        double sum = 0;
        for (var r : pipes.getOrDefault(level, Map.of()).values()) {
            var p = r.get(); if (p == null || p.blockEntity.isRemoved()) continue;
            sum += state(p).volume(); for (var b : state(p).buffers.values()) sum += b.amount;
        }
        return sum;
    }
    private static boolean allows(FluidTransportBehaviour pipe, Direction side, FluidStack phase) {
        var block = pipe.blockEntity.getBlockState();
        // Create's empty-stack predicate passes smart filters but checks valves.
        return pipe.canHaveFlowToward(block, side) && pipe.canPullFluidFrom(phase, block, side);
    }
    private static int request(double amount) { return FluidBudget.reservationAmount(amount); }
    private static boolean same(FluidStack a, FluidStack b) { return FluidMaterials.same(a, b); }
    private static void restorePhysicalConnections(FluidTransportBehaviour pipe) {
        if (pipe.interfaces == null) pipe.getConnection(Direction.DOWN);
        for (var side : Direction.values()) if (pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side)) {
            var retired = state(pipe).retainedPorts.get(side);
            if (retired != null && ticking(pipe.getWorld(), PhysicsPreview.neighbor(pipe.getWorld(), pipe.getPos(), side))
                && FluidPropagator.isOpenEnd(pipe.getWorld(), pipe.getPos(), side)) {
                // Reopening the same exterior face restores its original native
                // placement counter as well as its already extracted input.
                pipe.interfaces.put(side, retired); state(pipe).retainedPorts.remove(side);
            }
            pipe.interfaces.computeIfAbsent(side, PipeConnection::new);
        }
    }

    /** A retired world inlet finishes only its already extracted stock before
     * the new physical neighbour acquires the face. It cannot take a new bucket. */
    private static OpenEndedPipe retainedIntake(FluidTransportBehaviour pipe, Direction side) {
        if (pipe == null) return null;
        var saved = state(pipe); var archived = saved.retainedPorts.get(side);
        var live = pipe.interfaces == null ? null : pipe.interfaces.get(side);
        for (var c : new PipeConnection[]{archived, live}) if (c != null) {
            var connection = (IPipePressure)c;
            for (var source : new FlowSource[]{connection.differentialcreate$source(), connection.differentialcreate$retainedSource()})
                if (source instanceof OpenEndedPipe end) {
                    var buffer = saved.buffers.get(side);
                    if (end.provideHandler().getCapability().getFluidInTank(0).getAmount() > 0
                        || buffer != null && !buffer.delivery && buffer.amount > 0) return end;
                }
        }
        return null;
    }
    private static boolean bufferedInput(FluidTransportBehaviour pipe, Direction side) {
        if (pipe == null) return false;
        var buffer = state(pipe).buffers.get(side);
        return buffer != null && !buffer.delivery && buffer.amount > 0;
    }

    private static final class Endpoint {
        final FluidTransportBehaviour pipe; final Direction side; final FlowSource source;
        final PipeHydraulics.Buffer buffer;
        IFluidHandler handler; FluidTankBlockEntity tank;
        FluidStack offered = FluidStack.EMPTY;
        boolean supplies; double surface, face;
        boolean retained;
        Object receiptHandler; double receipt;
        Endpoint(FluidTransportBehaviour pipe, Direction side, FlowSource source) {
            this.pipe = pipe; this.side = side; this.source = source;
            buffer = state(pipe).buffers.computeIfAbsent(side, key -> new PipeHydraulics.Buffer());
            face = PhysicsPreview.height(pipe.getWorld(), pipe.getPos()) + .5 * side.getStepY(); refresh();
        }
        boolean allowsInput(FluidStack identity) {
            return retained ? pipe.canPullFluidFrom(identity, pipe.blockEntity.getBlockState(), side) : allows(pipe, side, identity);
        }
        void refresh() {
            var world = pipe.getWorld(); var pos = PhysicsPreview.neighbor(world, pipe.getPos(), side); var be = loadedEntity(world, pos);
            if (!retained && (!ticking(world, pos) || be instanceof FluidTankBlockEntity t && !ticking(world, t.getController()))) {
                handler = null; tank = null; supplies = false; return;
            }
            var provider = source == null ? null : source.provideHandler();
            // Portable interfaces revoke the cached provider while the pipe
            // frame remains reusable. Recreate only a missing generic cache;
            // tanks below already read their actual controller directly.
            if (!retained && provider == null && source instanceof FlowSource.FluidHandler && !(be instanceof FluidTankBlockEntity)) {
                source.manageSource(world, pipe.blockEntity); provider = source.provideHandler();
            }
            handler = provider == null ? null : provider.getCapability();
            tank = !retained && be instanceof FluidTankBlockEntity t ? t.getControllerBE() : null;
            if (!retained && be instanceof FluidTankBlockEntity) {
                // A reloaded non-controller can expose its constructor's local
                // tank until its first native tick refreshes the capability.
                // Use the actual multiblock controller for simulation AND commit.
                handler = tank == null ? null : world.getCapability(Capabilities.FluidHandler.BLOCK,
                    tank.getBlockPos(), side.getOpposite());
            }
            // Inventories supply and receive at the same reference pressure.
            // Their rendered level and internal geometry do not restrict a
            // permitted capability. Only actual world mouths have a water head;
            // pipe gravity and boundary kinetic-energy losses remain in the solver.
            surface = face;
            if (!retained && handler instanceof HosePulleyFluidHandlerAccessor hose) {
                var root = hose.differentialcreate$rootPosition().get(); var fluid = world.getFluidState(root);
                surface = PhysicsPreview.height(world, root) + (fluid.isEmpty() ? 0 : -.5 + fluid.getHeight(world, root));
            } else if (!retained && source instanceof OpenEndedPipe open) {
                var external = world.getFluidState(open.getOutputPos());
                surface = external.isEmpty() ? face : PhysicsPreview.height(world, open.getOutputPos()) - .5 + external.getHeight(world, open.getOutputPos());
            }
            offered = FluidStack.EMPTY; supplies = false;
            if (buffer.amount > 0) { offered = buffer.first().identity(); supplies = allowsInput(offered); return; }
            if (handler == null) return;
            if (retained) {
                var stock = handler instanceof OpenPipeDischarge.Placement ? handler.getFluidInTank(0) : FluidStack.EMPTY;
                if (!stock.isEmpty() && allowsInput(stock)) { offered = stock.copyWithAmount(1); supplies = true; }
                return;
            }
            var available = tank == null ? source.provideFluid(s -> !s.isEmpty() && allows(pipe, side, s))
                : handler.drain(1, FluidAction.SIMULATE);
            if (!available.isEmpty() && !allows(pipe, side, available)) available = FluidStack.EMPTY;
            if (!available.isEmpty()) {
                offered = available.copyWithAmount(1); supplies = true; return;
            }
            // Immersion never substitutes air for a rejected or non-source liquid.
            if (source instanceof OpenEndedPipe open) supplies = world.getFluidState(open.getOutputPos()).isEmpty();
            else if (handler instanceof HosePulleyFluidHandlerAccessor hose)
                supplies = world.getFluidState(hose.differentialcreate$rootPosition().get()).isEmpty();
            else {
                // A declared empty native slot is an explicit vented machine
                // boundary. A failed filtered drain alone is not an air source.
                for (int n = 0; n < handler.getTanks(); n++)
                    if (handler.getTankCapacity(n) > 0 && handler.getFluidInTank(n).isEmpty()) supplies = true;
            }
            supplies &= allows(pipe, side, FluidStack.EMPTY);
        }
        boolean receives(FluidStack phase) {
            if (retained || handler == null || !allows(pipe, side, phase)) return false;
            if (phase.isEmpty()) return true;
            if (buffer.amount > 0 && !same(buffer.first().identity(), phase)) {
                if (handler instanceof OpenPipeDischarge.Placement) return false;
                // An integer interface cannot accept a real .003 mB remainder.
                // Retain each such identity, without making it a permanent plug
                // against the next identity's actual representable receipts.
                for (var part : buffer.parcels()) if (!same(part.identity(), phase)
                    && FluidBudget.deliveryAmount(buffer.volume(part.identity())) > 0) return false;
            }
            if (handler instanceof OpenPipeDischarge.Placement p) {
                double accepted = p.differentialcreate$discharge(phase, 1, FluidAction.SIMULATE);
                if (accepted >= 0) return accepted > 0;
            }
            return handler.fill(phase.copyWithAmount(1), FluidAction.SIMULATE) > 0;
        }
        double pressure(FluidStack contact) {
            if (contact.isEmpty() || FluidMaterials.isGas(contact)) return 0;
            // Each pipe half's gravity is on its edge; world altitude cancels.
            double head = source instanceof OpenEndedPipe ? Math.max(0, surface - face) : surface - face;
            return FluidMaterials.of(contact).gravityForce(head);
        }
        double inputSpace(FluidStack phase) {
            if (!supplies || !same(offered, phase)) return 0;
            if (phase.isEmpty()) return Double.POSITIVE_INFINITY;
            double held = buffer.amount > 0 ? buffer.first().amount() : 0;
            if (retained) {
                var owned = handler instanceof OpenPipeDischarge.Placement ? handler.getFluidInTank(0) : FluidStack.EMPTY;
                return held + (same(owned, phase) ? owned.getAmount() : 0);
            }
            var stock = handler.drain(phase.copyWithAmount(Integer.MAX_VALUE), FluidAction.SIMULATE);
            return held + (same(stock, phase) ? stock.getAmount() : 0);
        }
        double outputSpace(FluidStack phase) {
            if (!receives(phase)) return 0;
            if (phase.isEmpty()) return Double.POSITIVE_INFINITY;
            double pending = buffer.amount;
            if (handler instanceof OpenPipeDischarge.Placement p) {
                double accepted = p.differentialcreate$discharge(phase, 1000 + pending, FluidAction.SIMULATE);
                if (accepted >= 0) return Math.max(0, accepted - pending);
            }
            double others = Math.max(0, budgets.get(pipe.getWorld()).receipts.getOrDefault(inventoryKey(handler), 0d) - receipt);
            // A bounded probe avoids overflow in native whole-bucket machines.
            return FluidBudget.receiptSpace(handler.fill(phase.copyWithAmount(1000), FluidAction.SIMULATE), others + pending);
        }
        void updateReceipt() {
            var claims = budgets.computeIfAbsent(pipe.getWorld(), key -> new WorkBudget()).receipts;
            if (receiptHandler != null && receipt != 0) claims.merge(receiptHandler, -receipt, Double::sum);
            // Integer extraction also leaves genuinely owned source stock.
            // It must retain its shared native room until consumed or returned,
            // even when another pipe is receiving at this same capability.
            double pending = buffer.amount;
            // Ports to one native inventory share its fractional receipt pool.
            // Rounding each port up would reserve two mB for .4 + .6 mB and
            // permanently prevent the last real mB from being delivered.
            receipt = pending;
            receiptHandler = inventoryKey(handler);
            if (handler != null && receipt != 0) claims.merge(receiptHandler, receipt, Double::sum);
        }
        void deliver() {
            if (!buffer.delivery || handler == null || buffer.amount == 0) return;
            if (!(handler instanceof OpenPipeDischarge.Placement)) {
                var handled = new ArrayList<FluidStack>();
                for (var part : buffer.parcels()) {
                    var identity = part.identity(); if (handled.stream().anyMatch(s -> same(s, identity))) continue;
                    handled.add(identity); int integer = Math.min(1000, FluidBudget.deliveryAmount(buffer.volume(identity)));
                    if (integer == 0) continue;
                    int accepted = handler.fill(identity.copyWithAmount(integer), FluidAction.EXECUTE);
                    if (accepted < 0 || accepted > integer) throw new IllegalStateException("Invalid external receipt");
                    if (accepted > 0) { buffer.settle(identity, accepted); pipe.blockEntity.setChanged(); updateReceipt(); }
                }
                return;
            }
            var part = buffer.first(); double sent = part.amount(), accepted = -1;
            if (handler instanceof OpenPipeDischarge.Placement p) accepted = p.differentialcreate$discharge(part.identity(), sent, FluidAction.EXECUTE);
            if (accepted == -1) {
                int integer = FluidBudget.deliveryAmount(sent); if (integer == 0) return;
                sent = integer; accepted = handler.fill(part.identity().copyWithAmount(integer), FluidAction.EXECUTE);
            }
            if (!Double.isFinite(accepted) || accepted < 0 || accepted > sent) throw new IllegalStateException("Invalid external receipt");
            if (accepted > 0) { buffer.add(part.identity(), -accepted); pipe.blockEntity.setChanged(); updateReceipt(); }
        }
        boolean reserve(FluidStack identity, double amount, double room) {
            if (identity.isEmpty()) return supplies;
            double held = buffer.amount > 0 && same(buffer.first().identity(), identity) ? buffer.first().amount() : 0;
            if (held > 0 && FluidBudget.receiptSpace(amount, held) == 0) return true;
            if (buffer.amount > held + 8 * Math.ulp(Math.max(1, buffer.amount))) return false;
            FluidStack drained;
            if (retained) {
                int cached = handler instanceof OpenPipeDischarge.Placement ? handler.getFluidInTank(0).getAmount() : 0;
                drained = cached == 0 ? FluidStack.EMPTY : handler.drain(identity.copyWithAmount(Math.min(request(amount - held), cached)), FluidAction.EXECUTE);
            } else if (handler instanceof OpenPipeDischarge.Placement p && handler.getFluidInTank(0).isEmpty())
                drained = p.differentialcreate$takeWorld(identity, request(amount - held), room);
            else {
                int requested = request(amount - held);
                drained = handler.drain(identity.copyWithAmount(requested), FluidAction.EXECUTE);
            }
            if (!drained.isEmpty()) { buffer.add(drained, drained.getAmount()); buffer.delivery = false; pipe.blockEntity.setChanged(); }
            return same(drained, identity) && FluidBudget.receiptSpace(amount, buffer.amount) == 0;
        }
    }
    private record Channel(int from, int to, Direction side, Endpoint endpoint, double length) {}
    private static final class Component {
        final List<FluidTransportBehaviour> members; final List<Endpoint> endpoints;
        final List<Channel> channels = new ArrayList<>(); final List<Set<Direction>> sides = new ArrayList<>();
        final List<List<Channel>> adjacent = new ArrayList<>();
        final Map<FluidTransportBehaviour, Integer> index = new IdentityHashMap<>();
        final Map<Object, Double> worldClaims = new IdentityHashMap<>();
        final double[] capacity, geometryCapacity;
        boolean active; List<QueuedFluidSolver.Cell> cells; List<QueuedFluidSolver.Link> links;
        TransferPlan candidatePlan;
        double transportHint = Double.POSITIVE_INFINITY;
        Component(List<FluidTransportBehaviour> members, List<Endpoint> endpoints, Map<BlockPos, FluidTransportBehaviour> live, WorkBudget audit) {
            this.members = members; this.endpoints = endpoints; capacity = new double[members.size()]; geometryCapacity = new double[members.size()];
            for (int n = 0; n < members.size(); n++) { index.put(members.get(n), n); adjacent.add(new ArrayList<>()); }
            for (int n = 0; n < members.size(); n++) {
                var pipe = members.get(n); var arms = EnumSet.noneOf(Direction.class);
                if (pipe.interfaces != null) for (var d : Direction.values()) if (pipe.interfaces.containsKey(d)
                    && pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), d)) arms.add(d);
                sides.add(arms); boolean pump = pipe.blockEntity instanceof PumpBlockEntity;
                var saved = state(pipe); double before = saved.airVolume();
                if (pump) saved.pumpFacing = pipe.blockEntity.getBlockState().getValue(PumpBlock.FACING);
                geometryCapacity[n] = Math.max(25, (pump ? 50 : 0) + arms.size() * (pump ? 25 : 50));
                saved.prepareQueue(geometryCapacity[n], pump, arms);
                capacity[n] = saved.queueCapacity;
                double change = saved.airVolume() - before;
                if (change > 0) audit.airInitialised += change; else audit.airOut -= change;
            }
            for (int n = 0; n < members.size(); n++) for (var d : sides.get(n)) {
                var next = live.get(PhysicsPreview.neighbor(members.get(n).getWorld(), members.get(n).getPos(), d)); var to = next == null ? null : index.get(next);
                if (next != null && to != null && n < to && sides.get(to).contains(d.getOpposite())) {
                    if (retainedIntake(members.get(n), d) != null || retainedIntake(next, d.getOpposite()) != null
                        || bufferedInput(members.get(n), d) || bufferedInput(next, d.getOpposite())) continue;
                    var c = new Channel(n, to, d, null, 1 - (members.get(n).blockEntity instanceof PumpBlockEntity ? .25 : 0)
                        - (next.blockEntity instanceof PumpBlockEntity ? .25 : 0));
                    channels.add(c); adjacent.get(n).add(c); adjacent.get(to).add(c);
                }
            }
            for (int b = 0; b < endpoints.size(); b++) {
                var p = endpoints.get(b); channels.add(new Channel(index.get(p.pipe), members.size() + b, p.side, p,
                    p.pipe.blockEntity instanceof PumpBlockEntity ? .25 : .5));
            }
        }
        int sign(int cell, Direction side) {
            return members.get(cell).blockEntity instanceof PumpBlockEntity pump
                && side.getAxis() == pump.getBlockState().getValue(PumpBlock.FACING).getAxis()
                ? side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1 : 0;
        }
        void begin() {
            worldClaims.clear();
            active = false;
            for (int n = 0; n < members.size(); n++) {
                var p = members.get(n); var saved = state(p);
                active |= saved.volume() > 0 || saved.outward.values().stream().anyMatch(q -> q != 0);
                if (p.blockEntity instanceof PumpBlockEntity pump) {
                    // Both Create render paths rotate about the positive world
                    // axis. Keep this rotation datum while the block arrow
                    // independently selects pressure and reaction polarity.
                    active |= NetworkDynamics.preciseSpeed(pump) != 0;
                }
            }
            for (var e : endpoints) { e.refresh(); e.deliver(); e.refresh(); active |= e.buffer.amount > 0 || e.supplies && !e.offered.isEmpty(); }
            if (!active) resetLoads();
        }
        FluidStack mouth(int cell, Direction side) {
            var saved = state(members.get(cell)); var p = saved.phases.peek(saved.front(side)); return p == null ? FluidStack.EMPTY : p.identity();
        }
        boolean contact(int edge, boolean forward, long deadline) {
            var c = channels.get(edge); var phase = forward ? mouth(c.from, c.side)
                : c.endpoint == null ? mouth(c.to, c.side.getOpposite()) : c.endpoint.offered;
            if (!(c.endpoint != null && c.endpoint.retained ? c.endpoint.allowsInput(phase) : allows(members.get(c.from), c.side, phase))) return false;
            if (c.endpoint == null) return allows(members.get(c.to), c.side.getOpposite(), phase);
            if (forward) return c.endpoint.outputSpace(phase) > 0;
            if (c.endpoint.inputSpace(phase) <= 0) return false;
            if (!c.endpoint.retained && !phase.isEmpty() && c.endpoint.buffer.amount == 0
                && c.endpoint.handler instanceof OpenPipeDischarge.Placement p && c.endpoint.handler.getFluidInTank(0).isEmpty()) {
                var whole = p.differentialcreate$worldFluid();
                return !whole.isEmpty() && FluidBudget.receiptSpace(p.differentialcreate$worldTransferSize(), intakeSpace(c.endpoint, phase, deadline)) == 0;
            }
            return true;
        }
        QueuedFluidSolver.Result sample(double minutes, long deadline) {
            if (!active || System.nanoTime() >= deadline) return QueuedFluidSolver.stationary(members.size(), channels.size());
            cells = new ArrayList<>(members.size()); links = new ArrayList<>(channels.size());
            for (int n = 0; n < members.size(); n++) {
                var p = members.get(n); var saved = state(p); double density = 0, gravity = 0;
                for (var parcel : saved.parcels()) {
                    var material = FluidMaterials.of(parcel.identity()); double part = parcel.amount() / capacity[n];
                    density += part * material.density(); if (!material.isGas()) gravity += part * material.density();
                }
                double rpm = p.blockEntity instanceof PumpBlockEntity pump ? NetworkDynamics.preciseSpeed(pump) : 0;
                int polarity = p.blockEntity instanceof PumpBlockEntity pump
                    && pump.getBlockState().getValue(PumpBlock.FACING).getAxisDirection() == Direction.AxisDirection.NEGATIVE ? -1 : 1;
                // Transport is limited by actually owned parcels, not nominal
                // geometry. After many transfers their floating-point sum can
                // be a few ULPs below capacity; never schedule that missing stock.
                double transportStock = Math.min(capacity[n], saved.phases.volume());
                cells.add(new QueuedFluidSolver.Cell(transportStock, PhysicsPreview.height(p.getWorld(), p.getPos()), density, gravity, rpm, saved.pumpFlow, polarity));
            }
            var boundary = new QueuedFluidSolver.Boundary[endpoints.size()];
            for (int b = 0; b < endpoints.size(); b++) {
                var p = endpoints.get(b); var phase = p.supplies ? p.offered : mouth(index.get(p.pipe), p.side);
                boundary[b] = new QueuedFluidSolver.Boundary(p.pressure(phase), p.supplies, p.handler != null, FluidMaterials.of(phase).density());
            }
            double[] origin = new double[channels.size()];
            for (int e = 0; e < channels.size(); e++) {
                var c = channels.get(e); var p = members.get(c.from);
                boolean open = (c.endpoint != null && c.endpoint.retained ? c.endpoint.allowsInput(FluidStack.EMPTY) : allows(p, c.side, FluidStack.EMPTY))
                    && (c.endpoint != null ? c.endpoint.handler != null || c.endpoint.retained && bufferedInput(p, c.side)
                    : allows(members.get(c.to), c.side.getOpposite(), FluidStack.EMPTY));
                links.add(new QueuedFluidSolver.Link(c.from, c.to, c.length, PhysicsPreview.height(p.getWorld(), p.getPos()) + .5 * c.side.getStepY(), sign(c.from, c.side),
                    c.endpoint == null ? sign(c.to, c.side.getOpposite()) : 0, open));
                origin[e] = state(p).outward.getOrDefault(c.side, 0d);
                if (c.endpoint == null) origin[e] = .5 * (origin[e] - state(members.get(c.to)).outward.getOrDefault(c.side.getOpposite(), 0d));
            }
            return QueuedFluidSolver.sample(cells, links, boundary, origin, minutes, deadline, (edge, forward) -> contact(edge, forward, deadline));
        }
        void advance(double minutes, long deadline, WorkBudget audit, Map<KineticBlockEntity, Double> torques, Frame frame) {
            frame.scheduledMinutes += minutes;
            if (System.nanoTime() >= deadline) { resetLoads(); return; }
            if (uniformClosed()) {
                // Identical, full parcels in a closed incompressible graph
                // have exactly zero inventory change at every node. Solve the
                // real momentum once; repeated FIFO transfers are an identity
                // operation, even when circulation crosses many cells per tick.
                var result = sample(minutes, deadline); frame.pressureSolves += result.solves();
                if (result.solves() != 0 && commit(result, minutes, deadline, audit, torques, true)) {
                    frame.advancedMinutes += minutes;
                    frame.shortestStep = Math.min(frame.shortestStep, minutes);
                } else resetLoads();
                return;
            }
            double remaining = minutes, previousDuration = Math.min(minutes, transportHint);
            int steps = 0;
            while (remaining > 0 && steps < 4096 && System.nanoTime() < deadline) {
                if (steps > 0) begin();
                if (!active) break;
                double duration = remaining / Math.max(1, Math.ceil(remaining / previousDuration));
                var result = sample(duration, deadline); frame.pressureSolves += result.solves();
                // The momentum trial is detached. If it predicts too much
                // displacement, retry the shorter interval from the same state;
                // no pressure impulse, inventory or world operation was committed.
                int retries = 0;
                while (result.solves() != 0) {
                    double allowed = QueuedFluidSolver.transportMinutes(cells, links, result.flow(), duration);
                    if (allowed >= duration) break;
                    if (!(allowed > 0) || ++retries > 32) return;
                    duration = Math.nextDown(allowed);
                    if (!(duration > 0) || remaining - duration == remaining) return;
                    frame.retries++;
                    result = sample(duration, deadline); frame.pressureSolves += result.solves();
                }
                if (result.solves() == 0) { if (steps == 0) resetLoads(); break; }
                result = limit(result, duration, deadline);
                var partTorques = new HashMap<KineticBlockEntity, Double>();
                if (!commit(result, duration, deadline, audit, partTorques, false)) break;
                double weight = duration / minutes;
                for (var entry : partTorques.entrySet()) torques.merge(entry.getKey(), entry.getValue() * weight, Double::sum);
                frame.transportSteps++;
                frame.advancedMinutes += duration;
                frame.shortestStep = Math.min(frame.shortestStep, duration);
                steps++;
                remaining = Math.max(0, remaining - duration);
                // Grow the next trial when deceleration permits it, while a
                // steady high flow keeps its already validated transfer size.
                double safe = QueuedFluidSolver.transportMinutes(cells, links, result.flow(), minutes);
                transportHint = previousDuration = safe < minutes ? .95 * safe : minutes;
            }
        }
        boolean uniformClosed() {
            if (!endpoints.isEmpty()) return false;
            FluidStack identity = null;
            for (int n = 0; n < members.size(); n++) {
                var queue = state(members.get(n)).phases;
                if (queue.size() != 1 || Math.abs(queue.volume() - capacity[n]) > 64 * Math.ulp(capacity[n])) return false;
                var phase = queue.peek(true).identity();
                if (identity == null) identity = phase;
                else if (!same(identity, phase)) return false;
            }
            return identity != null;
        }
        /** Count genuine displaceable capacity, never the open end's nominal cache. */
        double intakeSpace(Endpoint inlet, FluidStack identity, long deadline) {
            boolean[] seen = new boolean[members.size()]; var visit = new ArrayList<Integer>();
            int start = index.get(inlet.pipe); visit.add(start); seen[start] = true;
            for (int head = 0; head < visit.size(); head++) {
                if (System.nanoTime() >= deadline) return 0;
                int from = visit.get(head);
                for (var c : adjacent.get(from)) if (allows(members.get(c.from), c.side, identity)
                    && allows(members.get(c.to), c.side.getOpposite(), identity)) {
                    int other = c.from == from ? c.to : c.from;
                    if (!seen[other]) { seen[other] = true; visit.add(other); }
                }
            }
            double free = 0; boolean vent = false; double lowest = PhysicsPreview.height(inlet.pipe.getWorld(), inlet.pipe.getPos()); boolean driven = false;
            var counted = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var localPending = new IdentityHashMap<Object, Double>();
            for (var e : endpoints) if (seen[index.get(e.pipe)] && e.handler != null)
                localPending.merge(inventoryKey(e.handler), e.buffer.amount, Double::sum);
            for (int n : visit) {
                var pipe = members.get(n); var saved = state(pipe); free += saved.airVolume();
                for (var b : saved.buffers.values()) free -= b.amount;
                lowest = Math.min(lowest, PhysicsPreview.height(pipe.getWorld(), pipe.getPos()));
                if (pipe.blockEntity instanceof PumpBlockEntity pump && NetworkDynamics.preciseSpeed(pump) != 0) driven = true;
            }
            // Retained world stock is already owned, irrespective of endpoint
            // iteration order. It cannot occupy a second bucket's receiving room.
            for (var e : endpoints) if (seen[index.get(e.pipe)] && e.handler instanceof OpenPipeDischarge.Placement
                && counted.add(inventoryKey(e.handler))) free -= e.handler.getFluidInTank(0).getAmount();
            for (var e : endpoints) if (seen[index.get(e.pipe)] && e.handler != null) {
                if (!(e.handler instanceof OpenPipeDischarge.Placement) && e.receives(identity)
                    && (mouth(index.get(e.pipe), e.side).isEmpty() || same(mouth(index.get(e.pipe), e.side), identity))
                    && counted.add(inventoryKey(e.handler))) {
                    double reserved = Math.max(0, budgets.get(inlet.pipe.getWorld()).receipts.getOrDefault(inventoryKey(e.handler), 0d)
                        - localPending.getOrDefault(inventoryKey(e.handler), 0d));
                    reserved += frames.get(inlet.pipe.getWorld()).borrowedWorldRoom(e.handler, this, deadline);
                    if (!Double.isFinite(reserved)) return 0;
                    // Probe room for the new whole bucket AND previously owned
                    // stock. A 1000 mB probe minus a sub-mB remainder otherwise
                    // deadlocks a full wet pipe even beside a nearly empty tank.
                    int probe = request(1000 + Math.max(0, -free) + reserved);
                    free += FluidBudget.receiptSpace(e.handler.fill(identity.copyWithAmount(probe), FluidAction.SIMULATE), reserved);
                }
                if (e != inlet && e.receives(FluidStack.EMPTY)) { vent = true; lowest = Math.min(lowest, PhysicsPreview.height(e.pipe.getWorld(), PhysicsPreview.neighbor(e.pipe.getWorld(), e.pipe.getPos(), e.side))); }
            }
            return vent && (driven || PhysicsPreview.height(inlet.pipe.getWorld(), PhysicsPreview.neighbor(inlet.pipe.getWorld(), inlet.pipe.getPos(), inlet.side)) > lowest) ? Math.max(0, free) : 0;
        }
        /** Only other components need this claim: the owner's intake query
         * already subtracts its cached world stock. Cache the bounded walk until
         * that component next commits, without reserving imaginary tank space. */
        double borrowedWorldRoom(IFluidHandler receiver, long deadline) {
            var cached = worldClaims.get(inventoryKey(receiver)); if (cached != null) return cached;
            var identities = new ArrayList<FluidStack>(); double owned = 0;
            for (var e : endpoints) if (e.handler instanceof OpenPipeDischarge.Placement) {
                var stock = e.handler.getFluidInTank(0);
                if (!stock.isEmpty()) { owned += stock.getAmount(); if (identities.stream().noneMatch(s -> same(s, stock))) identities.add(stock.copyWithAmount(1)); }
                if (!e.buffer.delivery) for (var part : e.buffer.parcels()) {
                    owned += part.amount(); if (identities.stream().noneMatch(s -> same(s, part.identity()))) identities.add(part.identity());
                }
            }
            if (owned == 0) { worldClaims.put(inventoryKey(receiver), 0d); return 0; }
            double claim = 0;
            for (var identity : identities) {
                var seen = new boolean[members.size()]; var visit = new ArrayList<Integer>();
                for (var e : endpoints) if (inventoryKey(e.handler) == inventoryKey(receiver) && e.receives(identity)) {
                    int start = index.get(e.pipe); if (!seen[start]) { seen[start] = true; visit.add(start); }
                }
                if (visit.isEmpty()) continue;
                for (int head = 0; head < visit.size(); head++) {
                    if (System.nanoTime() >= deadline) return Double.POSITIVE_INFINITY;
                    for (var c : adjacent.get(visit.get(head))) if (allows(members.get(c.from), c.side, identity)
                        && allows(members.get(c.to), c.side.getOpposite(), identity)) {
                        int other = c.from == visit.get(head) ? c.to : c.from;
                        if (!seen[other]) { seen[other] = true; visit.add(other); }
                    }
                }
                double free = 0, alreadyReserved = 0, worldStock = 0;
                for (int n : visit) {
                    var saved = state(members.get(n)); free += saved.airVolume();
                    for (var b : saved.buffers.values()) free -= b.amount;
                }
                var counted = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                for (var e : endpoints) if (seen[index.get(e.pipe)] && e.handler != null) {
                    if (inventoryKey(e.handler) == inventoryKey(receiver)) alreadyReserved += e.buffer.amount;
                    if (e.handler instanceof OpenPipeDischarge.Placement) {
                        double stock = counted.add(inventoryKey(e.handler)) ? e.handler.getFluidInTank(0).getAmount() : 0;
                        free -= stock; worldStock += stock + (e.buffer.delivery ? 0 : e.buffer.amount);
                    }
                }
                // Mixed cached identities must not each count the same air as
                // free room. Keep their whole outstanding world stock reserved
                // until it enters physical cells; ordinary pipe FIFO is intact.
                if (worldStock > 0) claim += identities.size() == 1 ? Math.max(0, -free - alreadyReserved) : worldStock;
            }
            worldClaims.put(inventoryKey(receiver), claim); return claim;
        }
        QueuedFluidSolver.Result limit(QueuedFluidSolver.Result r, double minutes, long deadline) {
            candidatePlan = null;
            double scale = 1;
            var withdrawals = new IdentityHashMap<Object, Map<FluidStack, Double>>();
            for (int e = 0; e < channels.size(); e++) {
                var c = channels.get(e); double amount = r.flow()[e] * minutes / RotationMath.TICK_MINUTES;
                if (c.endpoint == null || amount >= 0) continue;
                var phase = c.endpoint.offered;
                if (phase.isEmpty()) continue;
                double needed = -amount, allowed = c.endpoint.inputSpace(phase);
                if (!c.endpoint.retained && c.endpoint.handler instanceof OpenPipeDischarge.Placement p
                    && c.endpoint.handler.getFluidInTank(0).isEmpty()) {
                    var whole = p.differentialcreate$worldFluid();
                    if (whole.isEmpty() || !same(whole, phase)
                        || FluidBudget.receiptSpace(p.differentialcreate$worldTransferSize(), intakeSpace(c.endpoint, phase, deadline)) != 0)
                        // A rejected new bucket does not block stock already
                        // owned at this mouth. Finish that real fractional tail
                        // rather than trying an impossible whole-world receipt.
                        allowed = Math.min(allowed, c.endpoint.buffer.amount);
                }
                scale = Math.min(scale, allowed / needed);
                if (c.endpoint.buffer.amount == 0) {
                    var kinds = withdrawals.computeIfAbsent(inventoryKey(c.endpoint.handler), key -> new LinkedHashMap<>());
                    var key = kinds.keySet().stream().filter(s -> same(s, phase)).findFirst().orElse(phase.copyWithAmount(1));
                    double total = kinds.merge(key, needed, Double::sum); scale = Math.min(scale, allowed / total);
                }
            }
            scale = Math.max(0, Math.min(1, scale));
            // Move an ordered packet across an internal port in one coarse
            // update. A phase boundary alone must not throttle every pipe.
            // Preview the same branch allocation as commit; shrinking a shared
            // flow can change which parcel the next branch sees, so recheck at
            // most three times without another pressure solve or time substep.
            for (int pass = 0; pass < QueuedFluidSolver.CONTACT_PASSES && scale > 0; pass++) {
                if (System.nanoTime() >= deadline) return QueuedFluidSolver.stationary(members.size(), channels.size());
                var plan = withdraw(r, minutes, scale);
                var receipts = new IdentityHashMap<Object, Double>();
                double restricted = scale;
                for (int e = 0; e < channels.size(); e++) {
                    var c = channels.get(e); double amount = r.flow()[e] * minutes / RotationMath.TICK_MINUTES * scale;
                    if (amount == 0 || amount < 0 && c.endpoint != null) continue;
                    double wanted = Math.abs(amount), allowed = 0;
                    // Air does not occupy a native liquid slot. A whole packet
                    // may vent air and deliver one real identity in this update,
                    // without turning every small air gap into a world-wide
                    // time boundary. Distinct real identities still queue.
                    var boundaryPhase = FluidStack.EMPTY; double delivered = 0; boolean rejected = false;
                    for (var part : plan.packets.get(e)) {
                        var phase = part.identity();
                        if (c.endpoint != null && !phase.isEmpty()) {
                            if (boundaryPhase.isEmpty()) boundaryPhase = phase;
                            else if (!same(boundaryPhase, phase)) { rejected = true; break; }
                        }
                        if (!(allows(members.get(c.from), c.side, phase) && (c.endpoint == null
                            ? allows(members.get(c.to), c.side.getOpposite(), phase)
                            : c.endpoint.receives(phase)))) { rejected = true; break; }
                        double taken = part.amount();
                        if (c.endpoint != null && !phase.isEmpty()) {
                            double room = c.endpoint.outputSpace(phase) - receipts.getOrDefault(inventoryKey(c.endpoint.handler), 0d) - delivered;
                            taken = FluidBudget.receiptTransfer(taken, room); delivered += taken;
                        }
                        allowed += taken;
                        if (taken < part.amount()) { rejected = true; break; }
                    }
                    if (c.endpoint != null && delivered > 0) receipts.merge(inventoryKey(c.endpoint.handler), delivered, Double::sum);
                    if (rejected || allowed + 8 * Math.ulp(Math.max(1, wanted)) < wanted) {
                        double bound = scale * allowed / wanted;
                        // A rejected identity must never cross a filter, even
                        // when its volume is below the ULP of this packet. Stop
                        // just before that interface and leave it in its donor;
                        // the next pressure solve then sees the correct phase.
                        if (rejected && bound > 0) bound = Math.nextDown(Math.min(scale, bound));
                        restricted = Math.min(restricted, bound); break;
                    }
                }
                if (restricted == scale) { candidatePlan = plan; return r.scale(scale, cells, links); }
                scale = restricted;
            }
            return r.scale(0, cells, links);
        }
        private record TransferPlan(List<FluidQueue<FluidStack>> next, List<List<FluidQueue.Part<FluidStack>>> packets) {}
        TransferPlan withdraw(QueuedFluidSolver.Result r, double minutes, double scale) {
            var next = new ArrayList<FluidQueue<FluidStack>>(members.size());
            for (var p : members) next.add(state(p).phases.copy());
            var packets = new ArrayList<List<FluidQueue.Part<FluidStack>>>(Collections.nCopies(channels.size(), null));
            var outlets = new ArrayList<List<Integer>>(members.size());
            for (int n = 0; n < members.size(); n++) outlets.add(null);
            double[] amounts = new double[channels.size()];
            for (int e = 0; e < channels.size(); e++) {
                var c = channels.get(e); double amount = amounts[e] = r.flow()[e] * minutes / RotationMath.TICK_MINUTES * scale;
                if (amount == 0) continue;
                if (amount < 0 && c.endpoint != null) { packets.set(e, List.of(new FluidQueue.Part<>(c.endpoint.offered, -amount))); continue; }
                int from = amount > 0 ? c.from : c.to; var saved = state(members.get(from));
                if (saved.queueFront != null) packets.set(e, next.get(from).take(Math.abs(amount), saved.front(amount > 0 ? c.side : c.side.getOpposite())));
                else {
                    if (outlets.get(from) == null) outlets.set(from, new ArrayList<>());
                    outlets.get(from).add(e);
                }
            }
            for (int n = 0; n < outlets.size(); n++) if (outlets.get(n) != null) {
                var edges = outlets.get(n); double[] rates = new double[edges.size()]; double total = 0;
                for (int k = 0; k < rates.length; k++) total += rates[k] = Math.abs(amounts[edges.get(k)]);
                var parts = next.get(n).distributeBounded(next.get(n).take(total, true), rates, state(members.get(n)).splitCursor);
                for (int k = 0; k < rates.length; k++) packets.set(edges.get(k), parts.get(k));
            }
            return new TransferPlan(next, packets);
        }
        boolean commit(QueuedFluidSolver.Result r, double minutes, long deadline, WorkBudget audit, Map<KineticBlockEntity, Double> torques, boolean uniform) {
            worldClaims.clear();
            if (r.solves() == 0) { resetLoads(); return false; }
            var plan = uniform ? new TransferPlan(members.stream().map(p -> state(p).phases).toList(), Collections.nCopies(channels.size(), null))
                : candidatePlan == null ? withdraw(r, minutes, 1) : candidatePlan;
            candidatePlan = null;
            var next = plan.next; var packets = plan.packets;
            var arriving = new ArrayList<List<List<FluidQueue.Part<FluidStack>>>>(Collections.nCopies(members.size(), null));
            // Every outgoing packet is from substep-start stock. No skipped cell.
            for (int e = 0; e < channels.size(); e++) if (packets.get(e) != null) {
                var c = channels.get(e); boolean forward = r.flow()[e] > 0;
                if (forward && c.endpoint != null) continue;
                int receiver = forward ? c.to : c.from; Direction face = forward ? c.side.getOpposite() : c.side;
                var saved = state(members.get(receiver));
                if (saved.queueFront != null) next.get(receiver).receive(packets.get(e), saved.front(face));
                else {
                    if (arriving.get(receiver) == null) arriving.set(receiver, new ArrayList<>());
                    arriving.get(receiver).add(packets.get(e));
                }
            }
            for (int n = 0; n < next.size(); n++) if (arriving.get(n) != null) next.get(n).receiveJoined(arriving.get(n));
            for (int n = 0; n < next.size(); n++)
                if (Math.abs(next.get(n).volume() - capacity[n]) > 1024 * Math.ulp(Math.max(1, capacity[n]))) { resetLoads(); return false; }
            if (System.nanoTime() >= deadline) { resetLoads(); return false; }
            // Native execution may decline a promise; keep anything actually
            // extracted in the saved mouth buffer and defer the whole component.
            for (int e = 0; e < channels.size(); e++) {
                var c = channels.get(e); double amount = r.flow()[e] * minutes / RotationMath.TICK_MINUTES;
                if (c.endpoint == null || amount >= 0) continue;
                double room = !c.endpoint.retained && c.endpoint.handler instanceof OpenPipeDischarge.Placement
                    ? intakeSpace(c.endpoint, c.endpoint.offered, deadline) : Double.POSITIVE_INFINITY;
                if (!c.endpoint.reserve(c.endpoint.offered, -amount, room)) { resetLoads(); return false; }
            }
            for (int e = 0; e < channels.size(); e++) {
                var c = channels.get(e); double amount = r.flow()[e] * minutes / RotationMath.TICK_MINUTES;
                if (c.endpoint == null || amount == 0) continue;
                if (amount < 0) {
                    if (c.endpoint.offered.isEmpty()) audit.airIn -= amount;
                    else { c.endpoint.buffer.take(-amount); c.endpoint.buffer.delivery = false; }
                } else for (var part : packets.get(e)) {
                    if (part.identity().isEmpty()) audit.airOut += part.amount();
                    else { c.endpoint.buffer.add(part.identity(), part.amount()); c.endpoint.buffer.delivery = true; }
                }
                if (c.endpoint.source != null) c.endpoint.source.keepAlive();
                c.endpoint.pipe.blockEntity.setChanged();
                c.endpoint.updateReceipt();
            }
            for (var p : members) state(p).outward.clear();
            for (int e = 0; e < channels.size(); e++) {
                var c = channels.get(e); double q = r.flow()[e]; state(members.get(c.from)).outward.put(c.side, q);
                if (c.endpoint == null) state(members.get(c.to)).outward.put(c.side.getOpposite(), -q);
            }
            for (int n = 0; n < members.size(); n++) {
                var p = members.get(n); var saved = state(p); saved.phases = next.get(n); boolean pump = p.blockEntity instanceof PumpBlockEntity;
                if (saved.queueFront == null && saved.outward.values().stream().filter(q -> q > 0).count() > 1) saved.splitCursor++;
                if (pump) {
                    var rotor = (PumpBlockEntity)p.blockEntity; double flow = 0;
                    for (var d : sides.get(n)) flow += .5 * sign(n, d) * saved.outward.getOrDefault(d, 0d);
                    saved.pumpFlow = flow;
                    ((IPumpDynamics)rotor).differentialcreate$hydraulicState(r.head()[n], flow, -r.torque()[n]);
                    torques.merge(rotor, r.torque()[n], Double::sum);
                }
                saved.adoptQueue(pump, sides.get(n));
                double beforeAir = saved.airVolume(); saved.prepareQueue(geometryCapacity[n], pump, sides.get(n));
                audit.airOut += beforeAir - saved.airVolume(); capacity[n] = saved.queueCapacity;
                p.blockEntity.setChanged();
                if (p.interfaces != null) for (var entry : p.interfaces.entrySet())
                    ((IPipePressure)entry.getValue()).differentialcreate$showFlow(mouth(n, entry.getKey()), saved.outward.getOrDefault(entry.getKey(), 0d));
            }
            for (var p : endpoints) p.deliver();
            return true;
        }
        void resetLoads() { for (var p : members) if (p.blockEntity instanceof IPumpDynamics pump) pump.differentialcreate$hydraulicState(0, 0, 0); }
    }
    public static final class Frame {
        final Level level; final List<Component> components = new ArrayList<>();
        final Map<FluidTransportBehaviour, BlockState> captured = new LinkedHashMap<>();
        final Map<FluidTransportBehaviour, Object> interfaces = new IdentityHashMap<>();
        final Map<BlockPos, BlockState> adjacentStates = new HashMap<>(); final Map<BlockPos, Object> adjacentEntities = new HashMap<>();
        final Set<BlockPos> inactive = new HashSet<>();
        final List<Endpoint> retainedInlets = new ArrayList<>();
        final Map<Object, List<Component>> sharedReceivers = new IdentityHashMap<>();
        private long acceptedSteps, jointIterations, retries; private double shortestStep = Double.POSITIVE_INFINITY;
        private long transportSteps, pressureSolves;
        private double scheduledMinutes, advancedMinutes;
        Frame(Level level) { this.level = level; }
        public int pipeCount() { return captured.size(); }
        public record Metrics(long acceptedSteps, long jointIterations, long retries, double shortestMinutes) {}
        public void resetMetrics() {
            acceptedSteps = jointIterations = retries = transportSteps = pressureSolves = 0;
            scheduledMinutes = advancedMinutes = 0; shortestStep = Double.POSITIVE_INFINITY;
        }
        public Metrics metrics() { return new Metrics(acceptedSteps, jointIterations, retries, shortestStep); }
        public long transportSteps() { return transportSteps; }
        public long pressureSolves() { return pressureSolves; }
        public double completedFraction() { return scheduledMinutes == 0 ? 1 : advancedMinutes / scheduledMinutes; }
        boolean valid() {
            // The synchronous rotor loop cannot load chunks or alter this graph
            // between world callbacks. Invalidation and callbacks clear this read.
            var reads = KineticReadCache.current();
            if (reads != null && reads.validFluids == this) return true;
            for (var inlet : retainedInlets) if (retainedIntake(inlet.pipe, inlet.side) == null && !bufferedInput(inlet.pipe, inlet.side)) return false;
            for (var pos : inactive) if (ticking(level, pos)) return false;
            for (var entry : captured.entrySet()) {
                var p = entry.getKey();
                if (p.blockEntity.isRemoved() || !ticking(level, p.getPos()) || loadedEntity(level, p.getPos()) != p.blockEntity
                    || p.blockEntity.getBlockState() != entry.getValue() || p.interfaces != interfaces.get(p)) return false;
            }
            for (var e : adjacentStates.entrySet()) if (!ticking(level, e.getKey()) || loadedState(level, e.getKey()) != e.getValue()
                || loadedEntity(level, e.getKey()) != adjacentEntities.get(e.getKey())) return false;
            for (var c : components) for (var e : c.endpoints)
                if (!e.retained && ((IPipePressure)e.pipe.getConnection(e.side)).differentialcreate$source() != e.source) return false;
            if (reads != null) reads.validFluids = this;
            return true;
        }
        void beginTick() {
            budgets.computeIfAbsent(level, key -> new WorkBudget()).receipts.clear();
            for (var c : components) for (var e : c.endpoints) { e.receipt = 0; e.receiptHandler = null; }
            for (var c : components) c.begin();
            deliverShared();
            for (var c : components) for (var e : c.endpoints) e.updateReceipt();
            sharedReceivers.clear();
            for (var c : components) for (var e : c.endpoints) if (e.handler != null && !(e.handler instanceof OpenPipeDischarge.Placement)) {
                var users = sharedReceivers.computeIfAbsent(inventoryKey(e.handler), key -> new ArrayList<>());
                if (!users.contains(c)) users.add(c);
            }
        }
        double borrowedWorldRoom(IFluidHandler receiver, Component requester, long deadline) {
            var users = sharedReceivers.get(inventoryKey(receiver)); if (users == null || users.size() < 2) return 0;
            double claimed = 0;
            for (var c : users) if (c != requester) claimed += c.borrowedWorldRoom(receiver, deadline);
            return claimed;
        }
        void deliverShared() {
            var groups = new IdentityHashMap<Object, List<Endpoint>>();
            for (var c : components) for (var e : c.endpoints)
                if (e.handler != null && !(e.handler instanceof OpenPipeDischarge.Placement))
                    groups.computeIfAbsent(inventoryKey(e.handler), key -> new ArrayList<>()).add(e);
            for (var entries : groups.entrySet()) {
                var ports = entries.getValue();
                if (ports.size() < 2 || ports.stream().noneMatch(e -> e.buffer.delivery)) continue;
                var handled = new ArrayList<FluidStack>();
                for (var lead : ports) for (var part : lead.buffer.parcels()) {
                    var identity = part.identity();
                    if (handled.stream().anyMatch(s -> same(s, identity))) continue;
                    handled.add(identity);
                    // A real receipt may settle together with unused stock
                    // extracted from this SAME native inventory. Source-only
                    // caches never trigger a return or a speculative transfer.
                    if (ports.stream().noneMatch(e -> e.buffer.delivery && e.buffer.volume(identity) > 0)) continue;
                    double total = 0, compensation = 0;
                    for (var e : ports) if (e.buffer.volume(identity) > 0) {
                        double increment = e.buffer.volume(identity) - compensation;
                        double sum = total + increment; compensation = (sum - total) - increment; total = sum;
                    }
                    int integer = Math.min(1000, FluidBudget.deliveryAmount(total));
                    if (integer == 0) {
                        returnTankFraction(ports, identity);
                        continue;
                    }
                    // Budget identity is shared; permission remains attached to an actual live endpoint.
                    var receiver = ports.stream().map(e -> e.handler).filter(h -> !(h instanceof com.diliupengyun.differential_create.kinetics.portable.DockingSession.Bound bound)
                        || bound.session().valid()).findFirst().orElse(null);
                    int accepted = receiver == null ? 0 : receiver.fill(identity.copyWithAmount(integer), FluidAction.EXECUTE);
                    if (accepted < 0 || accepted > integer) throw new IllegalStateException("Invalid shared external receipt");
                    double remaining = accepted;
                    for (var e : ports) if (remaining > 0 && e.buffer.volume(identity) > 0) {
                        double taken = Math.min(remaining, e.buffer.volume(identity));
                        e.buffer.settle(identity, taken); remaining -= taken;
                        e.pipe.blockEntity.setChanged(); e.updateReceipt(); e.refresh();
                    }
                    // The native integer adapter already permits a few ULPs
                    // of rounding at an integer boundary; no real tail is erased.
                    if (remaining > 0 && FluidBudget.receiptSpace(accepted, total) > 0)
                        throw new IllegalStateException("Shared receipt exceeded its owned parcels");
                }
            }
        }
        /** Native tanks can have real sub-mB receipt stock in their adapter.
         * A permitted port already drawing from that same tank must be able to
         * draw this stock too. Move only owned FIFO heads, with no capability
         * debit/credit, new inventory, or reclamation of an outside discharge.
         * Generic machines retain their own integer-interface rules. */
        private void returnTankFraction(List<Endpoint> ports, FluidStack identity) {
            Endpoint consumer = null;
            for (var e : ports) if (!e.retained && e.tank != null && !e.tank.boiler.isActive()
                && e.buffer.amount == 0 && state(e.pipe).outward.getOrDefault(e.side, 0d) < 0
                && e.allowsInput(identity) && e.handler.fill(identity.copyWithAmount(1), FluidAction.SIMULATE) > 0) {
                consumer = e; break;
            }
            if (consumer == null) return;
            for (var donor : ports) if (donor != consumer && donor.tank == consumer.tank && donor.buffer.delivery
                && donor.buffer.amount > 0 && same(donor.buffer.first().identity(), identity)) {
                double amount = donor.buffer.first().amount();
                donor.buffer.add(identity, -amount); consumer.buffer.add(identity, amount); consumer.buffer.delivery = false;
                donor.pipe.blockEntity.setChanged(); consumer.pipe.blockEntity.setChanged(); donor.refresh(); consumer.refresh();
            }
        }
        public boolean active() { return components.stream().anyMatch(c -> c.active); }
        public boolean active(BlockPos pos) {
            return components.stream().anyMatch(c -> c.active && c.members.stream().anyMatch(p -> p.getPos().equals(pos)));
        }
    }
    public static Frame capture(Level level) {
        var cached = frames.get(level); if (cached != null && cached.valid()) { cached.beginTick(); return cached; }
        var frame = new Frame(level); var registry = pipes.get(level); if (registry == null) return frame;
        for (var pos : new ArrayList<>(registry.keySet())) if (ticking(level, pos)) {
            var actual = loadedPipe(level, pos); var old = registry.get(pos).get();
            if (actual != null && actual != old) { if (actual.interfaces == null) actual.blockEntity.initialize(); registry.put(pos, new WeakReference<>(actual)); }
        }
        registry.entrySet().removeIf(e -> { var p = e.getValue().get(); return p == null || p.blockEntity.isRemoved()
            || ticking(level, e.getKey()) && loadedEntity(level, e.getKey()) != p.blockEntity; });
        var live = new LinkedHashMap<BlockPos, FluidTransportBehaviour>();
        registry.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (ticking(level, e.getKey())) live.put(e.getKey(), e.getValue().get()); else frame.inactive.add(e.getKey());
        });
        var visited = new HashSet<BlockPos>(); var budget = budgets.computeIfAbsent(level, key -> new WorkBudget());
        for (var seed : live.values()) if (visited.add(seed.getPos())) {
            var members = new ArrayList<FluidTransportBehaviour>(); members.add(seed); var endpoints = new ArrayList<Endpoint>();
            for (int i = 0; i < members.size(); i++) {
                var p = members.get(i); restorePhysicalConnections(p);
                frame.captured.put(p, p.blockEntity.getBlockState()); frame.interfaces.put(p, p.interfaces);
                if (p.interfaces == null) continue;
                for (var side : Direction.values()) {
                    var connection = p.interfaces.get(side);
                    if (connection == null || !p.canHaveFlowToward(p.blockEntity.getBlockState(), side)) continue;
                    var adjacent = PhysicsPreview.neighbor(level, p.getPos(), side);
                    // An outlet can border a tank-only chunk: no neighbouring
                    // pipe will register when it resumes. Track the unavailable
                    // position too, so its transition invalidates this frame.
                    if (!ticking(level, adjacent)) {
                        frame.inactive.add(adjacent);
                        var owned = retainedIntake(p, side);
                        if (owned != null || bufferedInput(p, side)) {
                            if (owned != null && owned.getWorld() == null) owned.manageSource(level, p.blockEntity);
                            var inlet = new Endpoint(p, side, owned); inlet.retained = true; inlet.refresh();
                            endpoints.add(inlet); frame.retainedInlets.add(inlet);
                        }
                        continue;
                    }
                    frame.adjacentStates.put(adjacent, loadedState(level, adjacent));
                    frame.adjacentEntities.put(adjacent, loadedEntity(level, adjacent));
                    var next = live.get(adjacent);
                    var retained = retainedIntake(p, side);
                    boolean stillOpen = next == null && FluidPropagator.isOpenEnd(level, p.getPos(), side);
                    if (retained != null && (!stillOpen || ((IPipePressure)connection).differentialcreate$source() != retained)) {
                        var inlet = new Endpoint(p, side, retained); inlet.retained = true; inlet.refresh();
                        endpoints.add(inlet); frame.retainedInlets.add(inlet); continue;
                    }
                    // A native integer withdrawal also owns a real fraction.
                    // When its container/face is gone, only that saved fraction
                    // feeds the pipe; no capability or new withdrawal is needed.
                    if (bufferedInput(p, side) && (next != null || !stillOpen && !FluidPropagator.hasFluidCapability(level, adjacent, side.getOpposite()))) {
                        var inlet = new Endpoint(p, side, null); inlet.retained = true; inlet.refresh();
                        endpoints.add(inlet); frame.retainedInlets.add(inlet); continue;
                    }
                    if (next != null && (retainedIntake(next, side.getOpposite()) != null || bufferedInput(next, side.getOpposite()))) continue;
                    if (next != null && next.canHaveFlowToward(next.blockEntity.getBlockState(), side.getOpposite())) {
                        if (visited.add(adjacent)) members.add(next); continue;
                    }
                    if (next != null) continue;
                    var old = ((IPipePressure)connection).differentialcreate$source(); boolean open = FluidPropagator.isOpenEnd(level, p.getPos(), side);
                    boolean inventory = !open && FluidPropagator.hasFluidCapability(level, adjacent, side.getOpposite());
                    if (PhysicsPreview.enabled(level) || old == null || open && !(old instanceof OpenEndedPipe) || inventory && !(old instanceof FlowSource.FluidHandler)
                        || !open && !inventory && old.isEndpoint()) connection.determineSource(level, p.getPos());
                    connection.manageSource(level, p.getPos(), p.blockEntity);
                    var source = ((IPipePressure)connection).differentialcreate$source();
                    if (source != null && source.isEndpoint() && source.provideHandler() != null && source.provideHandler().getCapability() != null)
                        endpoints.add(new Endpoint(p, side, source));
                }
            }
            // Closing a physical face may remove its native connection object.
            // Reserved input remains owned by the surviving pipe and finishes
            // before a newly attached neighbour can use that same face.
            for (var p : members) for (var side : state(p).retainedPorts.keySet()) {
                if (p.interfaces != null && p.interfaces.containsKey(side) && p.canHaveFlowToward(p.blockEntity.getBlockState(), side)) continue;
                var retained = retainedIntake(p, side); if (retained == null && !bufferedInput(p, side)) continue;
                if (retained != null) retained.manageSource(level, p.blockEntity);
                var inlet = new Endpoint(p, side, retained); inlet.retained = true; inlet.refresh();
                endpoints.add(inlet); frame.retainedInlets.add(inlet);
            }
            members.sort(Comparator.comparing(FluidTransportBehaviour::getPos));
            frame.components.add(new Component(members, endpoints, live, budget));
        }
        frames.put(level, frame); frame.beginTick(); return frame;
    }
    public static Map<KineticBlockEntity, Double> advance(Frame frame, double minutes) {
        var budget = budgets.computeIfAbsent(frame.level, key -> new WorkBudget()); budget.begin(frame.level); frame.jointIterations++;
        var torques = new HashMap<KineticBlockEntity, Double>();
        int count = frame.components.size(), first = Math.floorMod(budget.cursor, Math.max(1, count));
        boolean budgetReached = false;
        for (int step = 0; step < count; step++) {
            int c = (first + step) % count; var component = frame.components.get(c); long started = System.nanoTime(), before = budget.used;
            // Replays must not depend on machine load or video encoder scheduling.
            long deadline = PhysicsPreview.enabled(frame.level) ? Long.MAX_VALUE : started + Math.max(0, FLUID_BUDGET_NANOS - before);
            if (component.active) {
                component.advance(minutes, deadline, budget, torques, frame);
            } else component.resetLoads();
            budget.used += System.nanoTime() - started;
            if (before < FLUID_BUDGET_NANOS && budget.used >= FLUID_BUDGET_NANOS) { budget.cursor = c + 1; budgetReached = true; }
        }
        if (!budgetReached) budget.cursor = first + 1;
        frame.deliverShared();
        frame.acceptedSteps++; frame.shortestStep = Math.min(frame.shortestStep, minutes); return torques;
    }
    public static void publish(Frame frame) {
        for (var p : frame.captured.keySet()) if (!p.blockEntity.isRemoved()) {
            var saved = state(p); long hash = saved.visualHash();
            if (p.blockEntity instanceof IPumpDynamics pump) { hash = hash * 31 + Math.round(pump.differentialcreate$head() * 100); hash = hash * 31 + Math.round(pump.differentialcreate$flow() * 100); }
            if (hash != saved.lastVisualHash) { p.blockEntity.sendData(); saved.lastVisualHash = hash; }
        }
    }
}
