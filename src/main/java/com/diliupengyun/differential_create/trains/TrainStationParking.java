package com.diliupengyun.differential_create.trains;

import java.util.Objects;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Recover lost station metadata at the assembly pad without moving the train. */
public final class TrainStationParking {
    private TrainStationParking() {}

    public static boolean recover(StationBlockEntity block) {
        var level = block.getLevel();
        if (level == null || level.isClientSide || block.edgePoint.isOnCurve() || !block.edgePoint.isOrthogonal()
            || !block.edgePoint.hasValidTrack()) return false;
        GlobalStation station = block.getStation();
        if (station == null || station.getPresentTrain() != null) return false;
        var direction = block.getAssemblyDirection();
        if (direction == null) return false;
        var trackPos = block.edgePoint.getGlobalPosition();
        var track = block.edgePoint.getTrack();
        var trackState = block.edgePoint.getTrackBlockState();
        Vec3 pad = Vec3.atBottomCenterOf(trackPos)
            .add(0, track.getElevationAtCenter(level, trackPos, trackState), 0)
            .add(Vec3.atLowerCornerOf(direction.getNormal()));
        Vec3 normal = track.getUpNormal(level, trackPos, trackState);
        Vec3 towardStation = Vec3.atLowerCornerOf(direction.getOpposite().getNormal());
        Train candidate = null;
        for (var train : Create.RAILWAYS.sided(level).trains.values()) {
            if (train.currentStation != null || train.derailed || !Double.isFinite(train.speed) || Math.abs(train.speed) > 1e-6
                || train.graph == null || train.graph.getPoint(EdgePointType.STATION, station.id) != station
                || train.carriages.isEmpty() || !train.canDisassemble()) continue;
            boolean sameDimension = true;
            for (var carriage : train.carriages) {
                var entity = carriage.anyAvailableEntity();
                if (entity == null || entity.level() != level) { sameDimension = false; break; }
            }
            if (!sameDimension) continue;
            var carriage = train.currentlyBackwards ? train.carriages.getLast() : train.carriages.getFirst();
            var bogey = train.currentlyBackwards ? carriage.trailingBogey() : carriage.leadingBogey();
            if (!Objects.equals(bogey.getDimension(), level.dimension())) continue;
            Vec3 anchor = bogey.getAnchorPosition();
            Vec3 expected = pad.add(normal.scale(bogey.isUpsideDown() ? -1 : 1));
            if (anchor == null || anchor.distanceToSqr(expected) > 1d / 64) continue;
            Vec3 facing = bogey.leading().getPosition(train.graph).subtract(bogey.trailing().getPosition(train.graph)).normalize();
            if (train.currentlyBackwards) facing = facing.scale(-1);
            if (facing.dot(towardStation) < .999) continue;
            // An overlapping second train is ambiguous; never choose by map order.
            if (candidate != null) return false;
            candidate = train;
        }
        if (candidate == null || station.getNearestTrain() != null && station.getNearestTrain() != candidate) return false;
        candidate.setCurrentStation(station);
        station.reserveFor(candidate);
        Create.RAILWAYS.markTracksDirty();
        return station.getPresentTrain() == candidate;
    }
}
