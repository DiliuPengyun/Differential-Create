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
            var trial = ElasticKinetics.sample(networks, remaining);
            double duration = trial.result.duration();
            boolean boundary = false;
            for (var phase : phases)
                if (trial.travel(phase.binding.shaft()) >= phase.remaining) { boundary = true; break; }
            if (boundary) {
                duration = ShaftClock.firstBoundary(duration, minutes -> {
                    var sample = ElasticKinetics.sample(networks, minutes);
                    for (var phase : phases)
                        if (sample.travel(phase.binding.shaft()) >= phase.remaining) return true;
                    return false;
                });
                trial = ElasticKinetics.sample(networks, duration);
            }
            trial.commit();
            for (var network : networks) ElasticKinetics.publishReference(network);
            double[] advances = new double[phases.size()];
            for (int i = 0; i < phases.size(); i++)
                advances[i] = Math.min(phases.get(i).remaining, trial.travel(phases.get(i).binding.shaft()));
            trial.fracture();
            MachineWork.batch(() -> {
                for (int i = 0; i < phases.size(); i++)
                    if (advances[i] > 0 && !phases.get(i).binding.shaft().isRemoved())
                        phases.get(i).binding.work().differentialcreate$advanceTravel(advances[i]);
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
