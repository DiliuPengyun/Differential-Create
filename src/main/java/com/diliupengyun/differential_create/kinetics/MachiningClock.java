package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.mixin.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.world.level.Level;

/** All rotors advance on one clock, stopping at recipe boundaries and actual fracture events. */
public final class MachiningClock {
    private MachiningClock() {}
    private record Phase(MachineWork.Binding binding, double remaining) {}
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
            boolean immediate = false;
            for (var binding : MachineWork.collect(networks)) {
                double budget = binding.work().differentialcreate$remainingTravel();
                if (budget <= MachineWork.EPSILON) {
                    binding.work().differentialcreate$advanceTravel(0); immediate = true;
                } else if (Double.isFinite(budget)) phases.add(new Phase(binding, budget));
            }
            if (++transitions > 16384) throw new IllegalStateException("Physical events did not converge in " + level.dimension().location());
            if (immediate) continue;
            for (var network : networks) ElasticKinetics.refresh(network);
            // A change of direction must be observed before a tool can leave its contact.
            double horizon = phases.stream().anyMatch(p -> p.binding.work() instanceof ContraptionActorWork actor && actor.motionManaged())
                ? Math.min(remaining, RotationMath.TICK_MINUTES / 8) : remaining;
            // Trial durations share the same starting state and coefficients until a physical event commits.
            var snapshot = ElasticKinetics.snapshot(networks);
            var trial = snapshot.sample(horizon);
            double duration = trial.result.duration();
            boolean boundary = false;
            for (var phase : phases)
                if (reached(trial, phase)) { boundary = true; break; }
            if (boundary) {
                // Sliding contacts and short final steps can make the numerical
                // travel function non-monotone even without a physical reversal.
                // Preserve the original bisection order and completion predicate.
                duration = ShaftClock.firstBoundary(duration, minutes -> {
                    var sample = snapshot.motion(minutes);
                    for (var phase : phases)
                        if (reached(sample, phase)) return true;
                    return false;
                });
                trial = snapshot.sample(duration);
            }
            double[] advances = new double[phases.size()];
            double[] trialSignedTravel = new double[phases.size()];
            for (int i = 0; i < phases.size(); i++) {
                var phase = phases.get(i);
                double travel = trial.travel(phase.binding.shaft());
                advances[i] = Math.min(travel, phase.binding.work().differentialcreate$phaseLimit(travel,
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
    private static boolean reached(ElasticKinetics.Travel trial, Phase phase) {
        double travel = trial.travel(phase.binding.shaft());
        return travel >= phase.binding.work().differentialcreate$phaseLimit(travel,
            trial.signedTravel(phase.binding.shaft()), phase.remaining);
    }
}
