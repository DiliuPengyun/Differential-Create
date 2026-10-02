package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import net.minecraft.core.BlockPos;

/** Minecraft adapter for the same finite-stiffness law at all times. */
public final class ElasticKinetics {
    private ElasticKinetics() {}
    public static final class Connection {
        public final KineticBlockEntity a, b;
        public final double ratio;
        public final ClutchBlockEntity clutch;
        public double twist, torque;
        public boolean slipping;
        public Connection(KineticBlockEntity a, KineticBlockEntity b, double ratio, ClutchBlockEntity clutch) {
            this.a = a; this.b = b; this.ratio = ratio; this.clutch = clutch;
            var saved = state(a).elasticConnections.get(b.getBlockPos());
            if (saved != null && Math.abs(saved.ratio() - ratio) < 1e-8) twist = saved.twist();
        }
        public void save() { state(a).elasticConnections.put(b.getBlockPos(), new MemberState.SavedConnection(ratio, twist)); }
        // Two equal port compliances in series, referred to b. Reversing a/b changes
        // coefficients by q^2, so physical behavior cannot depend on block-coordinate ordering.
        public double stiffness() { return 2 * PhysicsTuning.TORSIONAL_STIFFNESS / (1 + ratio * ratio); }
        public double elasticEnergy() { return .5 * stiffness() * twist * twist; }
        double damping() { return 2 * PhysicsTuning.TORSIONAL_DAMPING / (1 + ratio * ratio); }
        double friction() { return clutch == null ? Double.POSITIVE_INFINITY : ClutchPhysics.torqueLimit(clutch); }
        double strength() { return Math.min(strengthOf(a) / Math.abs(ratio), strengthOf(b)); }
        KineticBlockEntity weakest() {
            double left = strengthOf(a) / Math.abs(ratio), right = strengthOf(b);
            return left < right ? a : right < left ? b
                : InertiaRegistry.getInertia(a) == 0 ? a : b;
        }
    }
    public static MemberState state(KineticBlockEntity be) { return ((IMemberDynamics) be).differentialcreate$member(); }
    private static void recordTorque(KineticBlockEntity be, KineticBlockEntity neighbour, double torque) {
        MemberState member = state(be);
        BlockPos contact = neighbour.getBlockPos();
        boolean input = contact.equals(be.source);
        boolean selectedInput = member.torqueContact != null && member.torqueContact.equals(be.source);
        // A shaft's two reaction torques have opposite signs. Use one defined port,
        // rather than whichever edge happens to be visited last. Without an inlet,
        // retain the strongest contact, breaking equal-magnitude ties by position.
        boolean stronger = Math.abs(torque) > Math.abs(member.transmittedTorque);
        boolean tie = Math.abs(torque) == Math.abs(member.transmittedTorque);
        if (member.torqueContact == null || input && !selectedInput
            || input == selectedInput && (stronger || tie && contact.compareTo(member.torqueContact) < 0)) {
            member.transmittedTorque = torque;
            member.torqueContact = contact;
        }
    }
    public static double strengthOf(KineticBlockEntity be) {
        return StrengthRegistry.of(be);
    }
    public static double kineticEnergy(KineticNetwork network) {
        double total = 0;
        for (var be : network.members.keySet()) {
            double speed = NetworkDynamics.localSpeed(be, network);
            total += .5 * InertiaRegistry.getInertia(be) * speed * speed;
        }
        return total;
    }
    public record ElasticEnergy(double total, Map<KineticBlockEntity, Double> local) {
        public double of(KineticBlockEntity be) { return local.getOrDefault(be, 0d); }
    }
    /** One snapshot counts each contact once and assigns its energy to its two elastic ports. */
    public static ElasticEnergy elasticEnergy(KineticNetwork network) {
        double total = 0;
        var local = new HashMap<KineticBlockEntity, Double>();
        for (var edge : ((INetworkDynamics) network).differentialcreate$data().connections) {
            double energy = edge.elasticEnergy();
            total += energy;
            // Equal local port stiffnesses store energy in proportion to their squared
            // local torques. The a:b share is q^2:1, independent of the reference side.
            double atB = energy / (1 + edge.ratio * edge.ratio);
            local.merge(edge.a, edge.ratio * edge.ratio * atB, Double::sum);
            local.merge(edge.b, atB, Double::sum);
        }
        return new ElasticEnergy(total, local);
    }
    /** New shafts inherit the assembly phase; friction surfaces retain independent phases. */
    public static void alignAngles(KineticNetwork network) {
        var data = ((INetworkDynamics) network).differentialcreate$data();
        var members = new ArrayList<>(network.members.keySet());
        members.sort(java.util.Comparator
            .comparing((KineticBlockEntity be) -> !be.getBlockPos().equals(data.anchor))
            .thenComparing(be -> !Double.isFinite(state(be).lastInertia))
            .thenComparing(be -> !be.isSource())
            .thenComparing(be -> InertiaRegistry.getInertia(be) == 0)
            .thenComparing(KineticBlockEntity::getBlockPos));
        var indices = new HashMap<KineticBlockEntity, Integer>();
        double[] angles = new double[members.size()];
        for (int i = 0; i < members.size(); i++) {
            indices.put(members.get(i), i);
            angles[i] = state(members.get(i)).angle;
        }
        var contacts = new ArrayList<ShaftPhases.Contact>();
        for (var edge : data.connections)
            if (edge.clutch == null)
                contacts.add(new ShaftPhases.Contact(indices.get(edge.a), indices.get(edge.b), edge.ratio, edge.twist));
        double[] aligned = ShaftPhases.align(angles, contacts);
        for (int i = 0; i < members.size(); i++) state(members.get(i)).angle = aligned[i];
    }
    public static void refresh(KineticNetwork network) {
        MachineActivity.refresh(network);
        var dynamics = (INetworkDynamics) network;
        dynamics.differentialcreate$data().balance = NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios());
        for (var be : network.members.keySet()) {
            MemberState member = state(be);
            double inertia = InertiaRegistry.getInertia(be);
            if (Double.isFinite(member.lastInertia) && inertia > member.lastInertia)
                member.physicalSpeed *= member.lastInertia / inertia;
            member.lastInertia = inertia;
        }
    }
    public static void publishReference(KineticNetwork network) {
        var dynamics = (INetworkDynamics) network;
        double momentum = 0, inertia = 0;
        for (var entry : dynamics.differentialcreate$getRatios().entrySet()) {
            double j = InertiaRegistry.getInertia(entry.getKey()), r = entry.getValue();
            momentum += j * r * state(entry.getKey()).physicalSpeed;
            inertia += j * r * r;
        }
        double speed = inertia > 0 ? momentum / inertia : dynamics.differentialcreate$getSpeed();
        if (inertia == 0 && !dynamics.differentialcreate$getRatios().isEmpty()) {
            var first = dynamics.differentialcreate$getRatios().entrySet().iterator().next();
            speed = state(first.getKey()).physicalSpeed / first.getValue();
        }
        dynamics.differentialcreate$data().publishingReference = true;
        try { dynamics.differentialcreate$setSpeed(speed); }
        finally { dynamics.differentialcreate$data().publishingReference = false; }
    }
    public interface Travel {
        double travel(KineticBlockEntity be);
        double signedTravel(KineticBlockEntity be);
    }
    public record WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection) {
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary) { this(shaft, boundary, 0); }
    }
    public static final class Trial implements Travel {
        public final List<KineticBlockEntity> members;
        public final List<Connection> connections;
        public final TorsionSolver.Result result;
        private final Map<KineticBlockEntity, Integer> indices;
        Trial(List<KineticBlockEntity> members, List<Connection> connections, Map<KineticBlockEntity, Integer> indices,
              TorsionSolver.Result result) {
            this.members = members; this.connections = connections; this.indices = indices; this.result = result;
        }
        public double travel(KineticBlockEntity be) {
            Integer i = indices.get(be);
            return i == null ? 0 : result.travel()[i] / RotationMath.TICK_MINUTES;
        }
        public double signedTravel(KineticBlockEntity be) {
            Integer i = indices.get(be);
            return i == null ? 0 : result.angle()[i] / RotationMath.TICK_MINUTES;
        }
        public void commit() {
            for (int i = 0; i < members.size(); i++) {
                MemberState state = state(members.get(i));
                state.physicalSpeed = result.speed()[i];
                state.angle += result.angle()[i];
                state.transmittedTorque = 0;
                state.torqueContact = null;
            }
            for (int e = 0; e < connections.size(); e++) {
                Connection edge = connections.get(e);
                edge.twist = result.twist()[e]; edge.torque = result.torque()[e]; edge.slipping = result.slipping()[e];
                edge.save();
                recordTorque(edge.a, edge.b, -edge.ratio * edge.torque);
                recordTorque(edge.b, edge.a, edge.torque);
            }
        }
        public void fracture() {
            if (result.broken() < 0) return;
            Connection edge = connections.get(result.broken());
            KineticBlockEntity broken = edge.weakest();
            // The stored spring energy of every removed contact becomes fracture loss.
            for (Connection contact : connections)
                if (contact.a == broken || contact.b == broken) state(contact.a).elasticConnections.remove(contact.b.getBlockPos());
            broken.getLevel().destroyBlock(broken.getBlockPos(), true);
            NetworkTopology.mark(edge.a); NetworkTopology.mark(edge.b);
        }
    }
    private record MotionTrial(Map<KineticBlockEntity, Integer> indices, TorsionSolver.Motion result) implements Travel {
        public double travel(KineticBlockEntity be) {
            Integer i = indices.get(be);
            return i == null ? 0 : result.travel()[i] / RotationMath.TICK_MINUTES;
        }
        public double signedTravel(KineticBlockEntity be) {
            Integer i = indices.get(be);
            return i == null ? 0 : result.angle()[i] / RotationMath.TICK_MINUTES;
        }
    }
    /** Fixed inputs for one event search. Capture again after a commit, fracture, or machine callback. */
    public static final class Snapshot {
        private final List<KineticBlockEntity> members;
        private final List<Connection> connections;
        private final Map<KineticBlockEntity, Integer> indices;
        private final TorsionSolver.Prepared solver;
        private Snapshot(List<KineticBlockEntity> members, List<Connection> connections,
                         Map<KineticBlockEntity, Integer> indices, List<TorsionSolver.Body> bodies,
                         List<TorsionSolver.Link> links, double[] speeds, double[] twists) {
            this.members = List.copyOf(members); this.connections = List.copyOf(connections);
            this.indices = Map.copyOf(indices);
            solver = TorsionSolver.prepare(bodies, links, speeds, twists);
        }
        public Trial sample(double minutes) {
            return new Trial(members, connections, indices, solver.advance(minutes, PhysicsTuning.TORSION_MAX_STEP));
        }
        public Travel motion(double minutes) {
            return new MotionTrial(indices, solver.motion(minutes, PhysicsTuning.TORSION_MAX_STEP));
        }
        public Trial advanceToEvent(double minutes, List<WorkEvent> events) {
            var limits = new ArrayList<TorsionSolver.EventLimit>(events.size());
            for (var event : events) {
                Integer shaft = indices.get(event.shaft());
                if (shaft == null) continue;
                var boundary = event.boundary();
                limits.add(new TorsionSolver.EventLimit(shaft, boundary.travel() * RotationMath.TICK_MINUTES,
                    boundary.positive() * RotationMath.TICK_MINUTES, boundary.negative() * RotationMath.TICK_MINUTES,
                    event.enteringDirection()));
            }
            var result = solver.advanceToEvent(minutes, PhysicsTuning.TORSION_MAX_STEP, limits);
            return new Trial(members, connections, indices, result);
        }
    }
    public static Trial sample(List<KineticNetwork> networks, double minutes) {
        return snapshot(networks).sample(minutes);
    }
    public static Snapshot snapshot(List<KineticNetwork> networks) {
        return snapshot(networks, Map.of());
    }
    public static Snapshot snapshot(List<KineticNetwork> networks, Map<KineticBlockEntity, Double> externalTorques) {
        List<KineticBlockEntity> members = new ArrayList<>();
        List<Connection> connections = new ArrayList<>();
        for (var network : networks) {
            members.addAll(network.members.keySet());
            connections.addAll(((INetworkDynamics) network).differentialcreate$data().connections);
        }
        members.removeIf(KineticBlockEntity::isRemoved);
        members.sort(java.util.Comparator.comparing(KineticBlockEntity::getBlockPos));
        Map<KineticBlockEntity, Integer> indices = new HashMap<>();
        List<TorsionSolver.Body> bodies = new ArrayList<>();
        double[] speeds = new double[members.size()];
        for (int i = 0; i < members.size(); i++) {
            var be = members.get(i);
            indices.put(be, i);
            speeds[i] = NetworkDynamics.preciseSpeed(be);
            var curve = SourcePhysics.curve(be);
            double load = ContraptionPhysics.manages(be) ? ContraptionPhysics.workTorque(be) : MachineActivity.impact(be);
            double drag = MachineActivity.quadraticDrag(be);
            if (be instanceof EncasedFanBlockEntity) load = 0;
            bodies.add(new TorsionSolver.Body(InertiaRegistry.getInertia(be),
                (be.isSource() ? curve.stallTorque() : 0) + externalTorques.getOrDefault(be, 0d),
                be.isSource() ? curve.damping() : 0, load, drag,
                be.isSource() ? curve.powerLimit() : Double.POSITIVE_INFINITY,
                be.isSource() ? curve.governorSpeed() : Double.POSITIVE_INFINITY, curve.governorRange()));
        }
        connections.removeIf(edge -> !indices.containsKey(edge.a) || !indices.containsKey(edge.b));
        List<TorsionSolver.Link> links = new ArrayList<>();
        double[] twists = new double[connections.size()];
        for (int i = 0; i < connections.size(); i++) {
            Connection edge = connections.get(i); twists[i] = edge.twist;
            links.add(new TorsionSolver.Link(indices.get(edge.a), indices.get(edge.b), edge.ratio,
                edge.stiffness(), edge.damping(), edge.strength(), edge.friction()));
        }
        return new Snapshot(members, connections, indices, bodies, links, speeds, twists);
    }
}
