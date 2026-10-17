package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.mixin.ChainConveyorAccessor;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Event-ordered native transport. Shaft/cargo mass and native speed sampling are unchanged. */
public final class ChainConveyorTransport {
    private ChainConveyorTransport() {}
    private record Location(ChainConveyorPackage box, ChainConveyorBlockEntity owner, BlockPos connection) {}
    private record Event(double distance, BlockPos offset, boolean branch) {}
    private record Port(BlockPos conveyor, BlockPos port) {}
    private record Visit(BlockPos conveyor, BlockPos connection, double position) {}

    public static void tick(ChainConveyorBlockEntity conveyor) {
        var level = conveyor.getLevel();
        conveyor.prepareStats();
        if (level.isClientSide) {
            if (!VisualizationManager.supportsVisualization(level)) conveyor.tickBoxVisuals();
        } else {
            conveyor.routingTable.tick();
            if (conveyor.routingTable.shouldAdvertise()) {
                for (BlockPos offset : conveyor.connections) {
                    var other = other(conveyor, offset);
                    if (other != null) conveyor.routingTable.advertiseTo(offset, other.routingTable);
                }
                conveyor.routingTable.changed = false;
                conveyor.routingTable.lastUpdate = 0;
            }
        }
        var parcels = new ArrayList<Location>();
        for (var box : conveyor.getLoopingPackages()) parcels.add(new Location(box, conveyor, null));
        conveyor.getTravellingPackages().forEach((connection, boxes) -> {
            for (var box : boxes) parcels.add(new Location(box, conveyor, connection));
        });
        var changed = new LinkedHashSet<ChainConveyorBlockEntity>();
        for (var location : parcels) {
            var state = ((IChainPackageMotion) location.box).differentialcreate$motion();
            if (!conveyor.isVirtual() && state.tick == level.getGameTime()) continue;
            state.tick = level.getGameTime();
            location.box.justFlipped = false;
            if (level.isClientSide && !conveyor.isVirtual()) predict(location, changed);
            else advance(location, changed);
        }
        conveyor.updateBoxWorldPositions();
        for (var owner : changed) {
            owner.updateBoxWorldPositions();
            owner.notifyUpdate();
        }
        if (!parcels.isEmpty()) conveyor.setChanged();
    }

    private static ChainConveyorBlockEntity other(ChainConveyorBlockEntity owner, BlockPos offset) {
        var level = owner.getLevel();
        var pos = owner.getBlockPos().offset(offset);
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ChainConveyorBlockEntity other ? other : null;
    }
    private static double rpm(ChainConveyorBlockEntity owner) {
        double value = owner.getSpeed();
        return Double.isFinite(value) ? value : 0;
    }
    private static void remove(Location at) {
        if (at.connection == null) at.owner.getLoopingPackages().remove(at.box);
        else {
            var boxes = at.owner.getTravellingPackages().get(at.connection);
            if (boxes != null) boxes.remove(at.box);
        }
    }
    private static Location reverse(Location at, Set<ChainConveyorBlockEntity> changed) {
        if (at.connection == null || rpm(at.owner) == 0) return at;
        var motion = ((IChainPackageMotion) at.box).differentialcreate$motion();
        if (!motion.directionKnown) { motion.directionKnown = true; motion.reversed = at.owner.reversed; }
        if (motion.reversed == at.owner.reversed) return at;
        var destination = other(at.owner, at.connection);
        var stats = at.owner.connectionStats.get(at.connection);
        if (destination == null || stats == null || !destination.connections.contains(at.connection.multiply(-1))) return at;
        destination.prepareStats();
        remove(at);
        at.box.chainPosition = Math.max(0, stats.chainLength() - at.box.chainPosition);
        destination.addTravellingPackage(at.box, at.connection.multiply(-1));
        at.box.justFlipped = true;
        changed.add(at.owner); changed.add(destination);
        return new Location(at.box, destination, at.connection.multiply(-1));
    }
    private static void predict(Location at, Set<ChainConveyorBlockEntity> changed) {
        at = reverse(at, changed);
        double speed = rpm(at.owner), factor = ServerSpeedProvider.get();
        if (speed == 0 || !Double.isFinite(factor) || factor <= 0) return;
        if (at.connection == null)
            at.box.chainPosition = (float) ChainConveyorTravel.wrap(at.box.chainPosition + ChainConveyorTravel.degrees(speed) * factor);
        else {
            var stats = at.owner.connectionStats.get(at.connection);
            if (stats != null) at.box.chainPosition = (float) Math.min(stats.chainLength(), at.box.chainPosition + ChainConveyorTravel.distance(speed) * factor);
        }
    }

    private static void advance(Location initial, Set<ChainConveyorBlockEntity> changed) {
        Location at = reverse(initial, changed);
        double horizon = 1, elapsed = 0, remaining = 1;
        var rejected = new HashSet<Port>();
        var visits = new HashMap<Visit, Double>();
        while (remaining > 0) {
            var owner = at.owner;
            owner.prepareStats();
            double speed = rpm(owner);
            if (speed == 0) break;
            double position = at.box.chainPosition;
            if (!Double.isFinite(position)) break;
            var visit = new Visit(owner.getBlockPos(), at.connection, position);
            Double previous = visits.putIfAbsent(visit, elapsed);
            if (previous != null && elapsed > previous) {
                // No receiver accepted the parcel on this complete circuit. All
                // routing/receiver inputs stay fixed during this parcel's step.
                horizon = remaining % (elapsed - previous);
                elapsed = 0; remaining = horizon;
                visits.clear();
                if (remaining == 0) break;
            }
            int direction = speed > 0 ? 1 : -1;
            if (at.connection == null) {
                double rate = Math.abs(ChainConveyorTravel.degrees(speed));
                Event event = null;
                for (var entry : owner.loopPorts.entrySet()) {
                    var port = entry.getValue();
                    if (rejected.contains(new Port(owner.getBlockPos(), entry.getKey())) || !PackageItem.matchAddress(at.box.item, port.filter())) continue;
                    event = earlier(event, new Event(ChainConveyorTravel.toAngle(position, port.chainPosition(), direction), entry.getKey(), false));
                }
                BlockPos exit = owner.routingTable.getExitFor(at.box.item);
                if (owner.connections.contains(exit)) {
                    var target = other(owner, exit);
                    var stats = owner.connectionStats.get(exit);
                    if (target != null && stats != null && target.canAcceptMorePackagesFromOtherConveyor())
                        event = earlier(event, new Event(ChainConveyorTravel.toAngle(position, stats.tangentAngle(), direction), exit, true));
                }
                if (event == null || event.distance > rate * remaining) {
                    at.box.chainPosition = (float) ChainConveyorTravel.wrap(position + direction * rate * remaining);
                    anticipate(at, rate * 4, direction);
                    break;
                }
                elapsed += event.distance / rate;
                remaining = Math.max(0, horizon - elapsed);
                at.box.chainPosition = (float) ChainConveyorTravel.wrap(position + direction * event.distance);
                if (!event.branch) {
                    if (((ChainConveyorAccessor) owner).differentialcreate$export(at.box, event.offset)) {
                        remove(at); changed.add(owner); return;
                    }
                    rejected.add(new Port(owner.getBlockPos(), event.offset));
                    continue;
                }
                remove(at); at.box.chainPosition = 0;
                owner.addTravellingPackage(at.box, event.offset); changed.add(owner);
                at = new Location(at.box, owner, event.offset);
            } else {
                var stats = owner.connectionStats.get(at.connection);
                if (stats == null || !Float.isFinite(stats.chainLength()) || stats.chainLength() <= 0) break;
                double rate = ChainConveyorTravel.distance(speed);
                double toEnd = Math.max(0, stats.chainLength() - position);
                Event event = null;
                for (var entry : owner.travelPorts.entrySet()) {
                    var port = entry.getValue();
                    if (!at.connection.equals(port.connection()) || port.chainPosition() < position || port.chainPosition() > stats.chainLength()
                        || rejected.contains(new Port(owner.getBlockPos(), entry.getKey())) || !PackageItem.matchAddress(at.box.item, port.filter())) continue;
                    event = earlier(event, new Event(port.chainPosition() - position, entry.getKey(), false));
                }
                double distance = event == null ? toEnd : event.distance;
                if (distance > rate * remaining) {
                    at.box.chainPosition = (float) Math.min(stats.chainLength(), position + rate * remaining);
                    anticipate(at, rate * 4, direction);
                    break;
                }
                elapsed += distance / rate;
                remaining = Math.max(0, horizon - elapsed);
                at.box.chainPosition = (float) Math.min(stats.chainLength(), position + distance);
                if (event != null) {
                    if (((ChainConveyorAccessor) owner).differentialcreate$export(at.box, event.offset)) {
                        remove(at); changed.add(owner); return;
                    }
                    rejected.add(new Port(owner.getBlockPos(), event.offset));
                    continue;
                }
                var destination = other(owner, at.connection);
                if (destination == null || !destination.canAcceptMorePackagesFromOtherConveyor()) break;
                destination.prepareStats();
                remove(at);
                at.box.chainPosition = (float) ChainConveyorTravel.wrap(stats.tangentAngle() + 180 + 70 * (owner.reversed ? -1 : 1));
                destination.addLoopingPackage(at.box);
                changed.add(owner); changed.add(destination);
                at = new Location(at.box, destination, null);
            }
        }
    }
    private static Event earlier(Event a, Event b) {
        if (a == null || b.distance < a.distance) return b;
        if (b.distance > a.distance) return a;
        if (a.branch != b.branch) return a.branch ? b : a;
        return b.offset.compareTo(a.offset) < 0 ? b : a;
    }
    private static void anticipate(Location at, double travel, int direction) {
        var ports = at.connection == null ? at.owner.loopPorts : at.owner.travelPorts;
        for (var entry : ports.entrySet()) {
            var port = entry.getValue();
            if (!PackageItem.matchAddress(at.box.item, port.filter())) continue;
            double distance;
            if (at.connection == null) distance = ChainConveyorTravel.toAngle(at.box.chainPosition, port.chainPosition(), direction);
            else {
                if (!at.connection.equals(port.connection()) || port.chainPosition() < at.box.chainPosition) continue;
                distance = port.chainPosition() - at.box.chainPosition;
            }
            if (distance <= travel) ((ChainConveyorAccessor) at.owner).differentialcreate$anticipate(entry.getKey());
        }
    }
}
