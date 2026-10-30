package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/** Solver islands include material contacts, not just Create's network ids.
 * World callbacks still synchronize every island before they can alter this graph. */
final class KineticIslands {
    private KineticIslands() {}
    static List<List<KineticNetwork>> collect(List<KineticNetwork> networks) {
        var owner = new IdentityHashMap<KineticBlockEntity, Integer>();
        int[] parents = new int[networks.size()];
        for (int i = 0; i < networks.size(); i++) {
            parents[i] = i;
            for (var be : networks.get(i).members.keySet()) {
                // An unknown machine may read another shaft from its source/load
                // getter. Preserve the common clock until its coupling is known.
                if (!be.getClass().getName().startsWith("com.simibubi.create.")
                    && !(be instanceof com.diliupengyun.differential_create.content.BrassClutchBlockEntity))
                    return List.of(List.copyOf(networks));
                owner.put(be, i);
            }
        }
        for (var network : networks) {
            for (var edge : ((INetworkDynamics) network).differentialcreate$data().connections)
                join(parents, owner.get(edge.a), owner.get(edge.b));
            for (var be : network.members.keySet()) {
                var work = MachineWork.of(be);
                if (work != null) join(parents, owner.get(be), owner.get(work.differentialcreate$workShaft(be)));
            }
        }
        for (var pair : CrushingKinetics.pairs(networks)) join(parents, owner.get(pair.a()), owner.get(pair.b()));
        var groups = new LinkedHashMap<Integer, List<KineticNetwork>>();
        for (int i = 0; i < networks.size(); i++)
            groups.computeIfAbsent(root(parents, i), key -> new ArrayList<>()).add(networks.get(i));
        return groups.values().stream().map(List::copyOf).toList();
    }
    private static int root(int[] parents, int i) {
        while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i]; }
        return i;
    }
    private static void join(int[] parents, Integer a, Integer b) {
        if (a != null && b != null) parents[root(parents, b)] = root(parents, a);
    }
}
