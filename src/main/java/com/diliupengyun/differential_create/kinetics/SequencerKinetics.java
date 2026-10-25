package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.minecraft.core.Direction;

/** Two physical ports inside the sequencer, while Create retains one topology member. */
public final class SequencerKinetics {
    private SequencerKinetics() {}
    public static boolean outputFace(KineticBlockEntity be, KineticBlockEntity other) {
        if (!(be instanceof ISequencerDynamics)) return false;
        Direction face = Direction.getNearest(other.getBlockPos().getX() - be.getBlockPos().getX(),
            other.getBlockPos().getY() - be.getBlockPos().getY(), other.getBlockPos().getZ() - be.getBlockPos().getZ());
        Direction.Axis axis = ((IRotate) be.getBlockState().getBlock()).getRotationAxis(be.getBlockState());
        return face.getAxis() == axis && (be.hasSource() ? face != ((SequencedGearshiftBlockEntity) be).getSourceFacing()
            : face.getAxisDirection() == Direction.AxisDirection.POSITIVE);
    }
    public static KineticBlockEntity outputOwner(ElasticKinetics.Connection edge) {
        return outputFace(edge.a, edge.b) ? edge.a : outputFace(edge.b, edge.a) ? edge.b : null;
    }
    public static double portSpeed(KineticBlockEntity be, KineticBlockEntity other) {
        return outputFace(be, other) ? ((ISequencerDynamics) be).differentialcreate$motion().speed : NetworkDynamics.preciseSpeed(be);
    }
    public static boolean controlled(KineticBlockEntity be) {
        var visited = new java.util.HashSet<KineticBlockEntity>();
        while (be.getLevel() != null && be.source != null && visited.add(be)) {
            if (!(be.getLevel().getBlockEntity(be.source) instanceof KineticBlockEntity source)) return false;
            if (source instanceof ISequencerDynamics) return outputFace(source, be);
            be = source;
        }
        return false;
    }
    public static int portIndex(KineticBlockEntity be, KineticBlockEntity other,
                               Map<KineticBlockEntity, Integer> inputs, Map<KineticBlockEntity, Integer> outputs) {
        return outputFace(be, other) ? outputs.get(be) : inputs.get(be);
    }
    /** Identify the supplying side before a cold program acquires its direction.
     * Native nominal source trees can start at an idle flywheel. Port labels may
     * change only while both spindles are at rest and no motion target is armed. */
    public static void orientUnstarted(KineticNetwork network) {
        var edges = ((INetworkDynamics) network).differentialcreate$data().connections;
        var adjacent = new HashMap<KineticBlockEntity, List<KineticBlockEntity>>();
        for (var edge : edges) if (edge.friction() > 0) {
            adjacent.computeIfAbsent(edge.a, $ -> new java.util.ArrayList<>()).add(edge.b);
            adjacent.computeIfAbsent(edge.b, $ -> new java.util.ArrayList<>()).add(edge.a);
        }
        for (var be : network.members.keySet()) if (be instanceof ISequencerDynamics servo) {
            var m = servo.differentialcreate$motion();
            if (m.initialized && m.inputDirection != 0 || Math.abs(m.speed) > 1e-9 || Math.abs(NetworkDynamics.preciseSpeed(be)) > 1e-9) continue;
            KineticBlockEntity inlet = null; int best = 0; boolean tie = false;
            for (var neighbour : adjacent.getOrDefault(be, List.of())) {
                int score = supply(neighbour, be, adjacent);
                if (score > best) { best = score; inlet = neighbour; tie = false; }
                else if (score == best && score > 0) tie = true;
            }
            if (inlet == null || tie || inlet.getBlockPos().equals(be.source)) continue;
            var member = ElasticKinetics.state(be);
            double oldInputAngle = member.angle;
            member.angle = m.shaftAngle(); m.angle = oldInputAngle; m.phaseOffset = 0;
            m.start = m.target = m.angle;
            be.source = inlet.getBlockPos(); be.setChanged(); be.sendData();
        }
    }
    private static int supply(KineticBlockEntity first, KineticBlockEntity gearbox,
                              Map<KineticBlockEntity, List<KineticBlockEntity>> adjacent) {
        var visited = new java.util.HashSet<KineticBlockEntity>(); visited.add(gearbox);
        var queue = new ArrayDeque<KineticBlockEntity>(); queue.add(first); int score = 0;
        while (!queue.isEmpty()) {
            var be = queue.removeFirst(); if (!visited.add(be)) continue;
            if (be.isSource() && SourcePhysics.curve(be).stallTorque() != 0) return 2;
            if (be instanceof com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity) score = 1;
            if (!(be instanceof ISequencerDynamics)) queue.addAll(adjacent.getOrDefault(be, List.of()));
        }
        return score;
    }
    public static SequencerMotion.Command prepare(KineticBlockEntity be, List<ElasticKinetics.Connection> connections,
                                                  Map<KineticBlockEntity, Double> externalTorques) {
        var servo = (ISequencerDynamics) be;
        var m = servo.differentialcreate$motion();
        var ratios = new HashMap<KineticBlockEntity, Double>();
        var queue = new ArrayDeque<KineticBlockEntity>();
        for (var edge : connections) if (outputOwner(edge) == be) {
            var other = edge.a == be ? edge.b : edge.a;
            ratios.put(other, edge.a == be ? edge.ratio : 1 / edge.ratio);
            queue.add(other);
        }
        while (!queue.isEmpty()) {
            var from = queue.removeFirst();
            for (var edge : connections) {
                if (edge.a != from && edge.b != from || outputOwner(edge) != null || edge.friction() == 0) continue;
                var to = edge.a == from ? edge.b : edge.a;
                if (to == be || ratios.containsKey(to)) continue;
                ratios.put(to, ratios.get(from) * (edge.a == from ? edge.ratio : 1 / edge.ratio));
                queue.add(to);
            }
        }
        double inertia = SequencerMotion.OUTPUT_INERTIA, load = 0, drive = 0;
        double momentum = inertia * m.speed;
        double settling = Math.abs(m.speed);
        for (var entry : ratios.entrySet()) {
            var member = entry.getKey(); double r = entry.getValue();
            inertia += r * r * InertiaRegistry.getInertia(member);
            momentum += r * InertiaRegistry.getInertia(member) * NetworkDynamics.preciseSpeed(member);
            if (InertiaRegistry.getInertia(member) > 0) settling = Math.max(settling, Math.abs(NetworkDynamics.preciseSpeed(member) / r));
            double resistance = member instanceof IPumpDynamics pump ? Math.abs(pump.differentialcreate$torque())
                : ContraptionPhysics.manages(member) ? ContraptionPhysics.workTorque(member) : MachineActivity.impact(member);
            if (member instanceof EncasedFanBlockEntity) resistance = 0;
            load += Math.abs(r) * (resistance + MachineActivity.quadraticDrag(member) * Math.pow(NetworkDynamics.preciseSpeed(member), 2));
            drive += r * ((member instanceof IPumpDynamics ? 0 : externalTorques.getOrDefault(member, 0d))
                + (member.isSource() ? SourcePhysics.curve(member).torque(NetworkDynamics.preciseSpeed(member)) : 0));
        }
        return m.plan(servo.differentialcreate$program(), new SequencerMotion.Plant(inertia, load, drive, NetworkDynamics.preciseSpeed(be), momentum / inertia, settling), SequencerMotion.CONTROL_INTERVAL);
    }
    public static boolean needsControl(List<KineticNetwork> networks) {
        for (var n : networks) for (var be : n.members.keySet()) if (be instanceof ISequencerDynamics servo) {
            var m = servo.differentialcreate$motion(); var program = servo.differentialcreate$program();
            if (!m.parked() || m.inputDirection != 0 && m.index >= 0 && m.index < program.size() && program.get(m.index).moving()) return true;
        }
        return false;
    }
    public static void boundaries(List<KineticNetwork> networks, List<ElasticKinetics.WorkEvent> limits) {
        for (var n : networks) for (var be : n.members.keySet()) if (be instanceof ISequencerDynamics servo) {
            var m = servo.differentialcreate$motion();
            double distance = m.direction * (m.target - m.angle);
            if (m.index < 0 || m.command.exitSpeed() <= SequencerMotion.SPEED_EPSILON || distance <= SequencerMotion.POSITION_EPSILON) continue;
            double budget = distance / RotationMath.TICK_MINUTES;
            limits.add(new ElasticKinetics.WorkEvent(be, new ShaftWork.Boundary(Double.POSITIVE_INFINITY,
                m.direction > 0 ? budget : Double.POSITIVE_INFINITY, m.direction < 0 ? budget : Double.POSITIVE_INFINITY), 0, true));
        }
    }
    public static boolean finishReady(List<KineticNetwork> networks) {
        boolean advanced = false;
        for (var n : networks) for (var be : List.copyOf(n.members.keySet())) if (be instanceof ISequencerDynamics servo && !be.isRemoved()) {
            var m = servo.differentialcreate$motion(); var program = servo.differentialcreate$program();
            if (m.index >= 0 && m.index < program.size() && program.get(m.index).moving() && m.complete()) {
                MachineWork.event(servo::differentialcreate$finishMotion); advanced = true;
            }
        }
        return advanced;
    }
    public static void observe(List<KineticNetwork> networks, double duration, Map<KineticBlockEntity, Double> externalTorques) {
        for (var n : networks) for (var be : n.members.keySet()) if (be instanceof ISequencerDynamics servo && !be.isRemoved()) {
            prepare(be, ((INetworkDynamics) n).differentialcreate$data().connections, externalTorques);
            servo.differentialcreate$motion().observe(duration);
        }
    }
    public static void sync(List<KineticNetwork> networks) {
        for (var n : networks) for (var be : n.members.keySet()) if (be instanceof ISequencerDynamics) be.sendData();
    }
}
