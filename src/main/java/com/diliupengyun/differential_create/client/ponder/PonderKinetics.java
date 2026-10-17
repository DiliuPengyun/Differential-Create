package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.IClutchDynamics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.diliupengyun.differential_create.kinetics.RotationMath;
import com.diliupengyun.differential_create.kinetics.ShaftPhases;
import com.diliupengyun.differential_create.kinetics.SequencerKinetics;
import com.diliupengyun.differential_create.kinetics.SequencerMotion;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.StrengthRegistry;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/**
 * A local torque boundary replaces Ponder's prescribed RPM. Geometry, inertia,
 * elastic contacts, clutch friction and integration use the production laws.
 * This state never joins a server network or the live world's packet caches.
 */
public final class PonderKinetics {
    private static final class Rotor {
        final KineticBlockEntity be;
        float target;
        boolean fed, boundary, active, steamBoiler;
        long commandRevision, appliedRevision = -1;
        double previous, angle, speed;
        double outputPrevious, outputAngle, outputSpeed;
        double visualOffset, outputVisualOffset;
        Attachment attachment;
        SourcePhysics.Curve supply;
        Rotor(KineticBlockEntity be) {
            this.be = be;
            target = be.getTheoreticalSpeed();
            be.setSpeed(0);
            ElasticKinetics.state(be).physicalSpeed = 0;
        }
        boolean physicalSource() {
            // Ordinary bearings inherit GeneratingKineticBlockEntity but cannot supply torque.
            return be instanceof GeneratingKineticBlockEntity
                && (!(be instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)
                    || be instanceof com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity);
        }
        boolean source() { return supply != null || physicalSource() || boundary; }
    }

    private static final class Attachment {
        double inertia, load, drag, speed, previous, angle, twist;
    }

    private record Contact(KineticBlockEntity a, KineticBlockEntity b) {}

    private final Map<BlockPos, Rotor> rotors = new LinkedHashMap<>();
    private final Map<Contact, Double> contactTwists = new HashMap<>();
    private final Set<BlockPos> processingFans = new HashSet<>();
    private boolean explicitSources;
    private long commandRevision;

    public static PonderKinetics of(PonderLevel level) {
        return ((IPonderKinetics) level).differentialcreate$kinetics();
    }

    public void reset() { rotors.clear(); contactTwists.clear(); processingFans.clear(); explicitSources = false; commandRevision = 0; }
    public void useExplicitSources() { explicitSources = true; }
    public void enableFanProcessing(BlockPos pos) { processingFans.add(pos.immutable()); }
    public boolean processesFan(BlockPos pos) { return processingFans.contains(pos); }

    private Rotor rotor(KineticBlockEntity be) {
        Rotor old = rotors.get(be.getBlockPos());
        if (old == null || old.be != be) {
            old = new Rotor(be);
            rotors.put(be.getBlockPos().immutable(), old);
        }
        return old;
    }

    /** Tutorial RPM instructions change a source target, never a rotor's velocity. */
    public void command(PonderLevel level, Selection selection, UnaryOperator<Float> operation) {
        long revision = ++commandRevision;
        selection.forEach(pos -> {
            if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) {
                Rotor rotor = rotor(be);
                rotor.target = operation.apply(rotor.target);
                rotor.commandRevision = revision;
            }
        });
    }

    /** Explicit off-stage drive for diagrams that show a machine without its source. */
    public void boundary(PonderLevel level, BlockPos pos, float target) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) {
            Rotor rotor = rotor(be);
            rotor.boundary = true;
            rotor.target = target;
            rotor.commandRevision = ++commandRevision;
        }
    }

    /** Declared boiler supply; its nonlinear cylinder/governor curve is shared with gameplay. */
    public void supply(PonderLevel level, BlockPos pos, SourcePhysics.Curve curve) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) rotor(be).supply = curve;
    }

    /** The complete boiler lesson uses the same live boiler state as its appearance and particles. */
    public void useSteamBoiler(PonderLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity be)
            rotor(be).steamBoiler = true;
    }

    /** Additional mounted mass/resistance, in the production solver's units. */
    public void attachment(PonderLevel level, BlockPos pos, double inertia, double load, double drag) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) {
            Rotor rotor = rotor(be);
            if (rotor.attachment == null) rotor.attachment = new Attachment();
            rotor.attachment.inertia = inertia; rotor.attachment.load = load; rotor.attachment.drag = drag;
        }
    }

    public void setAttachmentLoad(BlockPos pos, double load) {
        var rotor = rotors.get(pos);
        if (rotor != null && rotor.attachment != null) rotor.attachment.load = Math.max(0, load);
    }

    /** A tutorial pulse arms the same program and passive position controller as gameplay. */
    public void sequence(PonderLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be && be instanceof ISequencerDynamics servo)
            servo.differentialcreate$motion().begin(servo.differentialcreate$program(), 0, be.getSpeed(), false);
    }

    public void tick(PonderLevel level) {
        Set<BlockPos> present = new HashSet<>();
        for (var blockEntity : level.getBlockEntities())
            if (blockEntity instanceof KineticBlockEntity be && !be.isRemoved()) {
                be.setBlockState(level.getBlockState(be.getBlockPos()));
                Rotor rotor = rotor(be);
                // Native scene edits reload partial NBT and clear server-only member fields.
                // The preview owns its physical state independently of that serialization.
                be.setSpeed((float) rotor.speed);
                ElasticKinetics.state(be).physicalSpeed = rotor.speed;
                present.add(be.getBlockPos());
            }
        rotors.keySet().retainAll(present);
        if (rotors.isEmpty()) return;
        var display = !explicitSources && level.scene != null ? new PonderDisplayTopology(level) : null;
        var members = new ArrayList<>(rotors.values().stream().filter(r -> display == null || display.contains(r.be)).toList());
        for (var rotor : rotors.values()) if (display != null && !display.contains(rotor.be)) rotor.active = false;
        for (var rotor : members) if (display != null && !rotor.active) {
            rotor.speed = 0;
            rotor.previous = rotor.angle = rotor.visualOffset = 0;
        }
        // A newly revealed component takes the assembled phase before its first rendered tick.
        // Existing components remain the phase anchors when a new source is installed.
        members.sort(Comparator.comparing((Rotor r) -> !r.active).thenComparing(r -> !r.source()).thenComparing(r -> r.be.getBlockPos()));
        if (members.isEmpty()) return;
        var indices = new HashMap<KineticBlockEntity, Integer>();
        for (int i = 0; i < members.size(); i++) indices.put(members.get(i).be, i);

        // Establish gearbox and split-shaft inlet faces from the drive before querying ratios.
        var visited = new HashSet<KineticBlockEntity>();
        for (var root : members) {
            if (!visited.add(root.be)) continue;
            var component = new ArrayList<Rotor>();
            var ratios = new HashMap<KineticBlockEntity, Double>();
            ratios.put(root.be, 1d);
            var queue = new ArrayDeque<KineticBlockEntity>();
            queue.add(root.be);
            while (!queue.isEmpty()) {
                var from = queue.removeFirst();
                component.add(rotor(from));
                for (var to : neighbours(level, from, display)) {
                    if (!indices.containsKey(to)) continue;
                    BlockPos oldSource = to.source;
                    if (to instanceof DirectionalShaftHalvesBlockEntity && !to.hasSource()) to.source = from.getBlockPos();
                    float ratio = connection(from, to, display);
                    if (ratio == 0) {
                        float inverse = connection(to, from, display);
                        if (inverse != 0) ratio = 1 / inverse;
                    }
                    if (ratio != 0 && Float.isFinite(ratio)) {
                        if (visited.add(to)) {
                            ratios.put(to, ratios.get(from) * ratio);
                            queue.add(to);
                        }
                    } else to.source = oldSource;
                }
            }
            // Revealing another part of a native construction diagram does not install a
            // stationary rotor into a running machine. Initialize the newly displayed part
            // from the assembly it belongs to; subsequent motion is fully torque integrated.
            if (display != null) {
                var anchor = component.stream().filter(r -> r.active).findFirst().orElse(null);
                if (anchor != null) for (var member : component) if (!member.active) {
                    double ratio = ratios.get(member.be) / ratios.get(anchor.be);
                    member.speed = anchor.speed * ratio;
                    member.angle = member.previous = anchor.angle * ratio;
                }
            }
            boolean hasSource = component.stream().anyMatch(Rotor::source);
            // Native authors often address only the driven gears, leaving the source
            // out of the selection. Refer that command back to the single real source.
            if (!explicitSources) {
                var sources = component.stream().filter(Rotor::source).toList();
                if (sources.size() == 1) {
                    Rotor drive = sources.getFirst();
                    Rotor command = component.stream().max(Comparator.comparingLong((Rotor r) -> r.commandRevision)
                        .thenComparing(r -> r.target != 0).thenComparing(r -> r == drive)).orElseThrow();
                    if (command.commandRevision > drive.appliedRevision) {
                        drive.target = (float) (command.target * ratios.get(drive.be) / ratios.get(command.be));
                        drive.appliedRevision = command.commandRevision;
                    }
                }
            }
            // Native construction lessons often omit their power source. Give that diagram
            // one boundary, but never invent one on a shaft disconnected from a real source.
            if (!hasSource && !explicitSources && component.stream().noneMatch(r -> r.fed)) {
                Rotor boundary = component.stream().filter(r -> r.target != 0).findFirst().orElse(null);
                if (boundary != null) { boundary.boundary = true; hasSource = true; }
            }
            if (hasSource) component.forEach(r -> r.fed = true);
        }

        var connections = new ArrayList<ElasticKinetics.Connection>();
        var presentContacts = new HashSet<Contact>();
        var links = new ArrayList<TorsionSolver.Link>();
        for (var rotor : members) for (var to : neighbours(level, rotor.be, display)) {
            if (!indices.containsKey(to)
                || rotor.be.getBlockPos().compareTo(to.getBlockPos()) >= 0) continue;
            float ratio = connection(rotor.be, to, display);
            if (!Float.isFinite(ratio) || ratio == 0) continue;
            ClutchBlockEntity clutch = rotor.be instanceof ClutchBlockEntity c && ClutchPhysics.output(c) == to ? c
                : to instanceof ClutchBlockEntity c && ClutchPhysics.output(c) == rotor.be ? c : null;
            var edge = new ElasticKinetics.Connection(rotor.be, to, ratio, clutch);
            var contact = new Contact(rotor.be, to);
            edge.twist = contactTwists.getOrDefault(contact, 0d);
            presentContacts.add(contact);
            connections.add(edge);
        }
        contactTwists.keySet().retainAll(presentContacts);
        var bodies = new ArrayList<TorsionSolver.Body>();
        var attachments = members.stream().filter(r -> r.attachment != null).toList();
        var servos = members.stream().filter(r -> r.be instanceof ISequencerDynamics).toList();
        var outputs = new HashMap<KineticBlockEntity, Integer>();
        double[] speed = new double[members.size() + servos.size() + attachments.size()], angles = new double[speed.length];
        for (int i = 0; i < members.size(); i++) {
            Rotor rotor = members.get(i); var be = rotor.be;
            speed[i] = rotor.speed;
            angles[i] = rotor.angle;
            ElasticKinetics.state(be).machineActive = MachineActivity.working(be);
            double impact = MachineActivity.impact(be);
            SourcePhysics.Curve curve = rotor.supply;
            if (rotor.steamBoiler)
                curve = SourcePhysics.steamCurve((com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity) be);
            if (curve == null && be instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity) {
                // Native installation lessons declare passive heat at 16 and burner heat at 64.
                // Powered shafts have no capacity entry of their own: the engine supplies torque.
                double steam = rotor.target == 0 ? 0 : Math.abs(rotor.target) <= 16 ? .125 : 1;
                curve = SourcePhysics.steamCurve(BlockStressValues.getCapacity(com.simibubi.create.AllBlocks.STEAM_ENGINE.get()),
                    steam, 1, (int) Math.signum(rotor.target));
            }
            if (curve == null) {
                double capacity = BlockStressValues.getCapacity(be.getBlockState().getBlock());
                // Omitted-source construction diagrams still need enough drive to work.
                // Their external boundary has the starter clutch's finite torque budget.
                if (rotor.boundary && !rotor.physicalSource())
                    capacity = PhysicsTuning.CLUTCH_TORQUE / 2;
                // Same linear source law as SourcePhysics.curve, with Ponder's RPM as
                // the desired no-load speed (the underlying source command is half of it).
                curve = rotor.source() && rotor.target != 0
                    ? SourcePhysics.linearCurve(rotor.target / 2, Math.max(1, capacity))
                    : new SourcePhysics.Curve(0, 0);
            }
            bodies.add(new TorsionSolver.Body(InertiaRegistry.getInertia(be), curve.stallTorque(),
                curve.damping(), be instanceof EncasedFanBlockEntity ? 0 : impact,
                MachineActivity.quadraticDrag(be), curve.powerLimit(), curve.governorSpeed(), curve.governorRange()));
        }
        for (var rotor : servos) {
            int at = bodies.size(); outputs.put(rotor.be, at);
            var motion = ((ISequencerDynamics) rotor.be).differentialcreate$motion();
            speed[at] = motion.speed;
            angles[at] = motion.shaftAngle();
            bodies.add(new TorsionSolver.Body(SequencerMotion.OUTPUT_INERTIA, 0, 0, motion.command.brake(), 0));
        }
        for (var edge : connections)
            links.add(new TorsionSolver.Link(SequencerKinetics.portIndex(edge.a, edge.b, indices, outputs),
                SequencerKinetics.portIndex(edge.b, edge.a, indices, outputs), edge.ratio,
                edge.stiffness(), edge.damping(), edge.strength(), edge.friction()));
        double[] twist = new double[connections.size() + attachments.size() + servos.size()];
        for (int i = 0; i < connections.size(); i++) twist[i] = connections.get(i).twist;
        var phases = new ArrayList<ShaftPhases.Contact>();
        for (int i = 0; i < links.size(); i++) if (connections.get(i).clutch == null) {
            var link = links.get(i);
            phases.add(new ShaftPhases.Contact(link.a(), link.b(), link.ratio(), twist[i]));
        }
        angles = ShaftPhases.align(angles, phases);
        for (var rotor : servos) {
            var motion = ((ISequencerDynamics) rotor.be).differentialcreate$motion();
            motion.phaseOffset = angles[outputs.get(rotor.be)] - motion.angle;
            rotor.outputVisualOffset += rotor.outputAngle - motion.shaftAngle();
            rotor.outputPrevious = motion.shaftAngle();
        }
        for (int i = 0; i < attachments.size(); i++) {
            Rotor rotor = attachments.get(i); Attachment mounted = rotor.attachment;
            int at = bodies.size(); speed[at] = mounted.speed;
            bodies.add(new TorsionSolver.Body(mounted.inertia, 0, 0, mounted.load, mounted.drag));
            links.add(new TorsionSolver.Link(indices.get(rotor.be), at, 1, PhysicsTuning.TORSIONAL_STIFFNESS,
                PhysicsTuning.TORSIONAL_DAMPING, StrengthRegistry.of(rotor.be), Double.POSITIVE_INFINITY));
            twist[connections.size() + i] = mounted.twist;
        }
        int couplingStart = links.size();
        for (var rotor : servos) links.add(new TorsionSolver.Link(indices.get(rotor.be), outputs.get(rotor.be),
            1, 0, SequencerMotion.COUPLING_DAMPING, Double.POSITIVE_INFINITY, 0));
        var result = servos.isEmpty()
            ? TorsionSolver.advance(bodies, links, speed, twist, RotationMath.TICK_MINUTES, PhysicsTuning.TORSION_MAX_STEP)
            : advanceSequencers(level, members, servos, connections, indices, outputs, bodies, links, speed, twist, couplingStart);
        for (int i = 0; i < members.size(); i++) {
            Rotor rotor = members.get(i);
            // Assembly phase may be recalibrated when a ratio or neighbour changes.
            // Keep the rendered shaft continuous while retaining the solver's calibrated phase.
            if (rotor.active) rotor.visualOffset += rotor.angle - angles[i];
            else rotor.visualOffset = 0;
            rotor.previous = angles[i]; rotor.angle = angles[i] + result.angle()[i];
            rotor.active = true;
            var state = ElasticKinetics.state(rotor.be);
            float old = rotor.be.getTheoreticalSpeed();
            rotor.speed = result.speed()[i];
            state.physicalSpeed = rotor.speed; state.angle = rotor.angle;
            rotor.be.setSpeed((float) result.speed()[i]);
            // StressGauge's notification dereferences a server network. Fans must
            // invalidate their airflow when the integrated speed changes direction/range.
            if (old != rotor.be.getTheoreticalSpeed()
                && (rotor.be instanceof com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity
                    || rotor.be instanceof EncasedFanBlockEntity))
                rotor.be.onSpeedChanged(old);
            if (rotor.be instanceof com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity)
                updateSpeedGaugeComparators(level, rotor.be);
        }
        for (int i = 0; i < attachments.size(); i++) {
            Attachment mounted = attachments.get(i).attachment;
            mounted.previous = mounted.angle;
            mounted.angle += result.angle()[members.size() + servos.size() + i];
            mounted.speed = result.speed()[members.size() + servos.size() + i];
            mounted.twist = result.twist()[connections.size() + i];
        }
        for (int i = 0; i < connections.size(); i++) {
            var edge = connections.get(i);
            edge.twist = result.twist()[i]; edge.torque = result.torque()[i]; edge.save();
            contactTwists.put(new Contact(edge.a, edge.b), edge.twist);
            if (edge.clutch != null) {
                var clutch = (IClutchDynamics) edge.clutch;
                var output = ClutchPhysics.output(edge.clutch);
                clutch.differentialcreate$setFreeOutputSpeed(output == null ? 0 : output.getSpeed());
                int timer = !result.slipping()[i] && output != null
                    && Math.abs(edge.clutch.getSpeed() - output.getSpeed()) < PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM
                    ? Math.min(PhysicsTuning.CLUTCH_LOCK_TICKS, clutch.differentialcreate$getLockTimer() + 1) : 0;
                clutch.differentialcreate$setLockTimer(timer);
                clutch.differentialcreate$setLocked(timer >= PhysicsTuning.CLUTCH_LOCK_TICKS);
                clutch.differentialcreate$setLastCouplingTorque((float) edge.torque);
            }
        }
        for (Rotor rotor : members) if (rotor.be instanceof ClutchBlockEntity clutch) {
            var output = ClutchPhysics.output(clutch);
            Rotor connected = output == null ? null : rotors.get(output.getBlockPos());
            if (connected == null && ClutchPhysics.torqueLimit(clutch) > 0) connected = rotor;
            if (connected != null) {
                rotor.outputVisualOffset += rotor.outputAngle - connected.previous;
                rotor.outputPrevious = connected.previous; rotor.outputAngle = connected.angle;
                rotor.outputSpeed = connected.be.getSpeed();
            } else {
                rotor.outputPrevious = rotor.outputAngle;
                rotor.outputAngle += rotor.outputSpeed * RotationMath.TICK_MINUTES;
            }
            var state = (IClutchDynamics) clutch;
            state.differentialcreate$setFreeOutputSpeed((float) rotor.outputSpeed);
            state.differentialcreate$setLastDeltaOmega(clutch.getSpeed() - (float) rotor.outputSpeed);
            if (ClutchPhysics.torqueLimit(clutch) == 0) {
                state.differentialcreate$setLockTimer(0); state.differentialcreate$setLocked(false);
            }
        }
        for (var rotor : servos)
            rotor.outputAngle = ((ISequencerDynamics) rotor.be).differentialcreate$motion().shaftAngle();
        if (result.broken() >= connections.size() && result.broken() < connections.size() + attachments.size())
            attachments.get(result.broken() - connections.size()).attachment = null;
        else if (result.broken() >= 0 && result.broken() < connections.size()) {
            var edge = connections.get(result.broken());
            var weakest = edge.weakest();
            level.setBlock(weakest.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
            if (level.scene != null) level.scene.forEach(net.createmod.ponder.api.element.WorldSectionElement.class,
                net.createmod.ponder.api.element.WorldSectionElement::queueRedraw);
        }
    }

    private static void updateSpeedGaugeComparators(PonderLevel level, KineticBlockEntity gauge) {
        int signal = gauge.getBlockState().getAnalogOutputSignal(level, gauge.getBlockPos());
        for (var direction : Direction.Plane.HORIZONTAL) {
            var pos = gauge.getBlockPos().relative(direction);
            if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator)) continue;
            var state = level.getBlockState(pos);
            if (!pos.relative(state.getValue(net.minecraft.world.level.block.ComparatorBlock.FACING)).equals(gauge.getBlockPos())) continue;
            var powered = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
            if (state.getValue(powered) != (signal > 0)) level.setBlock(pos, state.setValue(powered, signal > 0), 3);
            comparator.setOutputSignal(signal);
        }
    }

    /** Replan at the production control interval, keeping separate input/output energy and reaction torque. */
    private static TorsionSolver.Result advanceSequencers(PonderLevel level, List<Rotor> members, List<Rotor> servos,
        List<ElasticKinetics.Connection> connections, Map<KineticBlockEntity, Integer> indices,
        Map<KineticBlockEntity, Integer> outputs, List<TorsionSolver.Body> bodies, List<TorsionSolver.Link> links,
        double[] speed, double[] twist, int couplingStart) {
        double remaining = RotationMath.TICK_MINUTES;
        double[] angles = new double[speed.length], travel = new double[speed.length];
        TorsionSolver.Result result = null;
        while (remaining > 1e-14) {
            for (int i = 0; i < members.size(); i++) {
                members.get(i).be.setSpeed((float) speed[i]);
                ElasticKinetics.state(members.get(i).be).physicalSpeed = speed[i];
            }
            var limits = new ArrayList<TorsionSolver.EventLimit>();
            for (int i = 0; i < servos.size(); i++) {
                var rotor = servos.get(i);
                var motion = ((ISequencerDynamics) rotor.be).differentialcreate$motion();
                var command = SequencerKinetics.prepare(rotor.be, connections, Map.of());
                int out = outputs.get(rotor.be);
                bodies.set(out, new TorsionSolver.Body(SequencerMotion.OUTPUT_INERTIA, 0, 0, command.brake(), 0));
                links.set(couplingStart + i, new TorsionSolver.Link(indices.get(rotor.be), out, command.gear(),
                    0, SequencerMotion.COUPLING_DAMPING, Double.POSITIVE_INFINITY, command.coupling()));
                double distance = motion.direction * (motion.target - motion.angle);
                if (command.exitSpeed() > SequencerMotion.SPEED_EPSILON && distance > SequencerMotion.POSITION_EPSILON)
                    limits.add(new TorsionSolver.EventLimit(out, Double.POSITIVE_INFINITY,
                        motion.direction > 0 ? distance : Double.POSITIVE_INFINITY,
                        motion.direction < 0 ? distance : Double.POSITIVE_INFINITY));
            }
            double duration = Math.min(remaining, SequencerMotion.CONTROL_INTERVAL);
            var captured = TorsionSolver.prepare(bodies, links, speed, twist);
            result = limits.isEmpty() ? captured.advance(duration, duration)
                : captured.advanceToEvent(duration, duration, limits);
            speed = result.speed(); twist = result.twist();
            for (int i = 0; i < speed.length; i++) {
                angles[i] += result.angle()[i]; travel[i] += result.travel()[i];
            }
            for (var rotor : servos) {
                var servo = (ISequencerDynamics) rotor.be;
                var motion = servo.differentialcreate$motion();
                int at = outputs.get(rotor.be);
                motion.angle += result.angle()[at]; motion.speed = speed[at];
                motion.brakeHeat += motion.command.brake() * result.travel()[at];
                motion.observe(result.duration());
                if (motion.index >= 0 && motion.complete())
                    motion.begin(servo.differentialcreate$program(), motion.index + 1, rotor.be.getSpeed(), true);
            }
            remaining -= result.duration();
            if (result.broken() >= 0) break;
        }
        for (var rotor : servos) {
            var motion = ((ISequencerDynamics) rotor.be).differentialcreate$motion();
            var state = level.getBlockState(rotor.be.getBlockPos());
            int display = motion.index < 0 ? 0 : motion.index + 1;
            var property = com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock.STATE;
            if (state.getValue(property) != display) level.setBlock(rotor.be.getBlockPos(), state.setValue(property, display), 3);
        }
        return new TorsionSolver.Result(RotationMath.TICK_MINUTES - remaining, speed, twist, angles, travel,
            result.torque(), result.slipping(), result.broken());
    }

    private static Iterable<KineticBlockEntity> neighbours(PonderLevel level, KineticBlockEntity be, PonderDisplayTopology display) {
        if (display != null) return display.neighbours(be);
        var result = new ArrayList<KineticBlockEntity>();
        for (var pos : locations(be)) if (level.getBlockEntity(pos) instanceof KineticBlockEntity other) result.add(other);
        return result;
    }

    private static float connection(KineticBlockEntity from, KineticBlockEntity to, PonderDisplayTopology display) {
        return display == null ? NetworkTopology.connection(from, to) : display.connection(from, to);
    }

    static List<BlockPos> locations(KineticBlockEntity be) {
        var positions = new ArrayList<BlockPos>();
        for (var direction : Direction.values()) positions.add(be.getBlockPos().relative(direction));
        return be.getBlockState().getBlock() instanceof IRotate rotate
            ? be.addPropagationLocations(rotate, be.getBlockState(), positions) : positions;
    }

    public Double angle(KineticBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        return rotor == null || rotor.be != be || !rotor.active ? null
            : 360 * (rotor.visualOffset + rotor.previous + (rotor.angle - rotor.previous) * partialTick);
    }

    public Double outputAngle(KineticBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        Attachment mounted = rotor == null ? null : rotor.attachment;
        return mounted == null ? null : 360 * (mounted.previous + (mounted.angle - mounted.previous) * partialTick);
    }

    public Double clutchOutputAngle(ClutchBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        return rotor == null || rotor.be != be ? null
            : 360 * (rotor.outputVisualOffset + rotor.outputPrevious + (rotor.outputAngle - rotor.outputPrevious) * partialTick);
    }

    public Double sequencerOutputAngle(KineticBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        return rotor == null || rotor.be != be ? null
            : 360 * (rotor.outputVisualOffset + rotor.outputPrevious + (rotor.outputAngle - rotor.outputPrevious) * partialTick);
    }
}
