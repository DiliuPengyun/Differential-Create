package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.diliupengyun.differential_create.kinetics.CrushingKinetics;
import com.diliupengyun.differential_create.kinetics.DriveTorque;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.IClutchDynamics;
import com.diliupengyun.differential_create.kinetics.ICrushingDynamics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.LoadGaugeBehaviour;
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
import com.simibubi.create.content.kinetics.gauge.GaugeBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;

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
        boolean fed, boundary, inferredBoundary, active, steamBoiler;
        long commandRevision, appliedRevision = -1;
        long componentId;
        double componentRatio = 1;
        double previous, angle, speed;
        double travel;
        double tickTravel;
        double outputPrevious, outputAngle, outputSpeed;
        double visualOffset, outputVisualOffset;
        Attachment attachment;
        SourcePhysics.Curve supply;
        Rotor(KineticBlockEntity be) {
            this.be = be;
            // Native gauge RPM is a readout animation, not an off-stage drive.
            target = be instanceof GaugeBlockEntity ? 0 : be.getTheoreticalSpeed();
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
        boolean active = true;
        double inertia, load, actorLoad, drag, speed, previous, angle, twist, ratio = 1;
        com.diliupengyun.differential_create.kinetics.ShaftPotential gravity = com.diliupengyun.differential_create.kinetics.ShaftPotential.NONE;
    }
    public record AttachmentState(boolean active, double inertia, double load, double actorLoad) {}
    public AttachmentState attachmentState(BlockPos pos) {
        var rotor = rotors.get(pos); var mounted = rotor == null ? null : rotor.attachment;
        return mounted == null ? null : new AttachmentState(mounted.active, mounted.inertia, mounted.load, mounted.actorLoad);
    }
    public void setAttachmentActive(KineticBlockEntity be, boolean active) {
        var rotor = rotors.get(be.getBlockPos());
        if (rotor == null || rotor.be != be || rotor.attachment == null) return;
        var mounted = rotor.attachment;
        if (!active) { mounted.speed = mounted.twist = 0; mounted.load = mounted.actorLoad = 0; }
        mounted.active = active;
    }

    private record Contact(KineticBlockEntity a, KineticBlockEntity b, double ratio) {}
    private record DisplayLine(BlockPos board, int line) {}

    private final Map<BlockPos, Rotor> rotors = new LinkedHashMap<>();
    private final Map<Contact, Double> contactTwists = new HashMap<>();
    private final Set<BlockPos> processingFans = new HashSet<>();
    private final Set<BlockPos> processingCrushers = new HashSet<>();
    private final Set<BlockPos> resynchronized = new HashSet<>();
    private final Map<KineticBlockEntity, LoadFactor.Reading> loadReadings = new HashMap<>();
    private final Map<DisplayLine, BlockPos> displayReadings = new HashMap<>();
    private PonderDisplayTopology display;
    private boolean explicitSources;
    private long commandRevision;
    private long componentRevision;
    private Map<KineticBlockEntity, Double> hydraulicTorques = Map.of();

    public void hydraulicTorques(Map<KineticBlockEntity, Double> torques) { hydraulicTorques = torques; }
    public double workTravel(BlockPos pos) {
        var rotor = rotors.get(pos); return rotor == null ? 0 : rotor.tickTravel;
    }
    public double outputSpeed(BlockPos pos) {
        var rotor = rotors.get(pos);
        return rotor == null ? 0 : rotor.attachment != null ? rotor.attachment.active ? rotor.attachment.speed : 0
            : rotor.be instanceof ISequencerDynamics ? rotor.outputSpeed : rotor.speed;
    }

    public static PonderKinetics of(PonderLevel level) {
        return ((IPonderKinetics) level).differentialcreate$kinetics();
    }

    public void reset() { rotors.clear(); contactTwists.clear(); processingFans.clear(); processingCrushers.clear(); resynchronized.clear(); loadReadings.clear(); displayReadings.clear(); hydraulicTorques = Map.of(); display = null; explicitSources = false; commandRevision = 0; componentRevision = 0; }
    public void useExplicitSources() { explicitSources = true; }
    public void enableFanProcessing(BlockPos pos) { processingFans.add(pos.immutable()); }
    public boolean processesFan(BlockPos pos) { return processingFans.contains(pos); }
    public void enableCrusherProcessing(BlockPos pos) { processingCrushers.add(pos.immutable()); }
    public boolean processesCrusher(BlockPos pos) { return processingCrushers.contains(pos); }
    /** True for the one tick on which a joining part was moved onto its assembly's visible phase. */
    public boolean resynchronized(KineticBlockEntity be) { return resynchronized.contains(be.getBlockPos()); }
    /** A connected output shares its neighbour's one-time assembly correction. */
    public boolean outputResynchronized(KineticBlockEntity be) {
        if (!(be instanceof ClutchBlockEntity clutch)) return false;
        var output = visibleClutchOutput(clutch);
        if (output == null && ClutchPhysics.torqueLimit(clutch) > 0) output = clutch;
        return output != null && resynchronized(output);
    }

    private KineticBlockEntity visibleClutchOutput(ClutchBlockEntity clutch) {
        if (!clutch.hasSource()) return null;
        var face = clutch.getSourceFacing().getOpposite();
        var output = shaftNeighbour(clutch, face);
        var rotor = output == null ? null : rotors.get(output.getBlockPos());
        return rotor != null && rotor.active && output.getBlockState().getBlock() instanceof IRotate rotate
            && rotate.hasShaftTowards(output.getLevel(), output.getBlockPos(), output.getBlockState(), face.getOpposite())
            ? output : null;
    }
    public LoadFactor.Reading loadReading(KineticBlockEntity be) {
        return loadReadings.getOrDefault(be, LoadFactor.Reading.NO_POWER);
    }
    /** Command metadata for diagnostics; never use this as a measured speed. */
    public float commandedSpeed(KineticBlockEntity be) {
        var rotor = rotors.get(be.getBlockPos());
        return rotor != null && rotor.be == be ? rotor.target : 0;
    }
    public void displayReading(BlockPos board, int line, BlockPos gauge) {
        var key = new DisplayLine(board.immutable(), line);
        if (gauge == null) displayReadings.remove(key);
        else displayReadings.put(key, gauge.immutable());
    }
    /** Both blocks belong to one rigid solver component after the last tick. */
    public boolean sameComponent(KineticBlockEntity a, KineticBlockEntity b) {
        Rotor first = rotors.get(a.getBlockPos());
        Rotor second = rotors.get(b.getBlockPos());
        return first != null && second != null && first.be == a && second.be == b
            && first.componentId != 0 && first.componentId == second.componentId;
    }

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
                // Gauges follow the solved shaft speed. Forwarding their scripted
                // readout to a source can reverse an already running gear train.
                if (be instanceof GaugeBlockEntity) return;
                Rotor rotor = rotor(be);
                rotor.target = operation.apply(rotor.target);
                // An undriven template's RPM is its initial condition. It must not
                // become a delayed upstream command when that part joins a running line.
                // Once powered, later operator commands still travel back to its source.
                if (rotor.fed || rotor.source()) rotor.commandRevision = revision;
            }
        });
        // Instructions can move a section immediately after commanding it. Apply
        // the command through the currently assembled graph, before that move;
        // otherwise an old stop/reverse is replayed only when the parts reconnect.
        if (!explicitSources) {
            var components = new LinkedHashMap<Long, List<Rotor>>();
            for (var rotor : rotors.values()) if (rotor.active && rotor.componentId != 0)
                components.computeIfAbsent(rotor.componentId, ignored -> new ArrayList<>()).add(rotor);
            for (var component : components.values()) {
                var ratios = new HashMap<KineticBlockEntity, Double>();
                component.forEach(rotor -> ratios.put(rotor.be, rotor.componentRatio));
                forwardCommand(component, ratios);
            }
        }
    }

    /** Explicit off-stage drive for diagrams that show a machine without its source. */
    public void boundary(PonderLevel level, BlockPos pos, float target) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) {
            Rotor rotor = rotor(be);
            rotor.boundary = true;
            rotor.inferredBoundary = false;
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
    public void attachmentGravity(KineticBlockEntity be, com.diliupengyun.differential_create.kinetics.ShaftPotential gravity) {
        var rotor = rotors.get(be.getBlockPos());
        if (rotor != null && rotor.attachment != null) rotor.attachment.gravity = gravity;
    }
    public void setActorLoad(BlockPos pos, double load) {
        var rotor = rotors.get(pos);
        if (rotor != null && rotor.attachment != null) rotor.attachment.actorLoad = Math.max(0, load);
    }

    /** A tutorial pulse arms the same program and passive position controller as gameplay. */
    public void sequence(PonderLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be && be instanceof ISequencerDynamics servo)
            servo.differentialcreate$motion().begin(servo.differentialcreate$program(), 0, be.getSpeed(), false);
    }

    public void tick(PonderLevel level) {
        rotors.values().forEach(rotor -> {
            rotor.tickTravel = 0;
            if (rotor.attachment != null) rotor.attachment.previous = rotor.attachment.angle;
        });
        resynchronized.clear();
        loadReadings.clear();
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
        display = !explicitSources && level.scene != null ? new PonderDisplayTopology(level) : null;
        var members = new ArrayList<>(rotors.values().stream().filter(r -> display == null || display.contains(r.be)).toList());
        for (var rotor : rotors.values()) if (display != null && !display.contains(rotor.be)) {
            rotor.active = false;
            rotor.componentId = 0;
        }
        for (var rotor : members) if (display != null && !rotor.active) {
            rotor.speed = 0;
            rotor.previous = rotor.angle = rotor.visualOffset = rotor.travel = 0;
            rotor.componentId = 0;
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
            // Native construction diagrams reveal a pre-assembled part into a machine
            // that may already be running. It joins through the source logic below;
            // synchronizeDisplay then puts it on the assembly's visible phase.
            if (!explicitSources) {
                // A separately revealed part may have borrowed an off-stage drive.
                // Once it joins a real drive (or an older running assembly), it is
                // passive again, not a second motor fighting the same shaft.
                var sources = component.stream().filter(Rotor::source).toList();
                if (sources.size() > 1) {
                    boolean realDrive = sources.stream().anyMatch(r -> !r.inferredBoundary);
                    Rotor reference = sources.stream().max(Comparator.comparingDouble(r -> r.travel)).orElseThrow();
                    for (var source : sources) if (source.inferredBoundary && (realDrive || source != reference)) {
                        source.boundary = false;
                        source.inferredBoundary = false;
                    }
                }
            }
            boolean hasSource = component.stream().anyMatch(Rotor::source);
            // Native authors often address only the driven gears, leaving the source
            // out of the selection. Refer that command back to the single real source.
            if (!explicitSources) forwardCommand(component, ratios);
            // Native construction lessons often omit their power source. Give that diagram
            // one boundary, but never invent one on a shaft disconnected from a real source.
            if (!hasSource && !explicitSources && component.stream().noneMatch(r -> r.fed)) {
                Rotor boundary = component.stream().filter(r -> r.target != 0).findFirst().orElse(null);
                if (boundary != null) { boundary.boundary = boundary.inferredBoundary = true; hasSource = true; }
            }
            if (hasSource) component.forEach(r -> r.fed = true);
            if (hasSource) {
                var sources = component.stream().filter(Rotor::source).toList();
                if (sources.size() == 1)
                    orientSplitFaces(sources.getFirst().be, component, ratios, level, display);
            }
            synchronizeDisplay(component, ratios);
            long componentId = ++componentRevision;
            for (var member : component) {
                member.componentId = componentId;
                member.componentRatio = ratios.get(member.be);
            }
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
            var contact = new Contact(rotor.be, to, ratio);
            edge.twist = contactTwists.getOrDefault(contact, 0d);
            presentContacts.add(contact);
            connections.add(edge);
        }
        contactTwists.keySet().retainAll(presentContacts);
        if (display != null) synchronizeVisibleContacts(connections);
        var bodies = new ArrayList<TorsionSolver.Body>();
        var attachments = members.stream().filter(r -> r.attachment != null && r.attachment.active).toList();
        var servos = members.stream().filter(r -> r.be instanceof ISequencerDynamics).toList();
        var outputs = new HashMap<KineticBlockEntity, Integer>();
        double[] speed = new double[members.size() + servos.size() + attachments.size()], angles = new double[speed.length];
        for (int i = 0; i < members.size(); i++) {
            Rotor rotor = members.get(i); var be = rotor.be;
            speed[i] = rotor.speed;
            angles[i] = rotor.angle;
            ElasticKinetics.state(be).machineActive = MachineActivity.working(be);
            // A crushing pair reacts on relative wheel motion, not on each wheel's
            // absolute motion. Its work contact is added below, as in gameplay.
            double impact = be instanceof com.diliupengyun.differential_create.kinetics.IPumpDynamics pump
                ? Math.abs(pump.differentialcreate$torque())
                : be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity
                ? 0 : MachineActivity.impact(be);
            SourcePhysics.Curve curve = rotor.supply;
            if (rotor.steamBoiler)
                curve = SourcePhysics.steamCurve((com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity) be);
            if (curve == null && be instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity) {
                curve = SourcePhysics.steamCurve((com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity) be);
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
            bodies.add(new TorsionSolver.Body(InertiaRegistry.getInertia(be), curve.stallTorque()
                + (be instanceof com.diliupengyun.differential_create.kinetics.IPumpDynamics ? 0 : hydraulicTorques.getOrDefault(be, 0d)),
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
            double ratio = PonderSimulation.of(level).couplingRatio(rotor.be);
            if (ratio != mounted.ratio) { mounted.twist = 0; mounted.ratio = ratio; }
            int at = bodies.size(); speed[at] = mounted.speed;
            bodies.add(new TorsionSolver.Body(mounted.inertia, 0, 0, mounted.load + mounted.actorLoad, mounted.drag)
                .withPotential(mounted.gravity).withSpeedLimit(com.diliupengyun.differential_create.kinetics.ContraptionMotion.speedLimit(rotor.be)));
            boolean linear = rotor.be instanceof com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity
                || rotor.be instanceof com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
            links.add(new TorsionSolver.Link(indices.get(rotor.be), at, ratio, PhysicsTuning.TORSIONAL_STIFFNESS,
                PhysicsTuning.TORSIONAL_DAMPING, linear ? Double.POSITIVE_INFINITY : StrengthRegistry.of(rotor.be),
                linear ? StrengthRegistry.of(rotor.be) : Double.POSITIVE_INFINITY));
            twist[connections.size() + i] = mounted.twist;
        }
        int couplingStart = links.size();
        for (var rotor : servos) links.add(new TorsionSolver.Link(indices.get(rotor.be), outputs.get(rotor.be),
            1, 0, SequencerMotion.COUPLING_DAMPING, Double.POSITIVE_INFINITY, 0));
        var crushers = new ArrayList<CrushingKinetics.Pair>();
        for (var be : level.getBlockEntities())
            if (be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity controller) {
                var pair = CrushingKinetics.pair(controller);
                if (pair != null && pair.active() && indices.containsKey(pair.a()) && indices.containsKey(pair.b()))
                    crushers.add(pair);
            }
        int crushingStart = links.size();
        for (var pair : crushers)
            links.add(TorsionSolver.Link.work(indices.get(pair.a()), indices.get(pair.b()), pair.torque()));
        twist = Arrays.copyOf(twist, links.size());
        var result = servos.isEmpty() && attachments.isEmpty()
            ? TorsionSolver.advance(bodies, links, speed, twist, RotationMath.TICK_MINUTES, PhysicsTuning.TORSION_MAX_STEP)
            : advanceMotion(level, members, servos, attachments, connections, indices, outputs, bodies, links, speed, twist, couplingStart, explicitSources);
        for (int i = 0; i < crushers.size(); i++)
            ((ICrushingDynamics) crushers.get(i).controller()).differentialcreate$reaction(result.torque()[crushingStart + i]);
        sampleLoads(members, servos, attachments, outputs, bodies, links, result);
        for (int i = 0; i < members.size(); i++) {
            Rotor rotor = members.get(i);
            // Assembly phase may be recalibrated when a ratio or neighbour changes.
            // Keep the rendered shaft continuous while retaining the solver's calibrated phase.
            if (rotor.active) rotor.visualOffset += rotor.angle - angles[i];
            else rotor.visualOffset = 0;
            rotor.previous = angles[i]; rotor.angle = angles[i] + result.angle()[i];
            rotor.travel += Math.abs(result.angle()[i]);
            rotor.tickTravel = result.travel()[i] * 1200;
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
                    || rotor.be instanceof EncasedFanBlockEntity
                    || rotor.be instanceof com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity
                        && Math.signum(old) != Math.signum(rotor.be.getTheoreticalSpeed())))
                rotor.be.onSpeedChanged(old);
            if (rotor.be instanceof com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity)
                updateGaugeComparators(level, rotor.be);
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
            contactTwists.put(new Contact(edge.a, edge.b, edge.ratio), edge.twist);
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
            var output = visibleClutchOutput(clutch);
            Rotor connected = output == null ? null : rotors.get(output.getBlockPos());
            if (connected == null && ClutchPhysics.torqueLimit(clutch) > 0) connected = rotor;
            if (connected != null) {
                rotor.outputVisualOffset = connected.visualOffset;
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

    private static void forwardCommand(List<Rotor> component, Map<KineticBlockEntity, Double> ratios) {
        var sources = component.stream().filter(Rotor::source).toList();
        if (sources.size() != 1) return;
        Rotor drive = sources.getFirst();
        Rotor command = component.stream().max(Comparator.comparingLong((Rotor r) -> r.commandRevision)
            .thenComparing(r -> r.target != 0).thenComparing(r -> r == drive)).orElseThrow();
        if (command.commandRevision <= drive.appliedRevision) return;
        // These mechanisms change an output ratio/connection. Their scripted
        // output RPM is a consequence, never a command to reverse or speed up the input.
        boolean transmission = component.stream().anyMatch(r -> r.be instanceof SplitShaftBlockEntity
            || r.be instanceof com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity
            || r.be instanceof com.simibubi.create.content.kinetics.chainDrive.ChainGearshiftBlockEntity);
        if (!transmission)
            drive.target = (float) (command.target * ratios.get(drive.be) / ratios.get(command.be));
        drive.appliedRevision = command.commandRevision;
    }

    /**
     * Native templates carry the original world's {@code source} position. For a
     * split shaft that can be a self-position, making getSourceFacing() arbitrary;
     * the gearshift then reverses both faces and its output runs the same way as
     * the input. Re-derive each split face from the actual drive side.
     */
    private void orientSplitFaces(KineticBlockEntity drive, List<Rotor> component,
        Map<KineticBlockEntity, Double> ratios, PonderLevel level, PonderDisplayTopology display) {
        var members = new HashSet<KineticBlockEntity>();
        for (var member : component) members.add(member.be);
        var oriented = new HashMap<KineticBlockEntity, Double>();
        oriented.put(drive, 1d);
        var seen = new HashSet<KineticBlockEntity>();
        seen.add(drive);
        var queue = new ArrayDeque<KineticBlockEntity>();
        queue.add(drive);
        while (!queue.isEmpty()) {
            var from = queue.removeFirst();
            for (var to : neighbours(level, from, display)) {
                if (!members.contains(to) || seen.contains(to)) continue;
                if (to instanceof DirectionalShaftHalvesBlockEntity) to.source = from.getBlockPos();
                float ratio = connection(from, to, display);
                if (ratio == 0) {
                    float inverse = connection(to, from, display);
                    if (inverse != 0) ratio = 1 / inverse;
                }
                if (ratio != 0 && Float.isFinite(ratio)) {
                    seen.add(to);
                    oriented.put(to, oriented.get(from) * ratio);
                    queue.add(to);
                }
            }
        }
        if (seen.size() == members.size()) {
            ratios.clear();
            ratios.putAll(oriented);
        }
    }

    /**
     * Two parts that were revealed or spun separately keep the phase difference they
     * accumulated before they touched. ShaftPhases.align only corrects the solver's
     * accumulated angle, while tick() keeps the rendered angle continuous through
     * visualOffset, so without this step the joint keeps a fixed twist. Rebase the
     * side that just joined onto the visible phase of the established assembly.
     * The side with more visible travel is the reference, which leaves an already
     * running line alone and moves a gauge that was held or revealed separately.
     */
    private void synchronizeDisplay(List<Rotor> component, Map<KineticBlockEntity, Double> ratios) {
        var groups = new LinkedHashMap<Long, List<Rotor>>();
        boolean hasNew = false;
        for (var member : component) {
            if (member.active && member.componentId != 0)
                groups.computeIfAbsent(member.componentId, id -> new ArrayList<>()).add(member);
            else hasNew = true;
        }
        if (groups.isEmpty() || groups.size() == 1 && !hasNew) return;

        List<Rotor> reference = null;
        double referenceTravel = -1;
        boolean referenceSource = false;
        for (var group : groups.values()) {
            double travel = 0;
            boolean source = false;
            for (var member : group) {
                travel += Math.abs(member.visualOffset + member.angle);
                source |= member.source();
            }
            if (reference == null || travel > referenceTravel + 1e-9
                || (Math.abs(travel - referenceTravel) <= 1e-9 && source && !referenceSource)
                || (Math.abs(travel - referenceTravel) <= 1e-9 && source == referenceSource
                    && group.size() > reference.size())) {
                reference = group;
                referenceTravel = travel;
                referenceSource = source;
            }
        }

        Rotor anchor = reference.getFirst();
        double anchorRatio = ratios.get(anchor.be);
        double anchorAngle = anchor.visualOffset + anchor.angle;
        for (var member : component) {
            double ratio = ratios.get(member.be) / anchorRatio;
            double desired = anchorAngle * ratio;
            if (!member.active) {
                // A newly displayed part starts at the assembly's visible phase before
                // ShaftPhases.align takes over; visualOffset remains zero for its first tick.
                member.speed = anchor.speed * ratio;
                member.previous = member.angle = desired;
            } else if (member.componentId != anchor.componentId) {
                // Keep the displayed angle, not the absolute offset, so the correction
                // cannot accumulate a growing multiple of a full turn.
                member.visualOffset += Math.IEEEremainder(desired - (member.visualOffset + member.angle), 1);
                resynchronized.add(member.be.getBlockPos().immutable());
            }
        }
    }

    /**
     * A section can keep its old visible phase while fading in and already share a
     * solver component with the running line, so the component-id merge check misses
     * it. Enforce ratio-1 rigid contacts whenever their rendered phases differ: snap
     * the side with less accumulated travel. Clutches and split shafts are excluded;
     * their faces have their own renderer paths.
     */
    private void synchronizeVisibleContacts(List<ElasticKinetics.Connection> connections) {
        for (var edge : connections) {
            if (edge.clutch != null || edge.ratio != 1) continue;
            if (edge.a instanceof SplitShaftBlockEntity || edge.b instanceof SplitShaftBlockEntity) continue;
            Rotor a = rotors.get(edge.a.getBlockPos());
            Rotor b = rotors.get(edge.b.getBlockPos());
            if (a == null || b == null || !a.active || !b.active) continue;
            double da = a.visualOffset + a.angle;
            double db = b.visualOffset + b.angle;
            // The shaft model repeats every quarter turn, so a 90-degree offset is invisible.
            if (Math.abs(Math.IEEEremainder(db - da, .25)) <= .5 / 360) continue;
            if (a.travel >= b.travel) {
                b.visualOffset += Math.IEEEremainder(da - db, 1);
                resynchronized.add(b.be.getBlockPos().immutable());
            } else {
                a.visualOffset += Math.IEEEremainder(db - da, 1);
                resynchronized.add(a.be.getBlockPos().immutable());
            }
        }
    }

    /** Sample the same bodies and contacts that just supplied the accepted motion. */
    private void sampleLoads(List<Rotor> members, List<Rotor> servos, List<Rotor> attachments,
        Map<KineticBlockEntity, Integer> outputs,
        List<TorsionSolver.Body> bodies, List<TorsionSolver.Link> links, TorsionSolver.Result result) {
        long[] groups = new long[bodies.size()];
        double[] ratios = new double[bodies.size()];
        for (int i = 0; i < members.size(); i++) {
            groups[i] = members.get(i).componentId;
            ratios[i] = members.get(i).componentRatio;
        }
        for (var rotor : servos) {
            int i = outputs.get(rotor.be);
            groups[i] = rotor.componentId;
            ratios[i] = rotor.componentRatio * ((ISequencerDynamics) rotor.be).differentialcreate$motion().command.gear();
        }
        for (int i = 0; i < attachments.size(); i++) {
            groups[members.size() + servos.size() + i] = attachments.get(i).componentId;
            ratios[members.size() + servos.size() + i] = attachments.get(i).componentRatio * attachments.get(i).attachment.ratio;
        }
        var samples = new HashMap<Long, List<LoadFactor.Member>>();
        var losses = new HashMap<Long, Double>();
        for (int i = 0; i < bodies.size(); i++) {
            var body = bodies.get(i);
            double rpm = result.speed()[i];
            double driving = DriveTorque.torque(body.drive(), body.powerLimit(), rpm, body.governorSpeed(), body.governorRange());
            double drive = LoadFactor.resolvedTorque(driving - body.damping() * rpm,
                Math.abs(driving) + Math.abs(body.damping() * rpm));
            samples.computeIfAbsent(groups[i], ignored -> new ArrayList<>()).add(new LoadFactor.Member(
                ratios[i], rpm, drive, body.load() + body.drag() * rpm * rpm, body.drive() != 0));
            if (body.potential().active()) samples.get(groups[i]).add(new LoadFactor.Member(
                ratios[i], rpm, body.potential().torque(0, 0), 0, false));
        }
        for (int i = 0; i < links.size(); i++) {
            var link = links.get(i);
            int a = link.a(), b = link.b();
            double relative = link.ratio() * result.speed()[a] - result.speed()[b];
            if (link.work()) {
                double torque = result.torque()[i];
                if (groups[a] == groups[b]) {
                    samples.get(groups[a]).add(new LoadFactor.Member(ratios[a] - ratios[b], relative, 0,
                        relative == 0 ? link.friction() : Math.max(0, torque * Math.signum(relative)), false));
                } else {
                    addReturnedWork(samples.get(groups[a]), ratios[a], result.speed()[a], -torque);
                    addReturnedWork(samples.get(groups[b]), ratios[b], result.speed()[b], torque);
                }
            } else {
                losses.merge(groups[a], LoadFactor.contactLoss(link.stiffness(), link.damping(),
                    link.friction(), result.twist()[i], relative), Double::sum);
            }
        }
        var readings = new HashMap<Long, LoadFactor.Reading>();
        samples.forEach((id, values) -> readings.put(id, LoadFactor.calculate(values, losses.getOrDefault(id, 0d))));
        for (var rotor : members) loadReadings.put(rotor.be, readings.get(rotor.componentId));
    }

    private static void addReturnedWork(List<LoadFactor.Member> samples, double ratio, double speed, double reaction) {
        boolean driving = reaction * speed > 0 || speed == 0 && reaction != 0;
        samples.add(new LoadFactor.Member(ratio, speed, driving ? reaction : 0,
            Math.max(0, -reaction * Math.signum(speed)), driving));
    }

    /** Run after storyboard commands too: stale NBT and scripted redstone cannot replace measurements. */
    public void refreshInstruments(PonderLevel level) {
        var entities = new ArrayList<net.minecraft.world.level.block.entity.BlockEntity>();
        level.getBlockEntities().forEach(entities::add);
        for (var be : entities) {
            if (be instanceof com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity gauge) {
                var behaviour = gauge.getBehaviour(LoadGaugeBehaviour.TYPE);
                if (behaviour != null) behaviour.refresh();
                updateGaugeComparators(level, gauge);
            } else if (be instanceof com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity gauge) {
                int range = com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour.rangeOf(gauge);
                double speed = com.diliupengyun.differential_create.kinetics.NetworkDynamics.preciseSpeed(gauge);
                gauge.dialTarget = com.diliupengyun.differential_create.kinetics.SpeedGaugeScale.fraction(speed, range);
                gauge.color = com.diliupengyun.differential_create.kinetics.SpeedGaugeScale.color(speed, range);
                updateGaugeComparators(level, gauge);
            }
        }
        displayReadings.forEach((line, source) -> {
            if (level.getBlockEntity(line.board()) instanceof com.simibubi.create.content.trains.display.FlapDisplayBlockEntity board
                && level.getBlockEntity(source) instanceof com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity gauge) {
                var text = LoadGaugeBehaviour.text(loadReading(gauge));
                var lines = board.getLines();
                if (line.line() < lines.size()) {
                    var sections = lines.get(line.line()).getSections();
                    // Re-applying unchanged text restarts the physical flap animation.
                    if (sections.isEmpty() || !text.equals(sections.getFirst().getText()))
                        board.applyTextManually(line.line(), text);
                }
            }
        });
    }

    static void updateGaugeComparators(PonderLevel level, net.minecraft.world.level.block.entity.BlockEntity gauge) {
        int signal = gauge.getBlockState().getAnalogOutputSignal(level, gauge.getBlockPos());
        for (var direction : Direction.Plane.HORIZONTAL) {
            var pos = gauge.getBlockPos().relative(direction);
            if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator)) continue;
            var state = level.getBlockState(pos);
            if (!pos.relative(state.getValue(net.minecraft.world.level.block.ComparatorBlock.FACING)).equals(gauge.getBlockPos())) continue;
            var powered = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
            boolean changed = state.getValue(powered) != (signal > 0);
            if (changed) level.setBlock(pos, state.setValue(powered, signal > 0), 3);
            comparator.setOutputSignal(signal);
            // Ponder does not run the server's redstone propagation. Update the
            // output wire from this measured signal, including when it returns to zero.
            var output = pos.relative(state.getValue(net.minecraft.world.level.block.ComparatorBlock.FACING).getOpposite());
            var queue = new ArrayDeque<BlockPos>();
            var strengths = new HashMap<BlockPos, Integer>();
            queue.add(output); strengths.put(output, signal);
            while (!queue.isEmpty()) {
                var wire = queue.removeFirst();
                var wireState = level.getBlockState(wire);
                if (!wireState.is(Blocks.REDSTONE_WIRE)) continue;
                int strength = strengths.get(wire);
                var power = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER;
                if (wireState.getValue(power) != strength) {
                    level.setBlock(wire, wireState.setValue(power, strength), 3);
                    changed = true;
                }
                for (var side : Direction.Plane.HORIZONTAL) {
                    var next = wire.relative(side);
                    if (strengths.putIfAbsent(next, Math.max(0, strength - 1)) == null) queue.add(next);
                }
            }
            if (changed && level.scene != null)
                level.scene.forEach(net.createmod.ponder.api.element.WorldSectionElement.class,
                    net.createmod.ponder.api.element.WorldSectionElement::queueRedraw);
        }
    }

    /** Replan at the production control interval, keeping separate input/output energy and reaction torque. */
    private static TorsionSolver.Result advanceMotion(PonderLevel level, List<Rotor> members, List<Rotor> servos, List<Rotor> attachments,
        List<ElasticKinetics.Connection> connections, Map<KineticBlockEntity, Integer> indices,
        Map<KineticBlockEntity, Integer> outputs, List<TorsionSolver.Body> bodies, List<TorsionSolver.Link> links,
        double[] speed, double[] twist, int couplingStart, boolean updateDisplay) {
        double remaining = RotationMath.TICK_MINUTES;
        double[] angles = new double[speed.length], travel = new double[speed.length];
        var initialBodies = List.copyOf(bodies);
        var initialLinks = List.copyOf(links);
        var bounds = attachments.stream().map(rotor -> PonderSimulation.of(level).linearBounds(rotor.be)).toList();
        TorsionSolver.Result result = null;
        while (remaining > 1e-14) {
            for (int i = 0; i < members.size(); i++) {
                members.get(i).be.setSpeed((float) speed[i]);
                ElasticKinetics.state(members.get(i).be).physicalSpeed = speed[i];
            }
            var limits = new ArrayList<TorsionSolver.EventLimit>();
            for (int i = 0; i < attachments.size(); i++) {
                int out = members.size() + servos.size() + i, edge = connections.size() + i;
                var range = bounds.get(i);
                double lower = range.lower() - angles[out], upper = range.upper() - angles[out];
                boolean atLower = lower >= -1e-8, atUpper = upper <= 1e-8;
                int stop = atLower && atUpper ? 2 : atLower ? -1 : atUpper ? 1 : 0;
                var body = initialBodies.get(out);
                bodies.set(out, body.withPotential(body.potential().shifted(angles[out])).withStop(stop));
                speed[out] = Math.clamp(speed[out], -body.speedLimit(), body.speedLimit());
                if (stop == 2 || atLower && speed[out] < 0 || atUpper && speed[out] > 0) speed[out] = 0;
                var link = initialLinks.get(edge);
                double input = speed[link.a()] * link.ratio();
                boolean open = stop == 2 || atLower && input < 0 || atUpper && input > 0;
                links.set(edge, open ? new TorsionSolver.Link(link.a(), out, 1, 0, 0, Double.POSITIVE_INFINITY, 0) : link);
                if (open) twist[edge] = 0;
                if (!atUpper && Double.isFinite(upper) || !atLower && Double.isFinite(lower))
                    limits.add(new TorsionSolver.EventLimit(out, Double.POSITIVE_INFINITY,
                        atUpper ? Double.POSITIVE_INFINITY : upper, atLower ? Double.POSITIVE_INFINITY : -lower));
            }
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
            double duration = servos.isEmpty() ? remaining : Math.min(remaining, SequencerMotion.CONTROL_INTERVAL);
            double step = Math.min(duration, PhysicsTuning.TORSION_MAX_STEP);
            var captured = TorsionSolver.prepare(bodies, links, speed, twist);
            result = limits.isEmpty() ? captured.advance(duration, step)
                : captured.advanceToEvent(duration, step, limits);
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
        for (var rotor : servos) if (updateDisplay) {
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

    /** Rendered face contacts use the same displayed neighbours as the physics graph. */
    public KineticBlockEntity shaftNeighbour(KineticBlockEntity be, Direction face) {
        if (display != null) return display.shaftNeighbour(be, face);
        return be.getLevel().getBlockEntity(be.getBlockPos().relative(face)) instanceof KineticBlockEntity other ? other : null;
    }

    public Double outputAngle(KineticBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        Attachment mounted = rotor == null ? null : rotor.attachment;
        return mounted == null ? null : 360 * (mounted.previous + (mounted.angle - mounted.previous) * partialTick);
    }

    public Double clutchOutputAngle(ClutchBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        return rotor == null || rotor.be != be || !rotor.active ? null
            : 360 * (rotor.outputVisualOffset + rotor.outputPrevious + (rotor.outputAngle - rotor.outputPrevious) * partialTick);
    }

    public Double sequencerOutputAngle(KineticBlockEntity be, float partialTick) {
        Rotor rotor = rotors.get(be.getBlockPos());
        return rotor == null || rotor.be != be || !rotor.active ? null
            : 360 * (rotor.outputVisualOffset + rotor.outputPrevious + (rotor.outputAngle - rotor.outputPrevious) * partialTick);
    }
}
