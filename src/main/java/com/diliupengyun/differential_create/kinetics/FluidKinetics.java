package com.diliupengyun.differential_create.kinetics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

/** Main-thread adapter. Graphs are captures; only accepted steps move inventory and publish motion. */
public final class FluidKinetics {
    private static final double VOLUME_EPSILON = 1e-9;
    private static final Map<Level, Map<BlockPos, WeakReference<FluidTransportBehaviour>>> pipes = new WeakHashMap<>();
    private static final Map<LevelAccessor, Map<BlockPos, PipeHydraulics>> replacements = new WeakHashMap<>();
    private FluidKinetics() {}
    public static void register(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide || pipe.blockEntity.isVirtual()) return;
        pipes.computeIfAbsent(level, key -> new HashMap<>()).put(pipe.getPos(), new WeakReference<>(pipe));
    }
    public static boolean manages(Level level, BlockPos pos) {
        return !level.isClientSide && pipes.getOrDefault(level, Map.of()).containsKey(pos);
    }
    public static boolean hasPipes(Level level) { return !pipes.getOrDefault(level, Map.of()).isEmpty(); }
    public static Set<Level> levels() { return new HashSet<>(pipes.keySet()); }
    public static void clear() { pipes.clear(); replacements.clear(); }
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
            if (pipe != null && !pipe.blockEntity.isRemoved())
                for (var buffer : state(pipe).buffers.values()) volume += buffer.amount;
        }
        return volume;
    }

    private record Port(BlockPos pos, Direction side) {}
    private static final class Endpoint {
        final FluidTransportBehaviour pipe;
        final Direction side;
        final FlowSource source;
        final IFluidHandler handler;
        final PipeHydraulics.Buffer buffer;
        final double referenceHeight;
        int node;
        Endpoint(FluidTransportBehaviour pipe, Direction side, FlowSource source, IFluidHandler handler) {
            this.pipe = pipe; this.side = side; this.source = source; this.handler = handler;
            buffer = state(pipe).buffers.computeIfAbsent(side, key -> new PipeHydraulics.Buffer());
            BlockPos connected = pipe.getPos().relative(side);
            var be = pipe.getWorld().getBlockEntity(connected);
            referenceHeight = be instanceof FluidTankBlockEntity tank ? tank.getController().getY() : connected.getY();
        }
        FluidStack identity() {
            if (buffer.amount > 0) return buffer.fluid;
            FluidStack fluid = handler.drain(1, FluidAction.SIMULATE);
            return fluid.isEmpty() ? FluidStack.EMPTY : fluid.copyWithAmount(1);
        }
        double available(FluidStack fluid, int requested) {
            if (!buffer.matches(fluid)) return 0;
            FluidStack drained = handler.drain(fluid.copyWithAmount(requested), FluidAction.SIMULATE);
            return buffer.amount + (same(drained, fluid) ? drained.getAmount() : 0);
        }
        boolean receives(FluidStack fluid) {
            return buffer.matches(fluid) && handler.fill(fluid.copyWithAmount(1), FluidAction.SIMULATE) > 0;
        }
        boolean reserve(FluidStack fluid, double amount) {
            double needed = amount - buffer.amount;
            if (needed <= 1e-10) return true;
            FluidStack drained = handler.drain(fluid.copyWithAmount(request(needed)), FluidAction.EXECUTE);
            if (!drained.isEmpty()) buffer.add(drained, drained.getAmount());
            pipe.blockEntity.setChanged();
            return buffer.matches(fluid) && buffer.amount + 1e-10 >= amount;
        }
        void commit(FluidStack fluid, double outward) {
            if (outward > 0) buffer.add(fluid, -outward);
            else if (outward < 0) {
                buffer.add(fluid, -outward);
                int deliver = (int) Math.min(Integer.MAX_VALUE, Math.floor(buffer.amount));
                if (deliver > 0) {
                    int accepted = handler.fill(fluid.copyWithAmount(deliver), FluidAction.EXECUTE);
                    if (accepted < 0 || accepted > deliver) throw new IllegalStateException("Invalid external fluid acceptance");
                    buffer.add(fluid, -accepted);
                }
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
    private record Connection(int from, int to, FluidTransportBehaviour pipe, Direction side,
                              FluidTransportBehaviour other, Endpoint endpoint, PumpBlockEntity pump, double length) {}

    public static final class Frame {
        final List<Component> components = new ArrayList<>();
        final Map<FluidTransportBehaviour, BlockState> captured = new HashMap<>();
        final Map<BlockPos, BlockState> adjacentStates = new HashMap<>();
        final Map<BlockPos, Object> adjacentEntities = new HashMap<>();
        final Level level;
        Frame(Level level) { this.level = level; }
        boolean valid() {
            for (var entry : captured.entrySet()) {
                var pipe = entry.getKey();
                if (pipe.blockEntity.isRemoved() || !pipe.getWorld().isLoaded(pipe.getPos())
                    || pipe.getWorld().getBlockEntity(pipe.getPos()) != pipe.blockEntity || pipe.blockEntity.getBlockState() != entry.getValue()) return false;
            }
            for (var entry : adjacentStates.entrySet())
                if (!level.isLoaded(entry.getKey()) || level.getBlockState(entry.getKey()) != entry.getValue()
                    || level.getBlockEntity(entry.getKey()) != adjacentEntities.get(entry.getKey())) return false;
            return true;
        }
        public boolean active() { return !components.isEmpty(); }
    }
    private static final class Component {
        final FluidStack fluid;
        final IdealFluidSolver.Material material;
        final List<Connection> connections;
        final List<Endpoint> endpoints;
        final List<FluidTransportBehaviour> members;
        final List<IdealFluidSolver.Edge> edges;
        final boolean[] boundary;
        final double[] pressure;
        final Map<Port, Integer> nodes;
        final Map<PumpBlockEntity, Double> axisSigns = new HashMap<>();
        Component(FluidStack fluid, List<Connection> connections, List<Endpoint> endpoints,
                  List<FluidTransportBehaviour> members, Map<Port, Integer> nodes) {
            this.fluid = fluid; material = FluidMaterials.of(fluid); this.connections = connections;
            this.endpoints = endpoints; this.members = members; this.nodes = nodes;
            boundary = new boolean[nodes.size() + endpoints.size()]; pressure = new double[boundary.length];
            for (var endpoint : endpoints) { boundary[endpoint.node] = true; pressure[endpoint.node] = material.gravityForce(endpoint.referenceHeight); }
            edges = connections.stream().map(c -> new IdealFluidSolver.Edge(c.from, c.to, material.inertia(c.length))).toList();
            for (var c : connections) if (c.pump != null) {
                double nominal = 0;
                if (c.pump.hasNetwork()) {
                    var network = c.pump.getOrCreateNetwork();
                    var ratios = ((INetworkDynamics) network).differentialcreate$getRatios();
                    double referenceDrive = 0;
                    for (var source : network.sources.keySet())
                        referenceDrive += ratios.getOrDefault(source, 0f) * SourcePhysics.curve(source).stallTorque();
                    nominal = Math.signum(ratios.getOrDefault(c.pump, 1f) * referenceDrive);
                }
                // Create's nominal 16-RPM connection datum need not have the drive's
                // direction. Map the active source command through the actual ratios,
                // then retain that orientation when the drive disconnects.
                axisSigns.put(c.pump, nominal == 0 ? state(c.pipe).pumpAxisSign : nominal);
            }
        }
        double[] initial() {
            double[] flow = new double[connections.size()];
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
        IdealFluidSolver.Trial sample(double[] initial, double[] guess, Map<KineticBlockEntity, Double> speed,
                                     double minutes, Map<KineticBlockEntity, Double> torques) {
            boolean[] active = new boolean[connections.size()]; Arrays.fill(active, true);
            double[] force = new double[active.length], couplings = new double[active.length];
            for (int e = 0; e < active.length; e++) {
                Connection c = connections.get(e);
                if (!c.pipe.canPullFluidFrom(fluid, c.pipe.blockEntity.getBlockState(), c.side == null ? Direction.UP : c.side)
                    || c.other != null && !c.other.canPullFluidFrom(fluid, c.other.blockEntity.getBlockState(), c.side.getOpposite())) active[e] = false;
                if (c.pump != null) {
                    double rpm = axisSigns.get(c.pump) * speed.getOrDefault(c.pump, NetworkDynamics.preciseSpeed(c.pump));
                    couplings[e] = IdealFluidSolver.coupling(material, rpm, guess[e]); force[e] = couplings[e] * rpm;
                }
                if (c.endpoint != null) {
                    if (guess[e] < 0 && c.endpoint.available(fluid, 1) <= VOLUME_EPSILON || guess[e] > 0 && !c.endpoint.receives(fluid)) active[e] = false;
                    if (guess[e] > 0) force[e] -= material.outletForce(guess[e]);
                }
            }
            IdealFluidSolver.Trial trial = null;
            // Enforce empty/full directions discovered by the current pressure solution.
            for (int pass = 0; pass <= active.length; pass++) {
                var graph = new IdealFluidSolver.Graph(boundary.length, edges, boundary, active);
                trial = graph.sample(initial, force, pressure, minutes);
                boolean changed = false;
                for (int e = 0; e < active.length; e++) if (active[e] && connections.get(e).endpoint != null) {
                    Endpoint endpoint = connections.get(e).endpoint; double q = trial.midpoint()[e];
                    if (q < -1e-12 && endpoint.available(fluid, 1) <= VOLUME_EPSILON || q > 1e-12 && !endpoint.receives(fluid)) {
                        active[e] = false; changed = true;
                    }
                }
                if (!changed) break;
            }
            if (trial == null) throw new IllegalStateException("No fluid trial");
            for (int e = 0; e < active.length; e++) if (connections.get(e).pump != null)
                torques.merge(connections.get(e).pump, -axisSigns.get(connections.get(e).pump) * couplings[e] * trial.midpoint()[e], Double::sum);
            return trial;
        }
        double allowedMinutes(IdealFluidSolver.Trial trial, double minutes) {
            double limit = minutes;
            for (var endpoint : endpoints) {
                double outward = trial.outward()[endpoint.node];
                if (outward <= 1e-12) continue;
                double wanted = outward * minutes / RotationMath.TICK_MINUTES;
                double available = endpoint.available(fluid, request(wanted));
                if (available < wanted) limit = Math.min(limit, available * RotationMath.TICK_MINUTES / outward);
            }
            return limit;
        }
        boolean reserve(IdealFluidSolver.Trial trial, double minutes) {
            boolean ready = true;
            for (var endpoint : endpoints) {
                double volume = trial.outward()[endpoint.node] * minutes / RotationMath.TICK_MINUTES;
                if (volume > 0) ready &= endpoint.reserve(fluid, volume);
            }
            return ready;
        }
        void commit(IdealFluidSolver.Trial trial, double minutes) {
            for (var endpoint : endpoints) endpoint.commit(fluid, trial.outward()[endpoint.node] * minutes / RotationMath.TICK_MINUTES);
            for (var pipe : members) { state(pipe).fluid = fluid.copyWithAmount(1); state(pipe).outward.clear(); }
            for (int e = 0; e < connections.size(); e++) {
                Connection c = connections.get(e); double q = trial.flow()[e];
                if (c.pump != null) {
                    var saved = state(c.pipe); saved.pumpFlow = q; saved.pumpFacing = c.pump.getBlockState().getValue(PumpBlock.FACING);
                    saved.pumpAxisSign = axisSigns.get(c.pump);
                    double rpm = axisSigns.get(c.pump) * NetworkDynamics.preciseSpeed(c.pump);
                    double gain = IdealFluidSolver.coupling(material, rpm, q);
                    double head = IdealFluidSolver.pressurePa(gain * rpm) / (material.density() * IdealFluidSolver.GRAVITY);
                    ((IPumpDynamics) c.pump).differentialcreate$hydraulicState(head, q, axisSigns.get(c.pump) * gain * q);
                } else {
                    state(c.pipe).outward.merge(c.side, q, Double::sum);
                    if (c.other != null) state(c.other).outward.merge(c.side.getOpposite(), -q, Double::sum);
                }
            }
            for (var pipe : members) {
                var saved = state(pipe);
                if (pipe.interfaces != null) for (var entry : pipe.interfaces.entrySet())
                    ((IPipePressure) entry.getValue()).differentialcreate$showFlow(fluid, saved.outward.getOrDefault(entry.getKey(), 0d));
                pipe.blockEntity.setChanged();
            }
        }
    }

    public static Frame capture(Level level) {
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
                var pipe = members.get(i); frame.captured.put(pipe, pipe.blockEntity.getBlockState());
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
            if (identity.isEmpty()) continue;
            var nodes = new LinkedHashMap<Port, Integer>();
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
                    connections.add(new Connection(node(nodes, pipe, front.getOpposite()), node(nodes, pipe, front), pipe, null, null, null, pump, .5));
                }
                if (pipe.interfaces == null) continue;
                for (Direction side : pipe.interfaces.keySet()) {
                    var next = live.get(pipe.getPos().relative(side));
                    if (next == null || pipe.getPos().compareTo(next.getPos()) >= 0 || !members.contains(next)
                        || !pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side) || !next.canHaveFlowToward(next.blockEntity.getBlockState(), side.getOpposite())) continue;
                    double length = 1 - (pipe.blockEntity instanceof PumpBlockEntity ? .25 : 0) - (next.blockEntity instanceof PumpBlockEntity ? .25 : 0);
                    connections.add(new Connection(node(nodes, pipe, side), node(nodes, next, side.getOpposite()), pipe, side, next, null, null, length));
                }
            }
            for (int i = 0; i < endpoints.size(); i++) {
                var endpoint = endpoints.get(i); endpoint.node = nodes.size() + i;
                connections.add(new Connection(node(nodes, endpoint.pipe, endpoint.side), endpoint.node,
                    endpoint.pipe, endpoint.side, null, endpoint, null, endpoint.pipe.blockEntity instanceof PumpBlockEntity ? .25 : .5));
            }
            if (!connections.isEmpty()) frame.components.add(new Component(identity, connections, endpoints, members, nodes));
        }
        return frame;
    }
    private static int node(Map<Port, Integer> nodes, FluidTransportBehaviour pipe, Direction side) {
        return nodes.get(new Port(pipe.getPos(), pipe.blockEntity instanceof PumpBlockEntity ? side : null));
    }

    public static final class JointTrial {
        public final ElasticKinetics.Trial mechanical;
        private final Frame frame;
        private final List<IdealFluidSolver.Trial> fluids;
        JointTrial(Frame frame, ElasticKinetics.Trial mechanical, List<IdealFluidSolver.Trial> fluids) {
            this.frame = frame; this.mechanical = mechanical; this.fluids = fluids;
        }
        public void commit() {
            mechanical.commit();
            for (int i = 0; i < fluids.size(); i++) frame.components.get(i).commit(fluids.get(i), mechanical.result.duration());
        }
    }

    /** Fixed-point midpoint coupling keeps the same pump coefficient on both energy sides.
     * Shortened machine/fracture/empty-source events recompute the entire detached step. */
    public static JointTrial sample(Frame frame, List<KineticNetwork> networks, double horizon, List<ElasticKinetics.WorkEvent> events) {
        double minutes = horizon;
        String stalled = "iteration";
        for (int retry = 0; retry < 24; retry++) {
            var speeds = new HashMap<KineticBlockEntity, Double>();
            for (var network : networks) for (var be : network.members.keySet()) speeds.put(be, NetworkDynamics.preciseSpeed(be));
            var initial = frame.components.stream().map(Component::initial).toList();
            var guesses = initial.stream().map(double[]::clone).toList();
            var lastTorques = new HashMap<KineticBlockEntity, Double>();
            boolean shorter = false;
            for (int iteration = 0; iteration < 80; iteration++) {
                var torques = new HashMap<KineticBlockEntity, Double>();
                var fluids = new ArrayList<IdealFluidSolver.Trial>();
                double allowed = minutes;
                for (int c = 0; c < frame.components.size(); c++) {
                    var component = frame.components.get(c);
                    var trial = component.sample(initial.get(c), guesses.get(c), speeds, minutes, torques);
                    fluids.add(trial); allowed = Math.min(allowed, component.allowedMinutes(trial, minutes));
                }
                if (allowed < minutes * (1 - 1e-9)) {
                    stalled = "source volume " + allowed + " / " + minutes;
                    minutes = allowed; shorter = true; break;
                }
                var capture = ElasticKinetics.snapshot(networks, torques);
                ElasticKinetics.Trial mechanical = events.isEmpty() ? capture.sample(minutes) : capture.advanceToEvent(minutes, events);
                if (mechanical.result.duration() < minutes * (1 - 1e-9)) { stalled = "mechanical event"; minutes = mechanical.result.duration(); shorter = true; break; }
                double difference = 0;
                var nextSpeeds = new HashMap<KineticBlockEntity, Double>();
                for (var be : mechanical.members) nextSpeeds.put(be, mechanical.signedTravel(be) * RotationMath.TICK_MINUTES / minutes);
                for (var entry : torques.entrySet()) difference = Math.max(difference,
                    Math.abs(entry.getValue() - lastTorques.getOrDefault(entry.getKey(), 0d)) / (1 + Math.abs(entry.getValue())));
                for (int c = 0; c < fluids.size(); c++) for (int e = 0; e < guesses.get(c).length; e++)
                    difference = Math.max(difference, Math.abs(fluids.get(c).midpoint()[e] - guesses.get(c)[e]) / (1 + Math.abs(fluids.get(c).midpoint()[e])));
                if (difference < 1e-10 && iteration > 0) {
                    double exchanged = 0, shaftWork = 0;
                    for (int c = 0; c < fluids.size(); c++) {
                        var component = frame.components.get(c);
                        for (int e = 0; e < component.connections.size(); e++) {
                            var pump = component.connections.get(e).pump;
                            if (pump == null) continue;
                            double rpm = component.axisSigns.get(pump) * speeds.getOrDefault(pump, NetworkDynamics.preciseSpeed(pump));
                            double gain = IdealFluidSolver.coupling(component.material, rpm, guesses.get(c)[e]);
                            exchanged += minutes * gain * rpm * fluids.get(c).midpoint()[e];
                        }
                    }
                    for (var entry : torques.entrySet()) shaftWork -= entry.getValue() * mechanical.signedTravel(entry.getKey()) * RotationMath.TICK_MINUTES;
                    if (Math.abs(exchanged - shaftWork) > 1e-9 * (Math.abs(exchanged) + Math.abs(shaftWork)) + 1e-12) {
                        stalled = "work " + exchanged + " / " + shaftWork;
                        minutes /= 2; shorter = true; break;
                    }
                    boolean ready = true;
                    for (int c = 0; c < fluids.size(); c++) ready &= frame.components.get(c).reserve(fluids.get(c), minutes);
                    if (!ready) { stalled = "reservation"; shorter = true; break; }
                    return new JointTrial(frame, mechanical, fluids);
                }
                lastTorques = torques;
                stalled = "iteration residual " + difference;
                for (int c = 0; c < fluids.size(); c++) System.arraycopy(fluids.get(c).midpoint(), 0, guesses.get(c), 0, guesses.get(c).length);
                speeds = nextSpeeds;
            }
            if (!shorter) minutes /= 2;
            if (!(minutes > 1e-15)) throw new IllegalStateException("Fluid event failed to advance");
        }
        throw new IllegalStateException("Ideal fluid / shaft coupling did not converge: " + stalled + "; step " + minutes);
    }
    public static void publish(Frame frame) {
        for (var pipe : frame.captured.keySet()) if (!pipe.blockEntity.isRemoved()) pipe.blockEntity.sendData();
    }
}
