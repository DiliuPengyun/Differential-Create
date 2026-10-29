package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
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
        public double damping() { return 2 * PhysicsTuning.TORSIONAL_DAMPING / (1 + ratio * ratio)
            + (SequencerKinetics.outputOwner(this) == null ? 0 : SequencerMotion.OUTPUT_DAMPING); }
        public double friction() { return clutch == null ? Double.POSITIVE_INFINITY : ClutchPhysics.torqueLimit(clutch); }
        public double strength() { return Math.min(strengthOf(a) / Math.abs(ratio), strengthOf(b)); }
        public KineticBlockEntity weakest() {
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
            if (ActorFeed.enabled(be)) total += .5 * ActorFeed.of(be).inertia * Math.pow(ActorFeed.of(be).speed, 2);
            total += ClockworkMotion.kineticEnergy(be);
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
        for (var be : network.members.keySet()) if (ActorFeed.enabled(be)) {
            double energy = ActorFeed.of(be).elasticEnergy();
            total += energy; local.merge(be, energy, Double::sum);
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
            if (be.isRemoved() || be.getLevel() == null) continue;
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
            KineticBlockEntity be = entry.getKey();
            if (be.isRemoved() || be.getLevel() == null) continue;
            double j = InertiaRegistry.getInertia(be), r = entry.getValue();
            momentum += j * r * state(entry.getKey()).physicalSpeed;
            inertia += j * r * r;
            if (entry.getKey() instanceof ISequencerDynamics servo) {
                momentum += SequencerMotion.OUTPUT_INERTIA * r * servo.differentialcreate$motion().speed;
                inertia += SequencerMotion.OUTPUT_INERTIA * r * r;
            }
            if (ActorFeed.enabled(be)) {
                var feed = ActorFeed.of(be);
                momentum += feed.inertia * r * feed.speed;
                inertia += feed.inertia * r * r;
            }
        }
        double speed = inertia > 0 ? momentum / inertia : dynamics.differentialcreate$getSpeed();
        if (inertia == 0 && !dynamics.differentialcreate$getRatios().isEmpty()) {
            for (var first : dynamics.differentialcreate$getRatios().entrySet()) {
                KineticBlockEntity be = first.getKey();
                if (be.isRemoved() || be.getLevel() == null) continue;
                speed = state(be).physicalSpeed / first.getValue();
                break;
            }
        }
        dynamics.differentialcreate$data().publishingReference = true;
        try { dynamics.differentialcreate$setSpeed(speed); }
        finally { dynamics.differentialcreate$data().publishingReference = false; }
    }
    public interface Travel {
        double travel(KineticBlockEntity be);
        double signedTravel(KineticBlockEntity be);
    }
    public record WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection, boolean output, CrushingKinetics.Pair pair, ClockworkMotion.Hand hand) {
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection, boolean output, CrushingKinetics.Pair pair) { this(shaft,boundary,enteringDirection,output,pair,null); }
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection, boolean output) { this(shaft,boundary,enteringDirection,output,null); }
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary, int enteringDirection) { this(shaft, boundary, enteringDirection, false); }
        public WorkEvent(KineticBlockEntity shaft, ShaftWork.Boundary boundary) { this(shaft, boundary, 0, false); }
        public WorkEvent withDrive(ContraptionPhysics.Drive drive) {
            return drive != null && ClockworkMotion.manages(drive.shaft())
                ? new WorkEvent(shaft, boundary, enteringDirection, output, pair, ClockworkMotion.hand(drive)) : this;
        }
    }
    public static final class Trial implements Travel {
        public final List<KineticBlockEntity> members;
        public final List<Connection> connections;
        public final TorsionSolver.Result result;
        private final Map<KineticBlockEntity, Integer> indices;
        private final Map<KineticBlockEntity, Integer> outputs;
        private final Map<ClockworkMotion.Hand, Integer> hands;
        private final Map<KineticBlockEntity, Double> brakes;
        private final Map<CrushingKinetics.Pair, Integer> crushing;
        private final List<KineticNetwork> networks;
        Trial(List<KineticBlockEntity> members, List<Connection> connections, Map<KineticBlockEntity, Integer> indices,
              TorsionSolver.Result result, Map<KineticBlockEntity, Integer> outputs, Map<KineticBlockEntity, Double> brakes,
              Map<CrushingKinetics.Pair, Integer> crushing, List<KineticNetwork> networks, Map<ClockworkMotion.Hand, Integer> hands) {
            this.members = members; this.connections = connections; this.indices = indices; this.result = result; this.outputs = outputs; this.brakes = brakes; this.crushing = crushing; this.networks = networks;
            this.hands = hands;
        }
        /**
         * A network made only of inertialess transmission members has no physical
         * rotor to carry its overall speed: only the relative speeds are fixed.
         * The ODE leaves that common mode free, but the numerical solve can still
         * feed a small constraint residual into it. With no inertia to resist, that
         * residual accumulates as an ever-growing displayed speed. Preserve the
         * common mode explicitly across the step; relative motion, angles and
         * elastic torque still come from the solver unchanged.
         */
        private TorsionSolver.Result preserveFreeCommonMode(double[] previous) {
            double[] speed = result.speed().clone(), angle = result.angle().clone();
            boolean changed = false;
            for (KineticNetwork network : networks) {
                if (!network.sources.isEmpty()) continue;
                var dynamics = (INetworkDynamics) network;
                if (dynamics.differentialcreate$data().jammed) continue;
                if (Math.abs(dynamics.differentialcreate$getExternalTorque()) > 1e-9) continue;
                if (dynamics.differentialcreate$getRatios().isEmpty()) continue;
                boolean massless = true;
                for (KineticBlockEntity be : network.members.keySet()) {
                    if (be.isRemoved() || be.getLevel() == null) continue;
                    if (InertiaRegistry.getInertia(be) != 0 || be instanceof ISequencerDynamics || ActorFeed.enabled(be)
                        || MachineActivity.impact(be) > 0 || MachineActivity.quadraticDrag(be) > 0) {
                        massless = false; break;
                    }
                }
                if (!massless) continue;
                // A nominal spanning-tree ratio is not a free physical mode
                // across friction/CVT boundaries or an incompatible closed loop.
                boolean freeMode = true;
                for (var contact : dynamics.differentialcreate$data().connections) {
                    double a = dynamics.differentialcreate$getRatios().getOrDefault(contact.a, 0f);
                    double b = dynamics.differentialcreate$getRatios().getOrDefault(contact.b, 0f);
                    if (contact.clutch != null || contact.a instanceof ClutchBlockEntity || contact.b instanceof ClutchBlockEntity
                        || contact.a instanceof SpeedControllerBlockEntity || contact.b instanceof SpeedControllerBlockEntity
                        || b == 0 || Math.abs(contact.ratio * a / b - 1) > 1e-12) { freeMode = false; break; }
                }
                if (!freeMode) continue;
                Integer at = null;
                float ratio = 0;
                for (var entry : dynamics.differentialcreate$getRatios().entrySet()) {
                    Integer index = indices.get(entry.getKey());
                    if (index != null && entry.getValue() != 0) { at = index; ratio = entry.getValue(); break; }
                }
                if (at == null) continue;
                double drift = result.speed()[at] / ratio - previous[at] / ratio;
                if (!Double.isFinite(drift) || Math.abs(drift) < 1e-12) continue;
                for (var entry : dynamics.differentialcreate$getRatios().entrySet()) {
                    Integer index = indices.get(entry.getKey());
                    if (index == null) continue;
                    double memberRatio = entry.getValue();
                    speed[index] -= drift * memberRatio;
                    angle[index] -= drift * memberRatio * result.duration();
                }
                changed = true;
            }
            return changed ? new TorsionSolver.Result(result.duration(), speed, result.twist(), angle,
                result.travel(), result.torque(), result.slipping(), result.broken(), result.workTravel()) : result;
        }
        public double crushingTravel(CrushingKinetics.Pair pair) {
            Integer at = crushing.get(pair);
            return at == null ? 0 : result.workTravel()[at] / (2 * RotationMath.TICK_MINUTES);
        }
        public double crushingSignedTravel(CrushingKinetics.Pair pair) {
            return (signedTravel(pair.a()) - signedTravel(pair.b())) / 2;
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
            double[] previous = new double[members.size()];
            for (int i = 0; i < members.size(); i++) previous[i] = state(members.get(i)).physicalSpeed;
            TorsionSolver.Result applied = preserveFreeCommonMode(previous);
            for (int i = 0; i < members.size(); i++) {
                MemberState state = state(members.get(i));
                state.physicalSpeed = applied.speed()[i];
                state.angle += applied.angle()[i];
                state.transmittedTorque = 0;
                state.torqueContact = null;
            }
            for (var entry : outputs.entrySet()) {
                int at = entry.getValue();
                if (entry.getKey() instanceof ISequencerDynamics dynamics) {
                    var servo = dynamics.differentialcreate$motion();
                    servo.speed = applied.speed()[at]; servo.angle += applied.angle()[at];
                    servo.brakeHeat += brakes.get(entry.getKey()) * applied.travel()[at];
                } else {
                    var feed = ActorFeed.of(entry.getKey());
                    feed.speed = applied.speed()[at]; feed.angle += applied.angle()[at];
                    feed.brakeHeat += feed.brake * applied.travel()[at];
                    // Servo contacts precede the structural output contacts.
                    int feedIndex = at - members.size();
                    feed.twist = applied.twist()[connections.size() + feedIndex];
                }
                entry.getKey().setChanged();
            }
            hands.forEach((hand, at) -> {
                hand.speed = applied.speed()[at]; hand.angle += applied.angle()[at];
                hand.brakeHeat += hand.command.brake() * applied.travel()[at];
            });
            for (var be : members) if (ClockworkMotion.manages(be)) be.setChanged();
            for (int e = 0; e < connections.size(); e++) {
                Connection edge = connections.get(e);
                edge.twist = applied.twist()[e]; edge.torque = applied.torque()[e]; edge.slipping = applied.slipping()[e];
                edge.save();
                recordTorque(edge.a, edge.b, -edge.ratio * edge.torque);
                recordTorque(edge.b, edge.a, edge.torque);
            }
            crushing.forEach((pair, at) -> {
                double torque = applied.torque()[at];
                ((ICrushingDynamics) pair.controller()).differentialcreate$reaction(torque);
                recordTorque(pair.a(), pair.b(), -torque);
                recordTorque(pair.b(), pair.a(), torque);
            });
        }
        public double outputTravel(KineticBlockEntity be) {
            Integer i = outputs.get(be);
            return i == null ? 0 : result.angle()[i] / RotationMath.TICK_MINUTES;
        }
        public double outputAbsTravel(KineticBlockEntity be) {
            Integer i = outputs.get(be);
            return i == null ? 0 : result.travel()[i] / RotationMath.TICK_MINUTES;
        }
        public double structureTravel(ContraptionPhysics.Drive drive) {
            if (!ClockworkMotion.manages(drive.shaft())) return ActorFeed.enabled(drive.shaft())
                ? outputTravel(drive.shaft()) : signedTravel(drive.shaft());
            Integer at = hands.get(ClockworkMotion.hand(drive));
            return at == null ? 0 : result.angle()[at] / RotationMath.TICK_MINUTES;
        }
        public double structureAbsTravel(ContraptionPhysics.Drive drive) {
            if (!ClockworkMotion.manages(drive.shaft())) return ActorFeed.enabled(drive.shaft())
                ? outputAbsTravel(drive.shaft()) : travel(drive.shaft());
            Integer at = hands.get(ClockworkMotion.hand(drive));
            return at == null ? 0 : result.travel()[at] / RotationMath.TICK_MINUTES;
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
        private final Map<ClockworkMotion.Hand, Integer> hands;
        private final Map<KineticBlockEntity, Double> brakes;
        private final TorsionSolver.Prepared solver;
        private final double[] sourceDrives;
        private final Map<CrushingKinetics.Pair, Integer> crushing;
        private final List<KineticNetwork> networks;
        private Snapshot(List<KineticBlockEntity> members, List<Connection> connections,
                         Map<KineticBlockEntity, Integer> indices, Map<KineticBlockEntity, Integer> outputs, List<TorsionSolver.Body> bodies,
                         List<TorsionSolver.Link> links, double[] speeds, double[] twists, double[] sourceDrives,
                         Map<CrushingKinetics.Pair, Integer> crushing, List<KineticNetwork> networks, Map<ClockworkMotion.Hand, Integer> hands) {
            this.members = List.copyOf(members); this.connections = List.copyOf(connections);
            this.indices = Map.copyOf(indices);
            this.outputs = Map.copyOf(outputs);
            this.hands = Map.copyOf(hands);
            var ratings = new HashMap<KineticBlockEntity, Double>();
            outputs.forEach((be, at) -> ratings.put(be, bodies.get(at).load()));
            this.brakes = Map.copyOf(ratings);
            this.sourceDrives = sourceDrives.clone();
            this.crushing = Map.copyOf(crushing);
            this.networks = List.copyOf(networks);
            solver = TorsionSolver.prepare(bodies, links, speeds, twists);
        }
        /** Pump resistance is already captured in the bodies' passive loads. */
        public void setExternalTorques(Map<KineticBlockEntity, Double> torques) {
            double[] drives = sourceDrives.clone();
            for (var entry : torques.entrySet()) {
                Integer at = indices.get(entry.getKey());
                if (at != null && !(entry.getKey() instanceof IPumpDynamics)) drives[at] += entry.getValue();
            }
            solver.updateDrives(drives);
        }
        public Trial sample(double minutes) {
            return new Trial(members, connections, indices, solver.advance(minutes, PhysicsTuning.TORSION_MAX_STEP), outputs, brakes, crushing, networks, hands);
        }
        public Travel motion(double minutes) {
            return new MotionTrial(indices, solver.motion(minutes, PhysicsTuning.TORSION_MAX_STEP));
        }
        public Trial advanceToEvent(double minutes, List<WorkEvent> events) {
            var limits = new ArrayList<TorsionSolver.EventLimit>(events.size());
            for (var event : events) {
                if (event.pair() != null) {
                    var pair = event.pair(); Integer link = crushing.get(pair);
                    if (link != null) limits.add(TorsionSolver.EventLimit.work(indices.get(pair.a()), indices.get(pair.b()), link,
                        event.boundary().travel() * RotationMath.TICK_MINUTES));
                    continue;
                }
                Integer shaft = event.hand() != null ? hands.get(event.hand()) : (event.output() ? outputs : indices).get(event.shaft());
                if (shaft == null) continue;
                var boundary = event.boundary();
                limits.add(new TorsionSolver.EventLimit(shaft, boundary.travel() * RotationMath.TICK_MINUTES,
                    boundary.positive() * RotationMath.TICK_MINUTES, boundary.negative() * RotationMath.TICK_MINUTES,
                    event.enteringDirection()));
            }
            var result = solver.advanceToEvent(minutes, PhysicsTuning.TORSION_MAX_STEP, limits);
            return new Trial(members, connections, indices, result, outputs, brakes, crushing, networks, hands);
        }
    }
    public static Trial sample(List<KineticNetwork> networks, double minutes) {
        return snapshot(networks).sample(minutes);
    }
    public static Snapshot snapshot(List<KineticNetwork> networks) {
        return snapshot(networks, Map.of());
    }
    private static boolean currentMember(KineticBlockEntity be) {
        var level = be.getLevel();
        if (be.isRemoved() || level == null) return false;
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            var pos = be.getBlockPos();
            var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk != null && chunk.getBlockEntity(pos) == be;
        }
        return level.getBlockEntity(be.getBlockPos()) == be;
    }
    /** Immutable indexing and passive connections; forces and motion are captured
     * afresh below. Its owner expires at world events and at the end of the tick. */
    static final class Layout {
        final List<KineticNetwork> networks;
        final List<KineticBlockEntity> members, servos, feeds;
        final List<Connection> connections;
        final List<ClockworkMotion.Output> clockOutputs;
        final Map<KineticBlockEntity, Integer> indices, outputs;
        final Map<ClockworkMotion.Hand, Integer> hands;
        final List<TorsionSolver.Link> links;

        Layout(List<KineticNetwork> networks) {
            this.networks = List.copyOf(networks);
            var members = new ArrayList<KineticBlockEntity>();
            var connections = new ArrayList<Connection>();
            for (var network : networks) {
                // Preserve both velocity and pose while saved child entities are still
                // loading. A partial mass/centre capture cannot represent this network.
                if (network.members.keySet().stream().anyMatch(ContraptionPhysics::incomplete)) continue;
                members.addAll(network.members.keySet());
                connections.addAll(((INetworkDynamics) network).differentialcreate$data().connections);
            }
            members.removeIf(be -> !currentMember(be));
            members.sort(java.util.Comparator.comparing(KineticBlockEntity::getBlockPos));
            this.members = List.copyOf(members);
            var indices = new HashMap<KineticBlockEntity, Integer>();
            for (int i = 0; i < members.size(); i++) indices.put(members.get(i), i);
            this.indices = Map.copyOf(indices);
            servos = members.stream().filter(ISequencerDynamics.class::isInstance).toList();
            feeds = members.stream().filter(ActorFeed::enabled).toList();
            clockOutputs = members.stream().flatMap(be -> ClockworkMotion.outputs(be).stream()).toList();
            var outputs = new HashMap<KineticBlockEntity, Integer>();
            int at = members.size();
            for (var be : servos) outputs.put(be, at++);
            for (var be : feeds) outputs.put(be, at++);
            this.outputs = Map.copyOf(outputs);
            var hands = new HashMap<ClockworkMotion.Hand, Integer>();
            for (var output : clockOutputs) hands.put(output.hand(), at++);
            this.hands = Map.copyOf(hands);
            connections.removeIf(edge -> !indices.containsKey(edge.a) || !indices.containsKey(edge.b));
            this.connections = List.copyOf(connections);
            var links = new ArrayList<TorsionSolver.Link>();
            for (var edge : connections)
                links.add(new TorsionSolver.Link(SequencerKinetics.portIndex(edge.a, edge.b, indices, outputs),
                    SequencerKinetics.portIndex(edge.b, edge.a, indices, outputs), edge.ratio,
                    edge.stiffness(), edge.damping(), edge.strength(), edge.friction()));
            this.links = List.copyOf(links);
        }
    }
    private static Layout layout(List<KineticNetwork> networks) {
        var cache = KineticReadCache.current();
        if (cache != null && cache.layout != null && networks.equals(cache.layout.networks)) return cache.layout;
        var layout = new Layout(networks);
        if (cache != null) cache.layout = layout;
        return layout;
    }
    public static Snapshot snapshot(List<KineticNetwork> networks, Map<KineticBlockEntity, Double> externalTorques) {
        var layout = layout(networks);
        var members = layout.members; var connections = layout.connections;
        var indices = layout.indices; var outputs = layout.outputs; var hands = layout.hands;
        var servos = layout.servos; var feeds = layout.feeds; var clockOutputs = layout.clockOutputs;
        List<TorsionSolver.Body> bodies = new ArrayList<>();
        feeds.forEach(ActorFeed::prepare);
        double[] speeds = new double[members.size() + servos.size() + feeds.size() + clockOutputs.size()], sourceDrives = new double[speeds.length];
        for (int i = 0; i < members.size(); i++) {
            var be = members.get(i);
            speeds[i] = NetworkDynamics.preciseSpeed(be);
            var curve = SourcePhysics.curve(be);
            double load = be instanceof IPumpDynamics pump ? Math.abs(pump.differentialcreate$torque())
                : ContraptionPhysics.manages(be) ? ContraptionPhysics.workTorque(be) : MachineActivity.impact(be);
            double drag = MachineActivity.quadraticDrag(be);
            if (ActorFeed.enabled(be) || ClockworkMotion.manages(be)) drag = 0;
            if (be instanceof EncasedFanBlockEntity) load = 0;
            if (be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity) load = 0;
            sourceDrives[i] = be.isSource() ? curve.stallTorque() : 0;
            bodies.add(new TorsionSolver.Body(InertiaRegistry.getInertia(be),
                sourceDrives[i] + (be instanceof IPumpDynamics ? 0 : externalTorques.getOrDefault(be, 0d)),
                be.isSource() ? curve.damping() : 0, load, drag,
                be.isSource() ? curve.powerLimit() : Double.POSITIVE_INFINITY,
                be.isSource() ? curve.governorSpeed() : Double.POSITIVE_INFINITY, curve.governorRange())
                .withPotential(ActorFeed.enabled(be) || ClockworkMotion.manages(be) ? ShaftPotential.NONE : ContraptionGravity.capture(be))
                .withStop(ActorFeed.enabled(be) ? 0 : ContraptionMotion.stop(be)));
        }
        for (var be : servos) {
            int at = bodies.size();
            var motion = ((ISequencerDynamics) be).differentialcreate$motion();
            var command = SequencerKinetics.prepare(be, connections, externalTorques);
            speeds[at] = motion.speed;
            bodies.add(new TorsionSolver.Body(SequencerMotion.OUTPUT_INERTIA, 0, 0, command.brake(), 0));
        }
        for (var be : feeds) {
            int at = bodies.size();
            var feed = ActorFeed.of(be); speeds[at] = feed.speed;
            bodies.add(new TorsionSolver.Body(feed.inertia, 0, 0,
                feed.brake + ContraptionPhysics.feedLoad(be), ContraptionPhysics.airDrag(be))
                .withPotential(ContraptionGravity.capture(be)).withStop(ContraptionMotion.stop(be))
                .withSpeedLimit(ContraptionMotion.speedLimit(be)));
        }
        for (var output : clockOutputs) {
            var drive = output.drive(); var hand = output.hand();
            int at = bodies.size(); speeds[at] = hand.speed;
            var gravity = ContraptionGravity.capture(drive);
            double load = ContraptionPhysics.feedLoad(drive);
            double drag = ContraptionPhysics.drives(drive.shaft()).stream().filter(d -> d.root() == drive.root())
                .mapToDouble(ContraptionPhysics.Drive::drag).sum();
            ClockworkMotion.prepare(output, gravity, load);
            bodies.add(new TorsionSolver.Body(hand.inertia, 0, 0, hand.command.brake() + load, drag)
                .withPotential(gravity).withStop(drive.root().isStalled() || drive.root().getContraption().stalled ? 2 : 0));
        }
        List<TorsionSolver.Link> links = new ArrayList<>(layout.links);
        double[] twists = new double[connections.size()];
        for (int i = 0; i < connections.size(); i++) {
            Connection edge = connections.get(i); twists[i] = edge.twist;
            // Redstone can change while a work counter advances, without changing
            // graph membership. Always read the live friction limit of a clutch.
            if (edge.clutch != null) {
                var link = links.get(i);
                links.set(i, new TorsionSolver.Link(link.a(), link.b(), link.ratio(),
                    link.stiffness(), link.damping(), link.strength(), edge.friction()));
            }
        }
        double[] allTwists = java.util.Arrays.copyOf(twists, twists.length + servos.size() + feeds.size() + clockOutputs.size());
        for (var be : servos) {
            var command = ((ISequencerDynamics) be).differentialcreate$motion().command;
            links.add(new TorsionSolver.Link(indices.get(be), outputs.get(be), command.gear(),
                0, SequencerMotion.COUPLING_DAMPING, Double.POSITIVE_INFINITY, command.coupling()));
        }
        for (var be : feeds) {
            var feed = ActorFeed.of(be);
            allTwists[links.size()] = feed.twist;
            links.add(new TorsionSolver.Link(indices.get(be), outputs.get(be), feed.gear,
                feed.stiffness(), feed.damping(),
                Double.POSITIVE_INFINITY, feed.coupling));
        }
        for (var output : clockOutputs) {
            var command = output.hand().command;
            links.add(new TorsionSolver.Link(indices.get(output.drive().shaft()), hands.get(output.hand()), command.gear(),
                0, PositionServo.DAMPING, Double.POSITIVE_INFINITY, command.coupling()));
        }
        var crushing = new HashMap<CrushingKinetics.Pair, Integer>();
        for (var pair : CrushingKinetics.pairs(networks)) if (pair.active() && indices.containsKey(pair.a()) && indices.containsKey(pair.b())) {
            crushing.put(pair, links.size());
            links.add(TorsionSolver.Link.work(indices.get(pair.a()), indices.get(pair.b()), pair.torque()));
        }
        allTwists = java.util.Arrays.copyOf(allTwists, links.size());
        return new Snapshot(members, connections, indices, outputs, bodies, links, speeds, allTwists, sourceDrives, crushing,
            new ArrayList<>(networks), hands);
    }
}
