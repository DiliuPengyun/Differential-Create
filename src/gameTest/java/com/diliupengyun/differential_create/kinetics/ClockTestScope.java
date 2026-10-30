package com.diliupengyun.differential_create.kinetics;

import java.util.IdentityHashMap;
import java.util.List;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/** Observe accepted time, not speculative solver probes. Never packaged in the mod. */
public final class ClockTestScope implements AutoCloseable {
    private final MachiningClock.Observer previous = MachiningClock.observer.get();
    private final IdentityHashMap<KineticBlockEntity, Integer> steps = new IdentityHashMap<>();
    private final IdentityHashMap<KineticBlockEntity, Double> times = new IdentityHashMap<>();
    private final IdentityHashMap<KineticBlockEntity, Double> maximum = new IdentityHashMap<>();
    private int barriers;
    public ClockTestScope() {
        MachiningClock.observer.set(new MachiningClock.Observer() {
            @Override public void accepted(List<KineticNetwork> networks, double duration) {
                for (var network : networks) for (var be : network.members.keySet()) {
                    steps.merge(be, 1, Integer::sum); times.merge(be, duration, Double::sum);
                    maximum.merge(be, duration, Math::max);
                }
            }
            @Override public void barrier() { barriers++; }
        });
    }
    public int steps(KineticBlockEntity be) { return steps.getOrDefault(be, 0); }
    public double time(KineticBlockEntity be) { return times.getOrDefault(be, 0d); }
    public double maximum(KineticBlockEntity be) { return maximum.getOrDefault(be, 0d); }
    public int barriers() { return barriers; }
    @Override public void close() {
        if (previous == null) MachiningClock.observer.remove(); else MachiningClock.observer.set(previous);
    }
}
