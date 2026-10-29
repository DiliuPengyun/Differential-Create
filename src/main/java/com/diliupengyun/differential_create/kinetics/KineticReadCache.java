package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/** Structural reads shared until a world event or the end of this tick. Never cache velocity,
 * position, contact forces or controller commands across accepted motion. */
final class KineticReadCache implements AutoCloseable {
    static final class Entry {
        List<ContraptionPhysics.Drive> drives;
        List<ClockworkMotion.Output> clockOutputs;
        List<CrushingKinetics.Pair> crusherPairs;
        SourcePhysics.Curve source;
        boolean feedKnown, feedEnabled;
        float inertia = Float.NaN;
    }

    record Sequencer(KineticBlockEntity shaft, KineticNetwork network) {}
    static final class Members {
        final List<KineticNetwork> networks;
        final List<KineticNetwork> structureNetworks = new ArrayList<>();
        final List<KineticBlockEntity> structures = new ArrayList<>();
        final List<Sequencer> sequencers = new ArrayList<>();
        List<CrushingKinetics.Pair> crushers;
        Members(List<KineticNetwork> networks) {
            this.networks = List.copyOf(networks);
            for (var network : networks) {
                boolean hasStructure = false;
                for (var be : network.members.keySet()) {
                    if (ContraptionPhysics.manages(be)) { structures.add(be); hasStructure = true; }
                    if (be instanceof ISequencerDynamics) sequencers.add(new Sequencer(be, network));
                }
                if (hasStructure) structureNetworks.add(network);
            }
        }
    }

    private static final ThreadLocal<KineticReadCache> current = new ThreadLocal<>();
    private final IdentityHashMap<KineticBlockEntity, Entry> entries = new IdentityHashMap<>();
    private final KineticReadCache previous;
    ElasticKinetics.Layout layout;
    private Members members;
    FluidKinetics.Frame validFluids;
    List<KineticNetwork> workNetworks;
    List<MachineWork.Binding> work;

    private KineticReadCache() { previous = current.get(); current.set(this); }
    static KineticReadCache open() { return new KineticReadCache(); }
    static KineticReadCache current() { return current.get(); }
    static Entry of(KineticBlockEntity be) {
        var cache = current.get();
        if (cache == null) return null;
        var entry = cache.entries.get(be);
        if (entry == null) { entry = new Entry(); cache.entries.put(be, entry); }
        return entry;
    }
    static Members members(List<KineticNetwork> networks) {
        var cache = current.get();
        if (cache != null && cache.members != null && networks.equals(cache.members.networks)) return cache.members;
        var members = new Members(networks);
        if (cache != null) cache.members = members;
        return members;
    }
    static void invalidateFluids() {
        var cache = current.get();
        if (cache != null) cache.validFluids = null;
    }
    static void invalidate(KineticBlockEntity be) {
        var cache = current.get();
        if (cache != null) { cache.entries.remove(be); cache.invalidateStructure(); }
    }
    static void clear() {
        var cache = current.get();
        if (cache != null) { cache.entries.clear(); cache.invalidateStructure(); }
    }
    private void invalidateStructure() { layout = null; work = null; members = null; validFluids = null; }
    @Override public void close() {
        if (previous == null) current.remove(); else current.set(previous);
    }
}
