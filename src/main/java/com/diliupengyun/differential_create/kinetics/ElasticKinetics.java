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
        double damping() { return 2 * PhysicsTuning.TORSIONAL_DAMPING / (1 + ratio * ratio)
            + (SequencerKinetics.outputOwner(this) == null ? 0 : SequencerMotion.OUTPUT_DAMPING); }
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
            if (be instanceof ISequencerDynamics servo) total += .5 * SequencerMotion.OUTPUT_INERTIA * Math.pow(servo.differentialcreate$motion().speed, 2);
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
        var outputs = new HashMap<KineticBlockEntity, Integer>();
        var servos = members.stream().filter(ISequencerDynamics.class::isInstance).toList();
        // Output spindles anchor their own physical assembly, independently of
        // the input shaft. Only the passive external contacts transmit phase.
        double[] angles = new double[servos.size() + members.size()];
        for (int i = 0; i < servos.size(); i++) {
            var be = servos.get(i);
            outputs.put(be, i);
            angles[i] = ((ISequencerDynamics) be).differentialcreate$motion().shaftAngle();
        }
        for (int i = 0; i < members.size(); i++) {
            int at = servos.size() + i;
            indices.put(members.get(i), at);
            angles[at] = state(members.get(i)).angle;
        }
        var contacts = new ArrayList<ShaftPhases.Contact>();
        for (var edge : data.connections)
            if (edge.clutch == null)
                contacts.add(new ShaftPhases.Contact(SequencerKinetics.portIndex(edge.a, edge.b, indices, outputs),
                    SequencerKinetics.portIndex(edge.b, edge.a, indices, outputs), edge.ratio, edge.twist));
        double[] aligned = ShaftPhases.align(angles, contacts);
        outputs.forEach((be, at) -> {
            if (aligned[at] == angles[at]) return;
            var m = ((ISequencerDynamics) be).differentialcreate$motion();
            // Two outputs may meet. Fit the assembly zero, never the coordinate
            // used for progress, planned boundaries or position feedback.
            m.phaseOffset = aligned[at] - m.angle;
            be.setChanged(); be.sendData();
        });
        indices.forEach((be, at) -> state(be).angle = aligned[at]);
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
            if (entry.getKey() instanceof ISequencerDynamics servo) {
                momentum += SequencerMotion.OUTPUT_INERTIA * r * servo.differentialcreate$motion().speed;
                inertia += SequencerMotion.OUTPUT_INERTIA * r * r;
            }
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
    public record WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection, boolean output) {
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection) { this(shaft, boundary, enteringDirection, false); }
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary) { this(shaft, boundary, 0, false); }
    }
    public static final class Trial implements Travel {
        public final List<KineticBlockEntity> members;
        public final List<Connection> connections;
        public final TorsionSolver.Result result;
        private final Map<KineticBlockEntity, Integer> indices;
        private final Map<KineticBlockEntity, Integer> outputs;
        private final Map<KineticBlockEntity, Double> brakes;
        Trial(List<KineticBlockEntity> members, List<Connection> connections, Map<KineticBlockEntity, Integer> indices,
              TorsionSolver.Result result, Map<KineticBlockEntity, Integer> outputs, Map<KineticBlockEntity, Double> brakes) {
            this.members = members; this.connections = connections; this.indices = indices; this.result = result; this.outputs = outputs; this.brakes = brakes;
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
            for (var entry : outputs.entrySet()) {
                var servo = ((ISequencerDynamics) entry.getKey()).differentialcreate$motion();
                int at = entry.getValue();
                servo.speed = result.speed()[at];
                servo.angle += result.angle()[at];
                servo.brakeHeat += brakes.get(entry.getKey()) * result.travel()[at];
                entry.getKey().setChanged();
            }
            for (int e = 0; e < connections.size(); e++) {
                Connection edge = connections.get(e);
                edge.twist = result.twist()[e]; edge.torque = result.torque()[e]; edge.slipping = result.slipping()[e];
                edge.save();
                recordTorque(edge.a, edge.b, -edge.ratio * edge.torque);
                recordTorque(edge.b, edge.a, edge.torque);
            }
        }
        public double outputTravel(KineticBlockEntity be) {
            Integer i = outputs.get(be);
            return i == null ? 0 : result.angle()[i] / RotationMath.TICK_MINUTES;
        }
        public void fracture() {
            if (result.broken() < 0 || result.broken() >= connections.size()) return;
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
    /** Captured world inputs for one event search or detached coupling solve.
     * Capture again after a commit, fracture, or machine callback. */
    public static final class Snapshot {
        private final List<KineticBlockEntity> members;
        private final List<Connection> connections;
        private final Map<KineticBlockEntity, Integer> indices;
        private final Map<KineticBlockEntity, Integer> outputs;
        private final Map<KineticBlockEntity, Double> brakes;
        private final TorsionSolver.Prepared solver;
        private final double[] sourceDrives;
        private Snapshot(List<KineticBlockEntity> members, List<Connection> connections,
                         Map<KineticBlockEntity, Integer> indices, Map<KineticBlockEntity, Integer> outputs, List<TorsionSolver.Body> bodies,
                         List<TorsionSolver.Link> links, double[] speeds, double[] twists, double[] sourceDrives) {
            this.members = List.copyOf(members); this.connections = List.copyOf(connections);
            this.indices = Map.copyOf(indices);
            this.outputs = Map.copyOf(outputs);
            var ratings = new HashMap<KineticBlockEntity, Double>();
            outputs.forEach((be, at) -> ratings.put(be, bodies.get(at).load()));
            this.brakes = Map.copyOf(ratings);
            this.sourceDrives = sourceDrives.clone();
            solver = TorsionSolver.prepare(bodies, links, speeds, twists);
        }
        /** No world state changes between these pump-load trials. */
        public void setExternalTorques(Map<KineticBlockEntity, Double> torques) {
            double[] drives = sourceDrives.clone();
            for (var entry : torques.entrySet()) {
                Integer at = indices.get(entry.getKey());
                if (at != null) drives[at] += entry.getValue();
            }
            solver.updateDrives(drives);
        }
        public Trial sample(double minutes) {
            return new Trial(members, connections, indices, solver.advance(minutes, PhysicsTuning.TORSION_MAX_STEP), outputs, brakes);
        }
        public Travel motion(double minutes) {
            return new MotionTrial(indices, solver.motion(minutes, PhysicsTuning.TORSION_MAX_STEP));
        }
        public Trial advanceToEvent(double minutes, List<WorkEvent> events) {
            var limits = new ArrayList<TorsionSolver.EventLimit>(events.size());
            for (var event : events) {
                Integer shaft = (event.output() ? outputs : indices).get(event.shaft());
                if (shaft == null) continue;
                var boundary = event.boundary();
                limits.add(new TorsionSolver.EventLimit(shaft, boundary.travel() * RotationMath.TICK_MINUTES,
                    boundary.positive() * RotationMath.TICK_MINUTES, boundary.negative() * RotationMath.TICK_MINUTES,
                    event.enteringDirection()));
            }
            var result = solver.advanceToEvent(minutes, PhysicsTuning.TORSION_MAX_STEP, limits);
            return new Trial(members, connections, indices, result, outputs, brakes);
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
        var servos = members.stream().filter(ISequencerDynamics.class::isInstance).toList();
        var outputs = new HashMap<KineticBlockEntity, Integer>();
        double[] speeds = new double[members.size() + servos.size()], sourceDrives = new double[speeds.length];
        for (int i = 0; i < members.size(); i++) {
            var be = members.get(i);
            indices.put(be, i);
            speeds[i] = NetworkDynamics.preciseSpeed(be);
            var curve = SourcePhysics.curve(be);
            double load = ContraptionPhysics.manages(be) ? ContraptionPhysics.workTorque(be) : MachineActivity.impact(be);
            double drag = MachineActivity.quadraticDrag(be);
            if (be instanceof EncasedFanBlockEntity) load = 0;
            sourceDrives[i] = be.isSource() ? curve.stallTorque() : 0;
            bodies.add(new TorsionSolver.Body(InertiaRegistry.getInertia(be),
                sourceDrives[i] + externalTorques.getOrDefault(be, 0d),
                be.isSource() ? curve.damping() : 0, load, drag,
                be.isSource() ? curve.powerLimit() : Double.POSITIVE_INFINITY,
                be.isSource() ? curve.governorSpeed() : Double.POSITIVE_INFINITY, curve.governorRange()));
        }
        for (var be : servos) {
            int at = bodies.size();
            outputs.put(be, at);
            var motion = ((ISequencerDynamics) be).differentialcreate$motion();
            var command = SequencerKinetics.prepare(be, connections, externalTorques);
            speeds[at] = motion.speed;
            bodies.add(new TorsionSolver.Body(SequencerMotion.OUTPUT_INERTIA, 0, 0, command.brake(), 0));
        }
        connections.removeIf(edge -> !indices.containsKey(edge.a) || !indices.containsKey(edge.b));
        List<TorsionSolver.Link> links = new ArrayList<>();
        double[] twists = new double[connections.size()];
        for (int i = 0; i < connections.size(); i++) {
            Connection edge = connections.get(i); twists[i] = edge.twist;
            links.add(new TorsionSolver.Link(SequencerKinetics.portIndex(edge.a, edge.b, indices, outputs),
                SequencerKinetics.portIndex(edge.b, edge.a, indices, outputs), edge.ratio,
                edge.stiffness(), edge.damping(), edge.strength(), edge.friction()));
        }
        double[] allTwists = java.util.Arrays.copyOf(twists, twists.length + servos.size());
        for (var be : servos) {
            var command = ((ISequencerDynamics) be).differentialcreate$motion().command;
            links.add(new TorsionSolver.Link(indices.get(be), outputs.get(be), command.gear(),
                0, SequencerMotion.COUPLING_DAMPING, Double.POSITIVE_INFINITY, command.coupling()));
        }
        return new Snapshot(members, connections, indices, outputs, bodies, links, speeds, allTwists, sourceDrives);
    }
}
