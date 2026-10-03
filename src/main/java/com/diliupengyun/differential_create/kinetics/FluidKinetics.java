package com.diliupengyun.differential_create.kinetics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Main-thread adapter for cached connections, real inventory and a common fluid/shaft clock. */
public final class FluidKinetics {
    private static final double VOLUME_EPSILON = 1e-9;
    private static final Map<Level, Map<BlockPos, WeakReference<FluidTransportBehaviour>>> pipes = new WeakHashMap<>();
    private static final Map<LevelAccessor, Map<BlockPos, PipeHydraulics>> replacements = new WeakHashMap<>();
    private static final Map<Level, Frame> frames = new WeakHashMap<>();
    private FluidKinetics() {}
    public static void register(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide || pipe.blockEntity.isVirtual()) return;
        var registry = pipes.computeIfAbsent(level, key -> new HashMap<>());
        var previous = registry.get(pipe.getPos());
        if (previous != null && previous.get() == pipe) return;
        registry.put(pipe.getPos(), new WeakReference<>(pipe)); frames.remove(level);
    }
    public static boolean manages(Level level, BlockPos pos) {
        return !level.isClientSide && pipes.getOrDefault(level, Map.of()).containsKey(pos);
    }
    public static boolean hasPipes(Level level) { return !pipes.getOrDefault(level, Map.of()).isEmpty(); }
    public static Set<Level> levels() { return new HashSet<>(pipes.keySet()); }
    public static void invalidate(Level level) { frames.remove(level); }
    public static void clear() { pipes.clear(); replacements.clear(); frames.clear(); }
    public static PipeHydraulics state(FluidTransportBehaviour pipe) { return ((IPipeDynamics) pipe).differentialcreate$fluidState(); }
    public static void cacheState(LevelAccessor level, BlockPos pos) {
        var pipe = FluidPropagator.getPipe(level, pos);
        if (pipe != null && !level.isClientSide()) replacements.computeIfAbsent(level, key -> new HashMap<>()).put(pos.immutable(), state(pipe));
    }
    public static void restoreState(LevelAccessor level, BlockPos pos) {
        var cache = replacements.get(level);
        if (cache == null) return;
        var saved = cache.remove(pos);
        var pipe = FluidPropagator.getPipe(level, pos);
        if (saved != null && pipe != null) {
            ((IPipeDynamics) pipe).differentialcreate$adoptFluidState(saved);
            register(pipe);
        }
        if (cache.isEmpty()) replacements.remove(level);
    }
    public static double ownedVolume(Level level) {
        double volume = 0;
        for (var reference : pipes.getOrDefault(level, Map.of()).values()) {
            var pipe = reference.get();
            if (pipe != null && !pipe.blockEntity.isRemoved()) {
                volume += state(pipe).volume();
                for (var buffer : state(pipe).buffers.values()) volume += buffer.amount;
            }
        }
        return volume;
    }

    private record Port(BlockPos pos, Direction side) {}
    private static final class Endpoint {
        final FluidTransportBehaviour pipe;
        final Direction side;
        final FlowSource source;
        IFluidHandler handler;
        InventoryPool inventory;
        final PipeHydraulics.Buffer buffer;
        double referenceHeight;
        int node;
        double faceHeight;
        Endpoint(FluidTransportBehaviour pipe, Direction side, FlowSource source, IFluidHandler handler) {
            this.pipe = pipe; this.side = side; this.source = source; this.handler = handler;
            buffer = state(pipe).buffers.computeIfAbsent(side, key -> new PipeHydraulics.Buffer());
            faceHeight = pipe.getPos().getY() + side.getStepY() * .5;
            refresh();
        }
        void refresh() {
            var provider = source.provideHandler(); handler = provider == null ? null : provider.getCapability();
            BlockPos connected = pipe.getPos().relative(side);
            var be = pipe.getWorld().getBlockEntity(connected);
            referenceHeight = be instanceof FluidTankBlockEntity tank ? tank.getController().getY() : connected.getY();
        }
        FluidStack identity() {
            if (buffer.amount > 0) return buffer.fluid;
            if (handler == null) return FluidStack.EMPTY;
            FluidStack fluid = handler.drain(1, FluidAction.SIMULATE);
            return fluid.isEmpty() ? FluidStack.EMPTY : fluid.copyWithAmount(1);
        }
        double available(FluidStack fluid, int requested) {
            if (handler == null || !buffer.matches(fluid)) return 0;
            FluidStack drained = handler.drain(fluid.copyWithAmount(requested), FluidAction.SIMULATE);
            return buffer.amount + (same(drained, fluid) ? drained.getAmount() : 0);
        }
        boolean receives(FluidStack fluid) {
            return handler != null && buffer.matches(fluid) && (inventory == null
                ? handler.fill(fluid.copyWithAmount(1), FluidAction.SIMULATE) > 0 : inventory.receiptSpace(1) > 0);
        }
        void commit(FluidStack fluid, double outward) {
            if (outward > 0) buffer.add(fluid, -outward);
            else if (outward < 0) {
                buffer.add(fluid, -outward);
                buffer.delivery = true;
            }
            if (outward != 0) {
                source.keepAlive(); source.whileFlowPresent(pipe.getWorld(), outward > 0);
                pipe.blockEntity.setChanged();
            }
        }
    }
    private static int request(double volume) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.ceil(volume)));
    }
    private static boolean same(FluidStack a, FluidStack b) {
        return !a.isEmpty() && !b.isEmpty() && FluidStack.isSameFluidSameComponents(a, b);
    }
    private record InventoryPort(int component, Endpoint endpoint) {}
    private static final class InventoryPool {
        final IFluidHandler handler;
        final FluidStack fluid;
        final List<InventoryPort> ports = new ArrayList<>();
        double pending;
        InventoryPool(IFluidHandler handler, FluidStack fluid) { this.handler = handler; this.fluid = fluid; }
        void refresh() {
            pending = 0; double roundoff = 0;
            for (var port : ports) {
                var buffer = port.endpoint.buffer;
                if (!buffer.delivery) continue;
                double amount = buffer.amount - roundoff, next = pending + amount;
                roundoff = (next - pending) - amount; pending = next;
            }
        }
        double receiptSpace(double volume) {
            int capacity = handler.fill(fluid.copyWithAmount(request(pending + volume)), FluidAction.SIMULATE);
            return FluidBudget.receiptSpace(capacity, pending);
        }
        double[] rates(List<FluidTrial> trials) {
            double[] rates = new double[ports.size()];
            for (int i = 0; i < rates.length; i++) {
                var port = ports.get(i);
                rates[i] = trials.get(port.component).filling.waterOutward()[port.endpoint.node] / RotationMath.TICK_MINUTES;
            }
            return rates;
        }
        double allowed(List<FluidTrial> trials, double minutes) {
            double[] rates = rates(trials), owned = new double[rates.length];
            double incoming = 0;
            for (int i = 0; i < rates.length; i++) {
                var buffer = ports.get(i).endpoint.buffer;
                if (rates[i] < 0) { incoming -= rates[i]; rates[i] = 0; }
                owned[i] = buffer.amount;
            }
            double wanted = FluidBudget.needed(rates, owned, minutes), limit = minutes;
            if (wanted > 1e-10) {
                FluidStack stock = handler.drain(fluid.copyWithAmount(request(wanted)), FluidAction.SIMULATE);
                limit = FluidBudget.limit(rates, owned, same(stock, fluid) ? stock.getAmount() : 0, minutes);
            }
            if (incoming > 0) limit = Math.min(limit, receiptSpace(incoming * minutes) / incoming);
            return limit;
        }
        boolean reserve(List<FluidTrial> trials, double minutes) {
            double[] rates = rates(trials), need = new double[rates.length];
            double total = 0; int last = -1;
            for (int i = 0; i < need.length; i++) {
                need[i] = Math.max(0, rates[i] * minutes - ports.get(i).endpoint.buffer.amount);
                if (need[i] > 0) { total += need[i]; last = i; }
            }
            if (total <= 1e-10) return true;
            FluidStack stock = handler.drain(fluid.copyWithAmount(request(total)), FluidAction.EXECUTE);
            if (!stock.isEmpty()) {
                if (!same(stock, fluid)) throw new IllegalStateException("External source returned a different fluid");
                double left = stock.getAmount();
                for (int i = 0; i < need.length; i++) if (need[i] > 0) {
                    double share = i == last ? left : stock.getAmount() * need[i] / total; left -= share;
                    var endpoint = ports.get(i).endpoint;
                    endpoint.buffer.add(fluid, share); endpoint.buffer.delivery = false;
                    endpoint.pipe.blockEntity.setChanged();
                }
                refresh();
            }
            for (int i = 0; i < need.length; i++) if (rates[i] * minutes > ports.get(i).endpoint.buffer.amount + 1e-10) return false;
            return true;
        }
        void deliver() {
            refresh();
            double total = pending; int last = -1;
            for (int i = 0; i < ports.size(); i++) {
                var buffer = ports.get(i).endpoint.buffer;
                if (buffer.delivery && buffer.amount > 0) last = i;
            }
            int amount = FluidBudget.deliveryAmount(total);
            if (amount == 0) return;
            int accepted = handler.fill(fluid.copyWithAmount(amount), FluidAction.EXECUTE);
            if (accepted < 0 || accepted > amount) throw new IllegalStateException("Invalid external fluid acceptance");
            double left = accepted;
            for (int i = 0; i < ports.size(); i++) {
                var endpoint = ports.get(i).endpoint; var buffer = endpoint.buffer;
                if (!buffer.delivery || buffer.amount == 0) continue;
                double share = Math.max(0, Math.min(buffer.amount, i == last ? left : accepted * (buffer.amount / total)));
                left -= share;
                buffer.add(fluid, -share); endpoint.pipe.blockEntity.setChanged();
            }
            refresh();
        }
    }
    private record Connection(int from, int to, FluidTransportBehaviour pipe, Direction side,
                              FluidTransportBehaviour other, Endpoint endpoint, PumpBlockEntity pump, double length, boolean sealed) {
        double firstLength() { return pipe.blockEntity instanceof PumpBlockEntity ? .25 : .5; }
        PipeContents contents() {
            var saved = state(pipe);
            if (pump != null) return saved.pumpFacing != null && saved.pumpFacing != pump.getBlockState().getValue(PumpBlock.FACING)
                ? saved.core.reverse() : saved.core;
            if (other == null) return saved.content(side);
            return PipeContents.join(saved.content(side), state(other).content(side.getOpposite()).reverse(), firstLength() / length);
        }
        void contents(PipeContents contents) {
            if (pump != null) { state(pipe).core = contents; return; }
            if (other == null) { state(pipe).contents.put(side, contents); return; }
            double cut = firstLength() / length;
            state(pipe).contents.put(side, contents.slice(0, cut));
            state(other).contents.put(side.getOpposite(), contents.slice(cut, 1).reverse());
        }
        double fromHeight() {
            if (!(pipe.blockEntity instanceof PumpBlockEntity p)) return pipe.getPos().getY();
            Direction front = p.getBlockState().getValue(PumpBlock.FACING);
            return pipe.getPos().getY() + (pump != null ? -front.getStepY() : side.getStepY()) * .25;
        }
        double toHeight() {
            if (pump != null) return pipe.getPos().getY() + pump.getBlockState().getValue(PumpBlock.FACING).getStepY() * .25;
            if (other == null) return pipe.getPos().getY() + side.getStepY() * .5;
            return other.getPos().getY() - (other.blockEntity instanceof PumpBlockEntity ? side.getStepY() * .25 : 0);
        }
    }

    public static final class Frame {
        final List<Component> components = new ArrayList<>();
        final List<InventoryPool> inventories = new ArrayList<>();
        final Map<FluidTransportBehaviour, BlockState> captured = new HashMap<>();
        final Map<FluidTransportBehaviour, Object> interfaces = new HashMap<>();
        final Map<BlockPos, BlockState> adjacentStates = new HashMap<>();
        final Map<BlockPos, Object> adjacentEntities = new HashMap<>();
        final Level level;
        Frame(Level level) { this.level = level; }
        boolean valid() {
            for (var entry : captured.entrySet()) {
                var pipe = entry.getKey();
                if (pipe.blockEntity.isRemoved() || !pipe.getWorld().isLoaded(pipe.getPos())
                    || pipe.getWorld().getBlockEntity(pipe.getPos()) != pipe.blockEntity || pipe.blockEntity.getBlockState() != entry.getValue()
                    || pipe.interfaces != interfaces.get(pipe)) return false;
            }
            for (var entry : adjacentStates.entrySet())
                if (!level.isLoaded(entry.getKey()) || level.getBlockState(entry.getKey()) != entry.getValue()
                    || level.getBlockEntity(entry.getKey()) != adjacentEntities.get(entry.getKey())) return false;
            for (var component : components) for (var endpoint : component.endpoints)
                if (((IPipePressure) endpoint.pipe.getConnection(endpoint.side)).differentialcreate$source() != endpoint.source) return false;
            return true;
        }
        void beginTick() {
            for (var component : components) component.beginTick();
            for (var component : components) for (var endpoint : component.endpoints) endpoint.inventory = null;
            inventories.clear();
            var shared = new IdentityHashMap<IFluidHandler, List<InventoryPool>>();
            for (int c = 0; c < components.size(); c++) {
                var component = components.get(c); if (!component.active) continue;
                for (var endpoint : component.endpoints) if (endpoint.handler != null && endpoint.buffer.matches(component.fluid)) {
                    var candidates = shared.computeIfAbsent(endpoint.handler, key -> new ArrayList<>());
                    var pool = candidates.stream().filter(p -> same(p.fluid, component.fluid)).findFirst().orElse(null);
                    if (pool == null) { pool = new InventoryPool(endpoint.handler, component.fluid); candidates.add(pool); inventories.add(pool); }
                    pool.ports.add(new InventoryPort(c, endpoint));
                    endpoint.inventory = pool;
                }
            }
            // Complete saved integer-boundary receipts before projecting motion.
            // Native open ends may consume that integer and immediately reopen.
            for (var pool : inventories) pool.deliver();
            for (var component : components) if (component.rest != null && component.restInputs != component.inputs(null)) component.rest = null;
        }
        public boolean active() { return components.stream().anyMatch(c -> c.active); }
    }
    private record FluidTrial(IdealFluidSolver.Trial motion, PipeFilling.Move filling, double[] origin, double allowed) {}
    private static final class Component {
        FluidStack fluid;
        IdealFluidSolver.Material material;
        boolean active;
        final List<Connection> connections;
        final List<Endpoint> endpoints;
        final List<FluidTransportBehaviour> members;
        final List<IdealFluidSolver.Edge> edges;
        final List<PipeFilling.Tube> tubes;
        final boolean[] boundary;
        final double[] pressure;
        final Map<PumpBlockEntity, Double> axisSigns = new HashMap<>();
        ReducedFluidGraph reduced;
        FluidTrial rest;
        long restInputs;
        boolean restIndependentSpeed;
        Component(FluidStack fluid, List<Connection> connections, List<Endpoint> endpoints,
                  List<FluidTransportBehaviour> members) {
            this.fluid = fluid; material = fluid.isEmpty() ? new IdealFluidSolver.Material(1000, false) : FluidMaterials.of(fluid); this.connections = connections;
            this.endpoints = endpoints; this.members = members;
            int count = 0; for (var c : connections) count = Math.max(count, 1 + Math.max(c.from, c.to));
            boundary = new boolean[count]; pressure = new double[count];
            for (var endpoint : endpoints) boundary[endpoint.node] = true;
            edges = connections.stream().map(c -> new IdealFluidSolver.Edge(c.from, c.to, material.inertia(c.length))).toList();
            tubes = connections.stream().map(c -> new PipeFilling.Tube(c.from, c.to, c.length, c.fromHeight(), c.toHeight())).toList();
        }
        void beginTick() {
            // Create's nominal connection datum need not have the drive's direction.
            // Refresh the source command through actual ratios even with cached pipes;
            // retain that orientation when the drive disconnects.
            for (var c : connections) if (c.pump != null) {
                double nominal = 0;
                if (c.pump.hasNetwork()) {
                    var network = c.pump.getOrCreateNetwork();
                    var ratios = ((INetworkDynamics) network).differentialcreate$getRatios();
                    double drive = 0;
                    for (var source : network.sources.keySet()) drive += ratios.getOrDefault(source, 0f) * SourcePhysics.curve(source).stallTorque();
                    nominal = Math.signum(ratios.getOrDefault(c.pump, 1f) * drive);
                }
                axisSigns.put(c.pump, nominal == 0 ? state(c.pipe).pumpAxisSign : nominal);
            }
            FluidStack owned = FluidStack.EMPTY;
            for (var pipe : members) {
                var saved = state(pipe);
                if (saved.volume() > VOLUME_EPSILON && !saved.fluid.isEmpty()) { owned = saved.fluid; break; }
                for (var buffer : saved.buffers.values()) if (buffer.amount > VOLUME_EPSILON) { owned = buffer.fluid; break; }
                if (!owned.isEmpty()) break;
            }
            for (var endpoint : endpoints) endpoint.refresh();
            if (owned.isEmpty()) for (var endpoint : endpoints) { owned = endpoint.identity(); if (!owned.isEmpty()) break; }
            active = !owned.isEmpty();
            if (active && !same(owned, fluid)) {
                rest = null;
                fluid = owned.copyWithAmount(1); material = FluidMaterials.of(fluid);
                for (var pipe : members) { state(pipe).outward.clear(); state(pipe).pumpFlow = 0; }
            }
            if (!active) for (var c : connections) if (c.pump != null) {
                double rpm = axisSigns.get(c.pump) * NetworkDynamics.preciseSpeed(c.pump);
                ((IPumpDynamics) c.pump).differentialcreate$hydraulicState(
                    IdealFluidSolver.pressurePa(IdealFluidSolver.coupling(material, rpm, 0) * rpm) / (material.density() * IdealFluidSolver.GRAVITY), 0, 0);
            }
        }
        long inputs(Map<KineticBlockEntity, Double> speed) {
            long key = 1;
            for (var endpoint : endpoints) {
                key = key * 31 + Double.doubleToLongBits(endpoint.available(fluid, 1));
                key = key * 31 + (endpoint.receives(fluid) ? 1 : 0);
                key = key * 31 + Double.doubleToLongBits(endpoint.referenceHeight);
                key = key * 31 + (endpoint.identity().isEmpty() || same(endpoint.identity(), fluid) ? 1 : 0);
            }
            if (!restIndependentSpeed) for (var c : connections) if (c.pump != null) {
                double rpm = axisSigns.get(c.pump) * (speed == null ? NetworkDynamics.preciseSpeed(c.pump)
                    : speed.getOrDefault(c.pump, NetworkDynamics.preciseSpeed(c.pump)));
                key = key * 31 + Double.doubleToLongBits(rpm);
            }
            return key;
        }
        PipeContents[] contents() {
            return rest != null ? rest.filling.contents() : connections.stream().map(Connection::contents).toArray(PipeContents[]::new);
        }
        double[] seed(double[] initial, Map<KineticBlockEntity, Double> speed, double minutes) {
            if (!active || rest != null || Arrays.stream(initial).anyMatch(q -> q != 0)) return initial.clone();
            boolean[] enabled = new boolean[connections.size()]; Arrays.fill(enabled, true);
            boolean[] borders = boundary.clone();
            double[] force = new double[connections.size()], mass = new double[force.length], datum = new double[boundary.length];
            for (int e = 0; e < force.length; e++) {
                var c = connections.get(e); mass[e] = material.inertia(c.length);
                force[e] = -material.gravityForce(c.toHeight() - c.fromHeight()) * c.contents().fraction();
                if (c.pump != null) {
                    double rpm = axisSigns.get(c.pump) * speed.getOrDefault(c.pump, NetworkDynamics.preciseSpeed(c.pump));
                    force[e] += IdealFluidSolver.coupling(material, rpm, 0) * rpm;
                }
                if (c.sealed) borders[c.to] = !c.contents().atEnd();
                if (c.endpoint != null && c.endpoint.available(fluid, 1) > VOLUME_EPSILON)
                    datum[c.to] = Math.max(0, material.gravityForce(c.endpoint.referenceHeight - c.endpoint.faceHeight));
            }
            double[] result = graph(borders, enabled).sample(initial, mass, force, datum, minutes).midpoint();
            double maximum = Arrays.stream(result).map(Math::abs).max().orElse(0), scale = 16;
            for (var c : connections) if (c.pump != null) scale = Math.max(scale, IdealFluidSolver.REFERENCE_FLOW
                * Math.abs(speed.getOrDefault(c.pump, NetworkDynamics.preciseSpeed(c.pump))) / IdealFluidSolver.REFERENCE_RPM);
            if (maximum > 0 && members.stream().allMatch(pipe -> state(pipe).volume() == 0))
                for (int e = 0; e < result.length; e++) result[e] *= scale / maximum;
            return result;
        }
        ReducedFluidGraph graph(boolean[] borders, boolean[] enabled) {
            if (reduced == null || !reduced.matches(borders, enabled)) reduced = new ReducedFluidGraph(boundary.length, edges, borders, enabled);
            return reduced;
        }
        double[] initial() {
            double[] flow = new double[connections.size()];
            if (!active) return flow;
            for (int e = 0; e < flow.length; e++) {
                Connection c = connections.get(e); PipeHydraulics saved = state(c.pipe);
                if (!same(saved.fluid, fluid)) continue;
                if (c.pump != null) {
                    Direction front = c.pump.getBlockState().getValue(PumpBlock.FACING);
                    flow[e] = saved.pumpFlow * (saved.pumpFacing == null || saved.pumpFacing == front ? 1 : -1);
                } else if (c.other != null) {
                    double right = same(state(c.other).fluid, fluid) ? state(c.other).outward.getOrDefault(c.side.getOpposite(), 0d) : 0;
                    flow[e] = .5 * (saved.outward.getOrDefault(c.side, 0d) - right);
                } else flow[e] = saved.outward.getOrDefault(c.side, 0d);
            }
            return flow;
        }
        FluidTrial sample(double[] initial, PipeContents[] contents, double[] guess, Map<KineticBlockEntity, Double> speed,
                          double minutes, Map<KineticBlockEntity, Double> torques) {
            if (!active) return new FluidTrial(new IdealFluidSolver.Trial(new double[connections.size()], new double[connections.size()],
                new double[boundary.length], new double[boundary.length], 0, 0, 0, 0), null, initial, minutes);
            if (rest != null) {
                if (restInputs == inputs(speed)) return new FluidTrial(rest.motion, rest.filling, rest.origin, minutes);
                rest = null;
            }
            boolean[] enabled = new boolean[connections.size()]; Arrays.fill(enabled, true);
            boolean[] borders = boundary.clone();
            double[] force = new double[enabled.length], couplings = new double[enabled.length], supplied = new double[boundary.length];
            var contacts = new ArrayList<FluidPressureBounds.Contact>();
            Arrays.fill(pressure, 0);
            for (var endpoint : endpoints) supplied[endpoint.node] = endpoint.available(fluid, 1) > VOLUME_EPSILON ? 1 : 0;
            for (int e = 0; e < enabled.length; e++) {
                Connection c = connections.get(e);
                if (!c.pipe.canPullFluidFrom(fluid, c.pipe.blockEntity.getBlockState(), c.side == null ? Direction.UP : c.side)
                    || c.other != null && !c.other.canPullFluidFrom(fluid, c.other.blockEntity.getBlockState(), c.side.getOpposite())
                    || state(c.pipe).volume() > VOLUME_EPSILON && !same(state(c.pipe).fluid, fluid)
                    || c.other != null && state(c.other).volume() > VOLUME_EPSILON && !same(state(c.other).fluid, fluid)) enabled[e] = false;
                if (c.pump != null) {
                    double rpm = axisSigns.get(c.pump) * speed.getOrDefault(c.pump, NetworkDynamics.preciseSpeed(c.pump));
                    couplings[e] = IdealFluidSolver.coupling(material, rpm, guess[e]); force[e] = couplings[e] * rpm;
                }
                if (c.sealed) borders[c.to] = !contents[e].atEnd(); // Empty space vents; the wall never emits liquid.
                if (c.endpoint != null) {
                    if (guess[e] > 0 && contents[e].atEnd() && !c.endpoint.receives(fluid)) enabled[e] = false;
                    if (guess[e] < 0 && !c.endpoint.identity().isEmpty() && !same(c.endpoint.identity(), fluid)) enabled[e] = false;
                    double liquid = guess[e] < 0 ? supplied[c.to] : contents[e].atEnd() ? 1 : 0;
                    if (guess[e] == 0 && c.endpoint.referenceHeight > c.endpoint.faceHeight) liquid = supplied[c.to];
                    pressure[c.to] = liquid * material.gravityForce(c.endpoint.referenceHeight - c.endpoint.faceHeight);
                    double liquidPressure = material.gravityForce(c.endpoint.referenceHeight - c.endpoint.faceHeight);
                    if (!contents[e].atEnd() && supplied[c.to] != 0 && liquidPressure < 0)
                        contacts.add(new FluidPressureBounds.Contact(c.to, liquidPressure));
                }
            }
            IdealFluidSolver.Trial trial = null; PipeFilling.Move move = null;
            double predictedLimit = minutes;
            double[] origin = initial, mass = new double[connections.size()];
            for (int e = 0; e < mass.length; e++) mass[e] = material.inertia(connections.get(e).length) * Math.max(1e-12, contents[e].fraction());
            for (int pass = 0; pass <= enabled.length; pass++) {
                var graph = graph(borders, enabled);
                // Only a real connection/closed-mouth impulse projects old motion.
                origin = graph.sample(initial, mass, new double[mass.length], new double[boundary.length], minutes).flow();
                // A shortened prefix's mass-flux force must not act over the full
                // horizon. Predict full-horizon coefficients; only a feasible final
                // trajectory is transported and committed below.
                var prediction = PipeFilling.predict(tubes, boundary.length, contents, origin, guess, boundary, supplied, material, minutes);
                predictedLimit = prediction.allowedMinutes();
                if (Arrays.stream(prediction.inertia()).allMatch(value -> value == 0)) {
                    double[] zero = new double[connections.size()];
                    return new FluidTrial(new IdealFluidSolver.Trial(zero, zero.clone(), new double[boundary.length], new double[boundary.length], 0, 0, 0, 0),
                        new PipeFilling.Move(contents, new double[boundary.length], zero.clone(), zero.clone(), minutes), zero.clone(), minutes);
                }
                double[] averageMass = prediction.inertia().clone(), applied = force.clone();
                for (int e = 0; e < mass.length; e++) {
                    // Positive matrix weights approach the zero-mass empty-space limit.
                    averageMass[e] = Math.max(averageMass[e], material.inertia(connections.get(e).length) * 1e-12);
                    applied[e] += prediction.correction()[e];
                    var endpoint = connections.get(e).endpoint;
                    if (endpoint != null && guess[e] > 0) {
                        double liquid = Math.max(0, Math.min(1, -prediction.waterOutward()[endpoint.node] / guess[e]));
                        pressure[endpoint.node] = liquid * material.gravityForce(endpoint.referenceHeight - endpoint.faceHeight);
                        applied[e] -= liquid * material.outletForce(guess[e]);
                    }
                    if (connections.get(e).pump != null) {
                        var pump = connections.get(e).pump;
                        double rpm = axisSigns.get(pump) * speed.getOrDefault(pump, NetworkDynamics.preciseSpeed(pump));
                        double slope = Math.min(0, IdealFluidSolver.pumpSlope(material, rpm, guess[e]));
                        // Solve the pump's stiff, linear flow dependence implicitly.
                        // These tangent terms cancel at convergence; they add no mass,
                        // friction or stored energy to the accepted physical state.
                        averageMass[e] -= .5 * minutes * slope;
                        applied[e] += slope * (origin[e] - guess[e]);
                    }
                }
                var motionGraph = graph;
                if (!contacts.isEmpty()) {
                    boolean[] pinned = FluidPressureBounds.solve(graph, origin, averageMass, applied, pressure, contacts, minutes);
                    boolean[] contactBorders = borders.clone(); boolean holds = false;
                    for (var contact : contacts) if (pinned[contact.node()]) {
                        // A held dry front constrains a stationary or wholly empty
                        // channel. Numerical empty-space weights cannot retain a
                        // sign-alternating momentum; any actual water keeps inertia.
                        boolean stationary = true;
                        for (int e = 0; e < origin.length; e++) if (connections.get(e).to == contact.node() && origin[e] != 0) stationary = false;
                        if (stationary || graph.emptyBoundaryChannel(contact.node(), contents)) {
                            contactBorders[contact.node()] = false; holds = true;
                        }
                    }
                    if (holds) motionGraph = graph(contactBorders, enabled);
                }
                trial = motionGraph.sample(origin, averageMass, applied, pressure, minutes);
                boolean changed = false;
                for (int e = 0; e < enabled.length; e++) if (enabled[e] && connections.get(e).endpoint != null) {
                    Endpoint endpoint = connections.get(e).endpoint; double q = trial.midpoint()[e];
                    if (q > 0 && contents[e].atEnd() && !endpoint.receives(fluid)) {
                        enabled[e] = false; changed = true;
                    }
                }
                if (!changed) break;
            }
            if (trial == null) throw new IllegalStateException("No fluid trial");
            double allowed = predictedLimit;
            move = PipeFilling.sample(tubes, boundary.length, contents, origin, trial.midpoint(), boundary, supplied, material, minutes);
            allowed = Math.min(allowed, move.allowedMinutes());
            for (int e = 0; e < enabled.length; e++) if (connections.get(e).pump != null)
                torques.merge(connections.get(e).pump, -axisSigns.get(connections.get(e).pump) * couplings[e] * trial.midpoint()[e], Double::sum);
            return new FluidTrial(trial, move, origin, allowed);
        }
        void commit(FluidTrial trial, double minutes) {
            if (!active) return;
            boolean stationary = Arrays.stream(trial.motion.flow()).allMatch(q -> q == 0)
                && Arrays.stream(trial.motion.midpoint()).allMatch(q -> q == 0);
            if (stationary && rest != null) {
                for (var c : connections) if (c.pump != null) {
                    double rpm = axisSigns.get(c.pump) * NetworkDynamics.preciseSpeed(c.pump);
                    double gain = IdealFluidSolver.coupling(material, rpm, 0);
                    ((IPumpDynamics) c.pump).differentialcreate$hydraulicState(
                        IdealFluidSolver.pressurePa(gain * rpm) / (material.density() * IdealFluidSolver.GRAVITY), 0, 0);
                }
                return;
            }
            for (var endpoint : endpoints) endpoint.commit(fluid, trial.filling.waterOutward()[endpoint.node] * minutes / RotationMath.TICK_MINUTES);
            for (var pipe : members) {
                var saved = state(pipe);
                if (saved.volume() <= VOLUME_EPSILON || same(saved.fluid, fluid)) saved.fluid = fluid.copyWithAmount(1);
                saved.outward.clear();
            }
            for (int e = 0; e < connections.size(); e++) {
                Connection c = connections.get(e); double q = trial.motion.flow()[e];
                if (trial.motion.midpoint()[e] != 0 || c.pump != null && state(c.pipe).pumpFacing != c.pump.getBlockState().getValue(PumpBlock.FACING))
                    c.contents(trial.filling.contents()[e]);
                if (c.pump != null) {
                    var saved = state(c.pipe); saved.pumpFlow = q; saved.pumpFacing = c.pump.getBlockState().getValue(PumpBlock.FACING);
                    saved.pumpAxisSign = axisSigns.get(c.pump);
                    double rpm = axisSigns.get(c.pump) * NetworkDynamics.preciseSpeed(c.pump);
                    double gain = IdealFluidSolver.coupling(material, rpm, q);
                    double head = IdealFluidSolver.pressurePa(gain * rpm) / (material.density() * IdealFluidSolver.GRAVITY);
                    ((IPumpDynamics) c.pump).differentialcreate$hydraulicState(head,
                        q * state(c.pipe).core.fraction(), axisSigns.get(c.pump) * gain * q);
                } else {
                    state(c.pipe).outward.merge(c.side, q, Double::sum);
                    if (c.other != null) state(c.other).outward.merge(c.side.getOpposite(), -q, Double::sum);
                }
            }
            for (var pipe : members) {
                var saved = state(pipe);
                if (pipe.interfaces != null) for (var entry : pipe.interfaces.entrySet())
                    ((IPipePressure) entry.getValue()).differentialcreate$showFlow(saved.content(entry.getKey()).fraction() == 0 ? FluidStack.EMPTY : saved.fluid,
                        saved.outward.getOrDefault(entry.getKey(), 0d));
                pipe.blockEntity.setChanged();
            }
            if (stationary) {
                restIndependentSpeed = reduced != null && reduced.stationary();
                // A suction front reopens when pump pressure overcomes its datum.
                if (restIndependentSpeed) for (var endpoint : endpoints) if (endpoint.referenceHeight < endpoint.faceHeight
                    && !state(endpoint.pipe).content(endpoint.side).atEnd() && endpoint.available(fluid, 1) > VOLUME_EPSILON)
                    restIndependentSpeed = false;
                restInputs = inputs(null); rest = trial;
            }
        }
    }

    public static Frame capture(Level level) {
        var cached = frames.get(level);
        if (cached != null && cached.valid()) { cached.beginTick(); return cached; }
        Frame frame = new Frame(level);
        var registry = pipes.get(level);
        if (registry == null) return frame;
        var live = new LinkedHashMap<BlockPos, FluidTransportBehaviour>();
        registry.entrySet().removeIf(entry -> {
            var pipe = entry.getValue().get();
            return pipe == null || pipe.blockEntity.isRemoved() || !level.isLoaded(entry.getKey())
                || level.getBlockEntity(entry.getKey()) != pipe.blockEntity;
        });
        registry.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> live.put(entry.getKey(), entry.getValue().get()));
        Set<BlockPos> visited = new HashSet<>();
        for (var seed : live.values()) if (visited.add(seed.getPos())) {
            var members = new ArrayList<FluidTransportBehaviour>(); members.add(seed);
            var endpoints = new ArrayList<Endpoint>();
            for (int i = 0; i < members.size(); i++) {
                var pipe = members.get(i); frame.captured.put(pipe, pipe.blockEntity.getBlockState()); frame.interfaces.put(pipe, pipe.interfaces);
                if (pipe.interfaces == null) continue;
                for (var entry : pipe.interfaces.entrySet()) {
                    Direction side = entry.getKey();
                    if (!pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side) || !level.isLoaded(pipe.getPos().relative(side))) continue;
                    BlockPos adjacent = pipe.getPos().relative(side);
                    frame.adjacentStates.put(adjacent, level.getBlockState(adjacent));
                    frame.adjacentEntities.put(adjacent, level.getBlockEntity(adjacent));
                    var next = live.get(pipe.getPos().relative(side));
                    if (next != null && next.canHaveFlowToward(next.blockEntity.getBlockState(), side.getOpposite())) {
                        if (visited.add(next.getPos())) members.add(next);
                        continue;
                    }
                    if (next != null) continue; // A closed valve is a wall, not an external source.
                    var connection = entry.getValue();
                    FlowSource previous = ((IPipePressure) connection).differentialcreate$source();
                    boolean open = FluidPropagator.isOpenEnd(level, pipe.getPos(), side);
                    boolean inventory = !open && FluidPropagator.hasFluidCapability(level, pipe.getPos().relative(side), side.getOpposite());
                    // Open ends have an original 1000-mB handler of their own. Keep it
                    // between captures; recreating it every tick would discard its fluid.
                    if (previous == null || open && !(previous instanceof OpenEndedPipe)
                        || inventory && !(previous instanceof FlowSource.FluidHandler)
                        || !open && !inventory && previous.isEndpoint()) connection.determineSource(level, pipe.getPos());
                    connection.manageSource(level, pipe.getPos(), pipe.blockEntity);
                    FlowSource source = ((IPipePressure) connection).differentialcreate$source();
                    if (source == null || !source.isEndpoint()) continue;
                    var provider = source.provideHandler(); IFluidHandler handler = provider == null ? null : provider.getCapability();
                    if (handler != null) endpoints.add(new Endpoint(pipe, side, source, handler));
                }
            }
            members.sort(Comparator.comparing(FluidTransportBehaviour::getPos));
            FluidStack identity = FluidStack.EMPTY;
            for (var pipe : members) if (!state(pipe).fluid.isEmpty()) { identity = state(pipe).fluid.copyWithAmount(1); break; }
            if (identity.isEmpty()) for (var endpoint : endpoints) {
                identity = endpoint.identity(); if (!identity.isEmpty()) break;
            }
            var nodes = new LinkedHashMap<Port, Integer>();
            var memberSet = new HashSet<>(members);
            for (var pipe : members) {
                if (pipe.blockEntity instanceof PumpBlockEntity pump) {
                    Direction front = pump.getBlockState().getValue(PumpBlock.FACING);
                    nodes.put(new Port(pipe.getPos(), front.getOpposite()), nodes.size()); nodes.put(new Port(pipe.getPos(), front), nodes.size());
                } else nodes.put(new Port(pipe.getPos(), null), nodes.size());
            }
            var connections = new ArrayList<Connection>();
            for (var pipe : members) {
                if (pipe.blockEntity instanceof PumpBlockEntity pump) {
                    Direction front = pump.getBlockState().getValue(PumpBlock.FACING);
                    connections.add(new Connection(node(nodes, pipe, front.getOpposite()), node(nodes, pipe, front), pipe, null, null, null, pump, .5, false));
                }
                if (pipe.interfaces == null) continue;
                for (Direction side : pipe.interfaces.keySet()) {
                    var next = live.get(pipe.getPos().relative(side));
                    if (next == null || pipe.getPos().compareTo(next.getPos()) >= 0 || !memberSet.contains(next)
                        || !pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side) || !next.canHaveFlowToward(next.blockEntity.getBlockState(), side.getOpposite())) continue;
                    double length = 1 - (pipe.blockEntity instanceof PumpBlockEntity ? .25 : 0) - (next.blockEntity instanceof PumpBlockEntity ? .25 : 0);
                    connections.add(new Connection(node(nodes, pipe, side), node(nodes, next, side.getOpposite()), pipe, side, next, null, null, length, false));
                }
            }
            for (int i = 0; i < endpoints.size(); i++) {
                var endpoint = endpoints.get(i); endpoint.node = nodes.size() + i;
                connections.add(new Connection(node(nodes, endpoint.pipe, endpoint.side), endpoint.node,
                    endpoint.pipe, endpoint.side, null, endpoint, null, endpoint.pipe.blockEntity instanceof PumpBlockEntity ? .25 : .5, false));
            }
            var inventoryPorts = new HashSet<Port>();
            for (var endpoint : endpoints) inventoryPorts.add(new Port(endpoint.pipe.getPos(), endpoint.side));
            int nextNode = nodes.size() + endpoints.size();
            for (var pipe : members) if (pipe.interfaces != null) for (var side : pipe.interfaces.keySet()) {
                if (!pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side) || inventoryPorts.contains(new Port(pipe.getPos(), side))) continue;
                var other = live.get(pipe.getPos().relative(side));
                if (other != null && memberSet.contains(other) && other.canHaveFlowToward(other.blockEntity.getBlockState(), side.getOpposite())) continue;
                connections.add(new Connection(node(nodes, pipe, side), nextNode++, pipe, side, null, null, null,
                    pipe.blockEntity instanceof PumpBlockEntity ? .25 : .5, true));
            }
            if (!connections.isEmpty()) frame.components.add(new Component(identity, connections, endpoints, members));
        }
        frames.put(level, frame); frame.beginTick();
        return frame;
    }
    private static int node(Map<Port, Integer> nodes, FluidTransportBehaviour pipe, Direction side) {
        return nodes.get(new Port(pipe.getPos(), pipe.blockEntity instanceof PumpBlockEntity ? side : null));
    }

    public static final class JointTrial {
        public final ElasticKinetics.Trial mechanical;
        private final Frame frame;
        private final List<FluidTrial> fluids;
        JointTrial(Frame frame, ElasticKinetics.Trial mechanical, List<FluidTrial> fluids) {
            this.frame = frame; this.mechanical = mechanical; this.fluids = fluids;
        }
        public void commit() {
            mechanical.commit();
            for (int i = 0; i < fluids.size(); i++) frame.components.get(i).commit(fluids.get(i), mechanical.result.duration());
            for (var pool : frame.inventories) pool.deliver();
        }
    }

    /** Fixed-point midpoint coupling keeps the same pump coefficient on both energy sides.
     * Shortened machine/fracture/empty-source events recompute the entire detached step. */
    public static JointTrial sample(Frame frame, List<KineticNetwork> networks, double horizon, List<ElasticKinetics.WorkEvent> events) {
        double minutes = horizon;
        String stalled = "iteration";
        int eventShortens = 0;
        for (int retry = 0; retry < 64; retry++) {
            var speeds = new HashMap<KineticBlockEntity, Double>();
            for (var network : networks) for (var be : network.members.keySet()) speeds.put(be, NetworkDynamics.preciseSpeed(be));
            var initial = frame.components.stream().map(Component::initial).toList();
            var contents = frame.components.stream().map(Component::contents).toList();
            var guesses = new ArrayList<double[]>();
            for (int c = 0; c < frame.components.size(); c++) guesses.add(frame.components.get(c).seed(initial.get(c), speeds, minutes));
            var lastTorques = new HashMap<KineticBlockEntity, Double>();
            var previousResidual = guesses.stream().map(q -> new double[q.length]).toList();
            var previousStep = guesses.stream().map(q -> new double[q.length]).toList();
            boolean shorter = false;
            for (int iteration = 0; iteration < 80; iteration++) {
                var torques = new HashMap<KineticBlockEntity, Double>();
                var fluids = new ArrayList<FluidTrial>();
                double allowed = minutes;
                Object limiting = null;
                for (int c = 0; c < frame.components.size(); c++) {
                    var component = frame.components.get(c);
                    var trial = component.sample(initial.get(c), contents.get(c), guesses.get(c), speeds, minutes, torques);
                    fluids.add(trial);
                    if (trial.allowed < allowed) { allowed = trial.allowed; limiting = component; }
                }
                for (var pool : frame.inventories) {
                    double limit = pool.allowed(fluids, minutes);
                    if (limit < allowed) { allowed = limit; limiting = pool; }
                }
                double fluidDifference = 0;
                for (int c = 0; c < fluids.size(); c++) for (int e = 0; e < guesses.get(c).length; e++)
                    fluidDifference = Math.max(fluidDifference, Math.abs(fluids.get(c).motion.midpoint()[e] - guesses.get(c)[e])
                        / (1 + Math.abs(fluids.get(c).motion.midpoint()[e])));
                if (allowed < minutes * (1 - 1e-12)) {
                    // Intermediate velocities can reverse an almost empty branch.
                    // Converge that fluid prediction before searching its event or
                    // sampling the shaft with an inadmissible transport interval.
                    if (fluidDifference >= 1e-10 || iteration == 0) {
                        stalled = "fluid iteration residual " + fluidDifference;
                        relax(fluids, guesses, previousResidual, previousStep, iteration);
                        continue;
                    }
                    stalled = (limiting instanceof Component component ? "pipe mouth at " + component.members.getFirst().getPos()
                        : limiting instanceof InventoryPool pool ? "inventory at " + pool.ports.getFirst().endpoint.pipe.getPos()
                            + (allowed > 1e-18 ? "" : " pending=" + pool.pending + " rates=" + Arrays.toString(pool.rates(fluids))
                                + " owned=" + pool.ports.stream().map(p -> p.endpoint.buffer.amount).toList()) : "fluid volume")
                        + " " + allowed + " / " + minutes;
                    // Use a sharp event estimate first. If re-solving keeps moving
                    // it, backtrack to a feasible prefix instead of approaching
                    // the revised face time forever from above.
                    minutes = ++eventShortens <= 2 ? allowed : Math.min(allowed, .5 * minutes);
                    shorter = true; break;
                }
                var capture = ElasticKinetics.snapshot(networks, torques);
                ElasticKinetics.Trial mechanical = events.isEmpty() ? capture.sample(minutes) : capture.advanceToEvent(minutes, events);
                if (mechanical.result.duration() < minutes * (1 - 1e-9)) { stalled = "mechanical event"; minutes = mechanical.result.duration(); shorter = true; break; }
                double difference = fluidDifference;
                var nextSpeeds = new HashMap<KineticBlockEntity, Double>();
                for (var be : mechanical.members) nextSpeeds.put(be, mechanical.signedTravel(be) * RotationMath.TICK_MINUTES / minutes);
                for (var entry : torques.entrySet()) difference = Math.max(difference,
                    Math.abs(entry.getValue() - lastTorques.getOrDefault(entry.getKey(), 0d)) / (1 + Math.abs(entry.getValue())));
                if (difference < 1e-10 && iteration > 0) {
                    double exchanged = 0, shaftWork = 0;
                    for (int c = 0; c < fluids.size(); c++) {
                        var component = frame.components.get(c);
                        for (int e = 0; e < component.connections.size(); e++) {
                            var pump = component.connections.get(e).pump;
                            if (pump == null) continue;
                            double rpm = component.axisSigns.get(pump) * speeds.getOrDefault(pump, NetworkDynamics.preciseSpeed(pump));
                            double gain = IdealFluidSolver.coupling(component.material, rpm, guesses.get(c)[e]);
                            exchanged += minutes * gain * rpm * fluids.get(c).motion.midpoint()[e];
                        }
                    }
                    for (var entry : torques.entrySet()) shaftWork -= entry.getValue() * mechanical.signedTravel(entry.getKey()) * RotationMath.TICK_MINUTES;
                    if (Math.abs(exchanged - shaftWork) > 1e-9 * (Math.abs(exchanged) + Math.abs(shaftWork)) + 1e-12) {
                        stalled = "work " + exchanged + " / " + shaftWork;
                        minutes /= 2; shorter = true; break;
                    }
                    boolean ready = true;
                    for (var pool : frame.inventories) ready &= pool.reserve(fluids, minutes);
                    if (!ready) {
                        stalled = "reservation";
                        shorter = true; break;
                    }
                    return new JointTrial(frame, mechanical, fluids);
                }
                lastTorques = torques;
                stalled = "iteration residual " + difference;
                relax(fluids, guesses, previousResidual, previousStep, iteration);
                speeds = nextSpeeds;
            }
            if (!shorter) minutes /= 2;
            if (!(minutes > 1e-18)) throw new IllegalStateException("Fluid event failed to advance: " + stalled + "; step=" + minutes);
        }
        throw new IllegalStateException("Ideal fluid / shaft coupling did not converge: " + stalled + "; step " + minutes);
    }
    private static void relax(List<FluidTrial> fluids, List<double[]> guesses, List<double[]> previousResidual,
                              List<double[]> previousStep, int iteration) {
        double numerator = 0, denominator = 0;
        for (int c = 0; c < fluids.size(); c++) for (int e = 0; e < guesses.get(c).length; e++) {
            double residual = fluids.get(c).motion.midpoint()[e] - guesses.get(c)[e];
            double change = residual - previousResidual.get(c)[e];
            numerator -= previousStep.get(c)[e] * change; denominator += change * change;
        }
        double relaxation = iteration == 0 ? .1 : denominator == 0 ? 1 : Math.max(.03, Math.min(1, numerator / denominator));
        for (int c = 0; c < fluids.size(); c++) for (int e = 0; e < guesses.get(c).length; e++) {
            double residual = fluids.get(c).motion.midpoint()[e] - guesses.get(c)[e];
            previousResidual.get(c)[e] = residual; previousStep.get(c)[e] = relaxation * residual;
            guesses.get(c)[e] += previousStep.get(c)[e];
        }
    }
    public static void publish(Frame frame) {
        for (var pipe : frame.captured.keySet()) if (!pipe.blockEntity.isRemoved()) {
            var saved = state(pipe); long hash = saved.visualHash();
            if (pipe.blockEntity instanceof IPumpDynamics pump) {
                hash = hash * 31 + Math.round(pump.differentialcreate$head() * 100);
                hash = hash * 31 + Math.round(pump.differentialcreate$flow() * 100);
            }
            if (hash != saved.lastVisualHash) { pipe.blockEntity.sendData(); saved.lastVisualHash = hash; }
        }
    }
}
