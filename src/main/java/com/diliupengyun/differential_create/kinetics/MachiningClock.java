package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.diliupengyun.differential_create.kinetics.portable.PortableDocking;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.world.level.Level;

/** Independent solver islands share world-event times, not controller sampling rates. */
public final class MachiningClock {
    private MachiningClock() {}
    private record Phase(MachineWork.Binding binding, double remaining, int wakeDirection) {}
    // Development probes are scoped to the calling thread; ordinary ticks allocate no telemetry.
    interface Observer {
        void accepted(List<KineticNetwork> networks, double duration);
        void barrier();
    }
    static final ThreadLocal<Observer> observer = new ThreadLocal<>();
    private static final double TIME_EPSILON = 1e-14;
    private record Inputs(List<KineticNetwork> networks, List<Phase> phases, List<PortableDocking.Binding> docks) {
        boolean worldFacing() { return !phases.isEmpty() || !docks.isEmpty() || !CrushingKinetics.pairs(networks).isEmpty(); }
    }
    private record Plan(List<KineticNetwork> networks, List<Phase> phases, List<PortableDocking.Binding> docks,
                        List<ElasticKinetics.WorkEvent> limits, ElasticKinetics.Snapshot snapshot,
                        ElasticKinetics.Trial trial, double start) {
        double end() { return start + trial.result.duration(); }
        boolean worldFacing() { return !phases.isEmpty() || !docks.isEmpty() || !CrushingKinetics.pairs(networks).isEmpty(); }
        ElasticKinetics.Trial until(double time) {
            if (Math.abs(end() - time) <= TIME_EPSILON) return trial;
            double duration = Math.max(0, time - start);
            return limits.isEmpty() ? snapshot.sample(duration) : snapshot.advanceToEvent(duration, limits);
        }
    }
    private record Accepted(Plan plan, ElasticKinetics.Trial trial, double[] advances, double[] signed) {}
    public static List<KineticNetwork> networks(Level level) {
        var registry = TorquePropagatorAccessor.differentialcreate$getNetworks().get(level);
        return registry == null ? List.of() : new ArrayList<>(registry.values());
    }
    public static boolean needsPositionControl(KineticBlockEntity be) {
        if (ContraptionPhysics.incomplete(be)) return false;
        return ClockworkMotion.manages(be) ? !ClockworkMotion.outputs(be).isEmpty()
            : be instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity && ActorFeed.enabled(be);
    }
    public static void advance(Level level) {
        com.diliupengyun.differential_create.kinetics.portable.PortableDocking.time(level);
        if (!level.tickRateManager().runsNormally()) return;
        NetworkTopology.flush();
        for (var network : networks(level)) ClutchPhysics.prepare(network);
        try (var reads = KineticReadCache.open()) {
            ContraptionMotion.begin(level);
            try { advanceRotors(level); }
            finally { ContraptionMotion.end(level); }
        }
    }
    private static void advanceRotors(Level level) {
        FluidKinetics.beginTick(level);
        NetworkTopology.flush();
        for (var network : networks(level)) ElasticKinetics.refresh(network);
        FluidKinetics.Frame fluids = FluidKinetics.hasPipes(level) ? FluidKinetics.capture(level) : null;
        Map<KineticBlockEntity, Double> hydraulicTorques = fluids == null ? Map.of()
            : FluidKinetics.advance(fluids, RotationMath.TICK_MINUTES);
        KineticReadCache.clear();
        double time = 0;
        int transitions = 0;
        List<Plan> plans = new ArrayList<>();
        while (time < RotationMath.TICK_MINUTES - TIME_EPSILON) {
            if (++transitions > 16384) throw new IllegalStateException("Physical events did not converge in " + level.dimension().location());
            if (plans.isEmpty()) {
                // Every island is at this time. Preparation may observe contacts or
                // run a zero-travel callback, so it belongs on the common clock too.
                NetworkTopology.flush();
                var all = networks(level);
                ContraptionMotion.resolveStops(all);
                if (SequencerKinetics.finishReady(all)) continue;
                long revision = KineticReadCache.revision();
                var phases = phases(all);
                boolean immediate = false;
                for (var phase : phases) if (phase.remaining <= MachineWork.EPSILON) {
                    phase.binding.work().differentialcreate$advanceTravel(0); immediate = true;
                }
                if (immediate) continue;
                var docks = PortableDocking.prepare(all);
                // Docking can update redstone and topology. Rebuild before capturing.
                NetworkTopology.flush();
                all = networks(level);
                for (var network : all) ElasticKinetics.refresh(network);
                if (revision != KineticReadCache.revision()) continue;
                if (fluids != null && !fluids.valid()) fluids = FluidKinetics.capture(level);
                var inputs = new ArrayList<Inputs>();
                for (var island : KineticIslands.collect(all)) {
                    var members = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<KineticBlockEntity, Boolean>());
                    for (var network : island) members.addAll(network.members.keySet());
                    inputs.add(new Inputs(island, phases.stream().filter(p -> members.contains(p.binding.shaft())).toList(),
                        docks.stream().filter(d -> d.drive() instanceof ContraptionPhysics.Drive drive && members.contains(drive.shaft())).toList()));
                }
                // Find world-facing deadlines first. Never predict slow islands far
                // past a barrier which is already known to invalidate that prediction.
                double deadline = RotationMath.TICK_MINUTES;
                for (var input : inputs) if (input.worldFacing()) {
                    var p = plan(input.networks, input.phases, input.docks, time, deadline, hydraulicTorques);
                    plans.add(p); deadline = Math.min(deadline, p.end());
                }
                for (var input : inputs) if (!input.worldFacing())
                    plans.add(plan(input.networks, input.phases, input.docks, time, deadline, hydraulicTorques));
                if (plans.isEmpty()) break;
            }
            double scheduled = plans.stream().mapToDouble(Plan::end).min().orElseThrow();
            double next = scheduled;
            var due = plans.stream().filter(p -> p.end() <= scheduled + TIME_EPSILON).toList();
            var accepted = new ArrayList<Accepted>();
            boolean barrier = due.stream().anyMatch(p -> p.worldFacing() || p.trial.result.broken() >= 0);
            List<ElasticKinetics.Trial> prefixes = null;
            // A sequencer may finish after observing its accepted state. Prepare its
            // possible barrier before committing too, without resampling world inputs.
            if (barrier || due.stream().anyMatch(p -> SequencerKinetics.mayFinish(p.networks, p.trial))) {
                var samplers = new ArrayList<java.util.function.DoubleFunction<ShaftClock.Prefix<ElasticKinetics.Trial>>>();
                for (var p : plans) samplers.add(at -> {
                    var prefix = p.until(at);
                    return new ShaftClock.Prefix<>(p.start + prefix.result.duration(), prefix);
                });
                var aligned = ShaftClock.align(time, next, TIME_EPSILON, samplers);
                barrier |= aligned.time() < next - TIME_EPSILON;
                next = aligned.time(); prefixes = aligned.states();
            }
            // Commit all simultaneous numerical states before any consumer observes them.
            boolean dueCommitted = !barrier;
            if (dueCommitted) {
                for (var p : due) accepted.add(accept(p, p.trial, hydraulicTorques));
                barrier = due.stream().anyMatch(p -> SequencerKinetics.hasReady(p.networks));
            }
            if (barrier) {
                for (int i = 0; i < plans.size(); i++) {
                    var p = plans.get(i);
                    if (!dueCommitted || !due.contains(p))
                        accepted.add(accept(p, prefixes.get(i), hydraulicTorques));
                }
                var probe = observer.get(); if (probe != null) probe.barrier();
            }
            for (var step : accepted) {
                var p = step.plan; var trial = step.trial;
                for (var pair : CrushingKinetics.pairs(p.networks)) CrushingKinetics.update(pair.controller());
                for (var dock : p.docks) if (dock.drive() instanceof ContraptionPhysics.Drive drive)
                    PortableDocking.observe(List.of(dock), trial.structureTravel(drive));
                for (var network : p.networks) ElasticKinetics.publishReference(network);
            }
            for (var step : accepted) step.trial.fracture();
            MachineWork.batch(() -> {
                for (var step : accepted) advanceWork(step);
                for (var step : accepted) SequencerKinetics.finishReady(step.plan.networks);
            });
            time = next;
            if (barrier) plans.clear();
            else {
                plans.removeAll(due);
                double deadline = plans.stream().filter(Plan::worldFacing).mapToDouble(Plan::end).min().orElse(RotationMath.TICK_MINUTES);
                if (time < RotationMath.TICK_MINUTES - TIME_EPSILON) for (var p : due)
                    plans.add(plan(p.networks, phases(p.networks), List.of(), time, deadline, hydraulicTorques));
            }
        }
        NetworkTopology.flush();
        SequencerKinetics.sync(networks(level));
        if (fluids != null) FluidKinetics.publish(fluids);
        for (var network : networks(level)) {
            ElasticKinetics.refresh(network);
            ElasticKinetics.publishReference(network);
            ClutchPhysics.accumulateCoupling(network, level);
            var dynamics = (INetworkDynamics) network;
            ClientSpeedSync.syncNetwork(network, level, dynamics, dynamics.differentialcreate$data().balance);
        }
    }
    private static List<Phase> phases(List<KineticNetwork> networks) {
        for (var shaft : KineticReadCache.members(networks).structures) ActorFeed.prepare(shaft);
        var phases = new ArrayList<Phase>();
        for (var binding : MachineWork.collect(networks)) {
            double budget = binding.work().differentialcreate$remainingTravel();
            if (Double.isFinite(budget)) {
                phases.add(new Phase(binding, budget, 0));
            } else {
                int direction = binding.work().differentialcreate$wakeDirection();
                if (direction != 0) phases.add(new Phase(binding, budget, direction));
            }
        }
        return phases;
    }
    private static Plan plan(List<KineticNetwork> networks, List<Phase> phases, List<PortableDocking.Binding> docks,
                         double time, double deadline, Map<KineticBlockEntity, Double> hydraulicTorques) {
        boolean motionBounded = !docks.isEmpty() || phases.stream().anyMatch(p -> p.binding.work() instanceof ContraptionActorWork actor && actor.motionManaged())
            || KineticReadCache.members(networks).structures.stream().anyMatch(ActorFeed::enabled);
        for (var network : networks) ElasticKinetics.refresh(network);
        // A change of direction must be observed before a tool can leave its contact.
        double remaining = deadline - time;
        double horizon = motionBounded ? Math.min(remaining, RotationMath.TICK_MINUTES / 8) : remaining;
        if (SequencerKinetics.needsControl(networks)) horizon = Math.min(horizon, SequencerMotion.CONTROL_INTERVAL);
        // An unassembled controller has no position plant and must not force
        // every unrelated network in this level onto its control interval.
        if (KineticReadCache.members(networks).structures.stream().anyMatch(MachiningClock::needsPositionControl))
            horizon = Math.min(horizon, PositionServo.CONTROL_INTERVAL);
        // Loads and limits stay fixed while accepted steps locate the next physical event.
        // The solver restarts from this capture if a shortened event step needs
        // its conservative path. Callbacks still run once, at the common event time.
        // Idle machinery has no work boundary to locate. Keep ordinary integration
        // (including fracture detection) without preparing event-search probes.
        ElasticKinetics.Trial trial;
        var limits = new ArrayList<ElasticKinetics.WorkEvent>(phases.size());
        for (var phase : phases)
            limits.add(new ElasticKinetics.WorkEvent(phase.binding.shaft(),
                    phase.binding.work().differentialcreate$boundary(phase.remaining), phase.wakeDirection, feedPhase(phase.binding), phase.binding.pair())
                    .withDrive(structureDrive(phase.binding)));
        for (var dock : docks) if (dock.drive() instanceof ContraptionPhysics.Drive drive)
            limits.add(new ElasticKinetics.WorkEvent(drive.shaft(), new ShaftWork.Boundary(Double.POSITIVE_INFINITY, dock.boundary(1), dock.boundary(-1)), 0, ActorFeed.enabled(drive.shaft())).withDrive(drive));
        ActorFeed.boundaries(networks, limits);
        ContraptionMotion.boundaries(networks, limits);
        var snapshot = ElasticKinetics.snapshot(networks, hydraulicTorques);
        SequencerKinetics.boundaries(networks, limits);
        trial = limits.isEmpty() ? snapshot.sample(horizon) : snapshot.advanceToEvent(horizon, limits);
        return new Plan(networks, phases, docks, limits, snapshot, trial, time);
    }
    private static Accepted accept(Plan plan, ElasticKinetics.Trial trial, Map<KineticBlockEntity, Double> hydraulicTorques) {
        double[] advances = new double[plan.phases.size()], signed = new double[plan.phases.size()];
        for (int i = 0; i < plan.phases.size(); i++) {
            var phase = plan.phases.get(i); var drive = structureDrive(phase.binding);
            double travel = phase.binding.pair() != null ? trial.crushingTravel(phase.binding.pair()) : drive != null ? trial.structureAbsTravel(drive) : trial.travel(phase.binding.shaft());
            signed[i] = phase.binding.pair() != null ? trial.crushingSignedTravel(phase.binding.pair()) : drive != null ? trial.structureTravel(drive) : trial.signedTravel(phase.binding.shaft());
            advances[i] = phase.wakeDirection != 0 ? 0 : Math.min(travel,
                phase.binding.work().differentialcreate$phaseLimit(travel, signed[i], phase.remaining));
        }
        trial.commit();
        SequencerKinetics.observe(plan.networks, trial.result.duration(), hydraulicTorques);
        ContraptionMotion.advance(trial);
        ContraptionMotion.resolveStops(plan.networks);
        var probe = observer.get(); if (probe != null) probe.accepted(plan.networks, trial.result.duration());
        return new Accepted(plan, trial, advances, signed);
    }
    private static void advanceWork(Accepted step) {
        for (int i = 0; i < step.plan.phases.size(); i++) {
            var phase = step.plan.phases.get(i);
            if (phase.binding.shaft().isRemoved()) continue;
            if (phase.remaining <= MachineWork.EPSILON) phase.binding.work().differentialcreate$advanceTravel(0);
            else if (step.advances[i] > 0) phase.binding.work().differentialcreate$advanceMotion(step.advances[i], step.signed[i]);
        }
    }
    private static boolean feedPhase(MachineWork.Binding binding) {
        return binding.work() instanceof ContraptionActorWork actor && !MovingDeployerWork.isHand(actor.context())
            && ActorFeed.enabled(binding.shaft());
    }
    private static ContraptionPhysics.Drive structureDrive(MachineWork.Binding binding) {
        return binding.work() instanceof ContraptionActorWork actor && !MovingDeployerWork.isHand(actor.context())
            ? ContraptionPhysics.drive(actor.context()) : null;
    }
}
