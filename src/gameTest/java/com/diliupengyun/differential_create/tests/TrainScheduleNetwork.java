package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.UUID;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBlock.SignalType;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Native graph fixtures. Group occupancy is maintained by the railway manager. */
final class TrainScheduleNetwork {
    record Node(DiscoveredLocation location, TrackNode node) {}
    final TrainScheduleScenario s;
    final ArrayList<SignalBoundary> signals = new ArrayList<>();
    TrainScheduleNetwork(TrainScheduleScenario scenario) { s = scenario; }
    Node node(Vec3 position) {
        var location = new DiscoveredLocation(s.h.getLevel(), position).withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
        s.train().graph.createNodeIfAbsent(location); return new Node(location, s.train().graph.locateNode(location));
    }
    void connect(Node a, Node b) { s.train().graph.connectNodes(s.h.getLevel(), a.location, b.location, null); }
    SignalEdgeGroup group() {
        var group = new SignalEdgeGroup(UUID.randomUUID()); Create.RAILWAYS.signalEdgeGroups.put(group.id, group);
        s.cleanup.add(() -> Create.RAILWAYS.signalEdgeGroups.remove(group.id)); return group;
    }
    SignalBoundary signal(TrackNode from, TrackNode to, double position, SignalEdgeGroup before, SignalEdgeGroup after, boolean chain, boolean forced) {
        var signal = new SignalBoundary(); signal.setType(EdgePointType.SIGNAL); signal.setId(UUID.randomUUID());
        signal.setLocation(Couple.create(from.getLocation(), to.getLocation()), position);
        signal.blockEntities.getFirst().put(s.h.absolutePos(BlockPos.ZERO), forced);
        signal.types.set(true, chain ? SignalType.CROSS_SIGNAL : SignalType.ENTRY_SIGNAL);
        signal.setGroup(false, before.id); signal.setGroup(true, after.id);
        s.train().graph.addPoint(EdgePointType.SIGNAL, signal); signals.add(signal); seal(); return signal;
    }
    void seal() { signals.forEach(signal -> signal.sidesToUpdate.replace($ -> false)); s.train().updateSignalBlocks = true; }
    GlobalStation station(TrackNode from, TrackNode to, double position, String name) {
        var station = new GlobalStation(); station.setType(EdgePointType.STATION); station.setId(UUID.randomUUID());
        station.name = name; station.blockEntityPos = s.h.absolutePos(BlockPos.ZERO); station.blockEntityDimension = s.h.getLevel().dimension();
        station.setLocation(Couple.create(from.getLocation(), to.getLocation()), position); s.train().graph.addPoint(EdgePointType.STATION, station); return station;
    }
}
