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
    public static double strengthOf(KineticBlockEntity be) {
        return InertiaRegistry.getInertia(be) == 0 ? PhysicsTuning.TRANSMISSION_STRENGTH : PhysicsTuning.MACHINE_STRENGTH;
    }
    public static double kineticEnergy(KineticNetwork network) {
        double total = 0;
        for (var be : network.members.keySet()) {
            double speed = NetworkDynamics.localSpeed(be, network);
            total += .5 * InertiaRegistry.getInertia(be) * speed * speed;
        }
        return total;
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
    public static final class Trial {
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
        public void commit() {
            for (int i = 0; i < members.size(); i++) {
                MemberState state = state(members.get(i));
                state.physicalSpeed = result.speed()[i];
                state.angle += result.angle()[i];
                state.transmittedTorque = 0;
            }
            for (int e = 0; e < connections.size(); e++) {
                Connection edge = connections.get(e);
                edge.twist = result.twist()[e]; edge.torque = result.torque()[e]; edge.slipping = result.slipping()[e];
                edge.save();
                state(edge.a).transmittedTorque = Math.max(state(edge.a).transmittedTorque, Math.abs(edge.ratio * edge.torque));
                state(edge.b).transmittedTorque = Math.max(state(edge.b).transmittedTorque, Math.abs(edge.torque));
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
    public static Trial sample(List<KineticNetwork> networks, double minutes) {
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
            double load = MachineActivity.impact(be), drag = 0;
            if (be instanceof EncasedFanBlockEntity) { drag = load / Math.pow(PhysicsTuning.FAN_ANCHOR_RPM, 2); load = 0; }
            bodies.add(new TorsionSolver.Body(InertiaRegistry.getInertia(be), be.isSource() ? curve.stallTorque() : 0,
                be.isSource() ? curve.damping() : 0, load, drag));
        }
        connections.removeIf(edge -> !indices.containsKey(edge.a) || !indices.containsKey(edge.b));
        List<TorsionSolver.Link> links = new ArrayList<>();
        double[] twists = new double[connections.size()];
        for (int i = 0; i < connections.size(); i++) {
            Connection edge = connections.get(i); twists[i] = edge.twist;
            links.add(new TorsionSolver.Link(indices.get(edge.a), indices.get(edge.b), edge.ratio,
                edge.stiffness(), edge.damping(), edge.strength(), edge.friction()));
        }
        var result = TorsionSolver.advance(bodies, links, speeds, twists, minutes, PhysicsTuning.TORSION_MAX_STEP);
        return new Trial(members, connections, indices, result);
    }
}
