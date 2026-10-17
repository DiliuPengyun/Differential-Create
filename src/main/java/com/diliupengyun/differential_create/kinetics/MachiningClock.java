package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.world.level.Level;

/** All rotors advance on one clock, stopping at recipe boundaries and actual fracture events. */
public final class MachiningClock {
    private MachiningClock() {}
    private record Phase(MachineWork.Binding binding, double remaining, int wakeDirection) {}
    public static List<KineticNetwork> networks(Level level) {
        var registry = TorquePropagatorAccessor.differentialcreate$getNetworks().get(level);
        return registry == null ? List.of() : new ArrayList<>(registry.values());
    }
    public static void advance(Level level) {
        com.diliupengyun.differential_create.kinetics.portable.PortableDocking.time(level);
        if (level.tickRateManager().isFrozen()) return;
        for (var network : networks(level)) ClutchPhysics.prepare(network);
        ContraptionMotion.begin(level);
        try { advanceRotors(level); }
        finally { ContraptionMotion.end(level); }
    }
    private static void advanceRotors(Level level) {
        FluidKinetics.beginTick(level);
        double remaining = RotationMath.TICK_MINUTES;
        int transitions = 0;
        NetworkTopology.flush();
        for (var network : networks(level)) ElasticKinetics.refresh(network);
        FluidKinetics.Frame fluids = FluidKinetics.hasPipes(level) ? FluidKinetics.capture(level) : null;
        Map<KineticBlockEntity, Double> hydraulicTorques = fluids == null ? Map.of()
            : FluidKinetics.advance(fluids, RotationMath.TICK_MINUTES);
        while (remaining > 1e-14) {
            NetworkTopology.flush();
            var networks = networks(level);
            if (SequencerKinetics.finishReady(networks)) continue;
            for (var network : networks) for (var shaft : network.members.keySet()) ActorFeed.prepare(shaft);
            var phases = new ArrayList<Phase>();
            boolean immediate = false, motionBounded = false;
            for (var binding : MachineWork.collect(networks)) {
                double budget = binding.work().differentialcreate$remainingTravel();
                if (budget <= MachineWork.EPSILON) {
                    binding.work().differentialcreate$advanceTravel(0); immediate = true;
                } else if (Double.isFinite(budget)) {
                    phases.add(new Phase(binding, budget, 0));
                    if (binding.work() instanceof ContraptionActorWork actor && actor.motionManaged()) motionBounded = true;
                } else {
                    int direction = binding.work().differentialcreate$wakeDirection();
                    if (direction != 0) phases.add(new Phase(binding, budget, direction));
                }
            }
            if (++transitions > 16384) throw new IllegalStateException("Physical events did not converge in " + level.dimension().location());
            if (immediate) continue;
            var docks = com.diliupengyun.differential_create.kinetics.portable.PortableDocking.prepare(networks);
            motionBounded |= !docks.isEmpty();
            motionBounded |= networks.stream().anyMatch(n -> n.members.keySet().stream().anyMatch(ActorFeed::enabled));
            for (var network : networks) ElasticKinetics.refresh(network);
            // A change of direction must be observed before a tool can leave its contact.
            double horizon = motionBounded ? Math.min(remaining, RotationMath.TICK_MINUTES / 8) : remaining;
            if (SequencerKinetics.needsControl(networks)) horizon = Math.min(horizon, SequencerMotion.CONTROL_INTERVAL);
            if (fluids != null && !fluids.valid()) fluids = FluidKinetics.capture(level);
            // Loads and limits stay fixed while accepted steps locate the next physical event.
            // The solver restarts from this capture if a shortened event step needs
            // its conservative path. Callbacks still run once, at the common event time.
            // Idle machinery has no work boundary to locate. Keep ordinary integration
            // (including fracture detection) without preparing event-search probes.
            ElasticKinetics.Trial trial;
            var limits = new ArrayList<ElasticKinetics.WorkEvent>(phases.size());
            for (var phase : phases)
                limits.add(new ElasticKinetics.WorkEvent(phase.binding.shaft(),
                    phase.binding.work().differentialcreate$boundary(phase.remaining), phase.wakeDirection, feedPhase(phase.binding), phase.binding.pair()));
            for (var dock : docks) if (dock.drive() instanceof ContraptionPhysics.Drive drive)
                limits.add(new ElasticKinetics.WorkEvent(drive.shaft(), new ShaftWork.Boundary(Double.POSITIVE_INFINITY, dock.boundary(1), dock.boundary(-1)), 0, ActorFeed.enabled(drive.shaft())));
            ActorFeed.boundaries(networks, limits);
            var snapshot = ElasticKinetics.snapshot(networks, hydraulicTorques);
            snapshot.setExternalTorques(hydraulicTorques);
            SequencerKinetics.boundaries(networks, limits);
            trial = limits.isEmpty() ? snapshot.sample(horizon) : snapshot.advanceToEvent(horizon, limits);
            double[] advances = new double[phases.size()];
            double[] trialSignedTravel = new double[phases.size()];
            for (int i = 0; i < phases.size(); i++) {
                var phase = phases.get(i);
                double travel = phase.binding.pair() != null ? trial.crushingTravel(phase.binding.pair()) : feedPhase(phase.binding) ? trial.outputAbsTravel(phase.binding.shaft()) : trial.travel(phase.binding.shaft());
                double signed = phase.binding.pair() != null ? trial.crushingSignedTravel(phase.binding.pair()) : feedPhase(phase.binding) ? trial.outputTravel(phase.binding.shaft()) : trial.signedTravel(phase.binding.shaft());
                advances[i] = phase.wakeDirection != 0 ? 0 : Math.min(travel,
                    phase.binding.work().differentialcreate$phaseLimit(travel,
                        signed, phase.remaining));
                trialSignedTravel[i] = signed;
            }
            trial.commit();
            for (var pair : CrushingKinetics.pairs(networks)) CrushingKinetics.update(pair.controller());
            SequencerKinetics.observe(networks, trial.result.duration(), hydraulicTorques);
            ContraptionMotion.advance(trial);
            for (var dock : docks) if (dock.drive() instanceof ContraptionPhysics.Drive drive)
                com.diliupengyun.differential_create.kinetics.portable.PortableDocking.observe(List.of(dock), ActorFeed.enabled(drive.shaft()) ? trial.outputTravel(drive.shaft()) : trial.signedTravel(drive.shaft()));
            for (var network : networks) ElasticKinetics.publishReference(network);
            trial.fracture();
            MachineWork.batch(() -> {
                for (int i = 0; i < phases.size(); i++)
                    if (advances[i] > 0 && !phases.get(i).binding.shaft().isRemoved())
                        phases.get(i).binding.work().differentialcreate$advanceMotion(advances[i], trialSignedTravel[i]);
                SequencerKinetics.finishReady(networks);
            });
            remaining = Math.max(0, remaining - trial.result.duration());
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
    private static boolean feedPhase(MachineWork.Binding binding) {
        return binding.work() instanceof ContraptionActorWork actor && !MovingDeployerWork.isHand(actor.context())
            && ActorFeed.enabled(binding.shaft());
    }
}
