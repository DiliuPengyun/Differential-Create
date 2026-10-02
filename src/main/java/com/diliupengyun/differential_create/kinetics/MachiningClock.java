package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
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
        for (var network : networks(level)) ClutchPhysics.prepare(network);
        ContraptionMotion.begin(level);
        try { advanceRotors(level); }
        finally { ContraptionMotion.end(level); }
    }
    private static void advanceRotors(Level level) {
        double remaining = RotationMath.TICK_MINUTES;
        int transitions = 0;
        while (remaining > 1e-14) {
            NetworkTopology.flush();
            var networks = networks(level);
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
            for (var network : networks) ElasticKinetics.refresh(network);
            // A change of direction must be observed before a tool can leave its contact.
            double horizon = motionBounded ? Math.min(remaining, RotationMath.TICK_MINUTES / 8) : remaining;
            // Loads and limits stay fixed while accepted steps locate the next physical event.
            var snapshot = ElasticKinetics.snapshot(networks);
            // The solver restarts from this capture if a shortened event step needs
            // its conservative path. Callbacks still run once, at the common event time.
            // Idle machinery has no work boundary to locate. Keep ordinary integration
            // (including fracture detection) without preparing event-search probes.
            ElasticKinetics.Trial trial;
            if (phases.isEmpty()) trial = snapshot.sample(horizon);
            else {
                var limits = new ArrayList<ElasticKinetics.WorkEvent>(phases.size());
                for (var phase : phases)
                    limits.add(new ElasticKinetics.WorkEvent(phase.binding.shaft(),
                        phase.binding.work().differentialcreate$boundary(phase.remaining), phase.wakeDirection));
                trial = snapshot.advanceToEvent(horizon, limits);
            }
            double[] advances = new double[phases.size()];
            double[] trialSignedTravel = new double[phases.size()];
            for (int i = 0; i < phases.size(); i++) {
                var phase = phases.get(i);
                double travel = trial.travel(phase.binding.shaft());
                advances[i] = phase.wakeDirection != 0 ? 0 : Math.min(travel,
                    phase.binding.work().differentialcreate$phaseLimit(travel,
                        trial.signedTravel(phase.binding.shaft()), phase.remaining));
                trialSignedTravel[i] = trial.signedTravel(phases.get(i).binding.shaft());
            }
            trial.commit();
            ContraptionMotion.advance(trial);
            for (var network : networks) ElasticKinetics.publishReference(network);
            trial.fracture();
            MachineWork.batch(() -> {
                for (int i = 0; i < phases.size(); i++)
                    if (advances[i] > 0 && !phases.get(i).binding.shaft().isRemoved())
                        phases.get(i).binding.work().differentialcreate$advanceMotion(advances[i], trialSignedTravel[i]);
            });
            remaining = Math.max(0, remaining - trial.result.duration());
        }
        NetworkTopology.flush();
        for (var network : networks(level)) {
            ElasticKinetics.refresh(network);
            ElasticKinetics.publishReference(network);
            ClutchPhysics.accumulateCoupling(network, level);
            var dynamics = (INetworkDynamics) network;
            dynamics.differentialcreate$setTargetSpeed(NetworkDynamics.computeTargetSpeed(network));
            ClientSpeedSync.syncNetwork(network, level, dynamics, dynamics.differentialcreate$data().balance);
        }
    }
}
