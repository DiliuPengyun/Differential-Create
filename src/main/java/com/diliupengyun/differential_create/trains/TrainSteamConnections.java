package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;

/** Assembly coordinates identify endpoints; only members of this Train can share steam. */
public final class TrainSteamConnections {
    public record Connection(CarriageSteamState carriage, TrainAssembly.Drive drive) {}
    public static final class Supply {
        public final CarriageSteamState carriage;
        public final TrainAssembly.Boiler boiler;
        public final List<Connection> connections = new ArrayList<>();
        private Supply(CarriageSteamState carriage, TrainAssembly.Boiler boiler) {
            this.carriage = carriage; this.boiler = boiler;
        }
    }
    public static final class Network {
        public final List<Supply> supplies = new ArrayList<>();
        private final Map<CarriageSteamState, Map<BlockPos, Supply>> engines = new IdentityHashMap<>();
        public Supply source(CarriageSteamState carriage, BlockPos motor) {
            return engines.getOrDefault(carriage, Map.of()).get(motor);
        }
    }
    private TrainSteamConnections() {}

    public static Network resolve(Train train) {
        return resolve(train.carriages.stream().map(TrainPhysics::carriage).toList());
    }
    public static Network resolve(List<CarriageSteamState> carriages) {
        var result = new Network();
        var owners = new HashMap<BlockPos, List<Supply>>();
        var local = new IdentityHashMap<CarriageSteamState, Map<BlockPos, Supply>>();
        for (var carriage : carriages) {
            if (carriage.layout == null) continue;
            var own = new HashMap<BlockPos, Supply>(); local.put(carriage, own);
            for (var boiler : carriage.layout.boilers.values()) {
                var supply = new Supply(carriage, boiler); result.supplies.add(supply);
                own.put(boiler.pos(), supply);
                owners.computeIfAbsent(carriage.layout.anchor.offset(boiler.pos()), p -> new ArrayList<>()).add(supply);
            }
        }
        for (var carriage : carriages) {
            var layout = carriage.layout; if (layout == null) continue;
            var bindings = new HashMap<BlockPos, Supply>(); result.engines.put(carriage, bindings);
            layout.resolve(motor -> {
                var supply = local.get(carriage).get(motor.boiler());
                if (supply == null) {
                    var candidates = owners.getOrDefault(layout.anchor.offset(motor.boiler()), List.of());
                    if (candidates.size() == 1) supply = candidates.getFirst();
                }
                if (supply == null) return false;
                bindings.put(motor.pos(), supply); return true;
            });
            for (var drive : layout.drives) {
                carriage.engines.computeIfAbsent(drive.motor().pos(), p -> new TrainMechanics.Engine());
                bindings.get(drive.motor().pos()).connections.add(new Connection(carriage, drive));
            }
        }
        return result;
    }
}
