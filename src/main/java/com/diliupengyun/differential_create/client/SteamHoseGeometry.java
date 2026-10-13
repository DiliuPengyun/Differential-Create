package com.diliupengyun.differential_create.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared endpoints for the installed hose and the placement preview. */
public final class SteamHoseGeometry {
    public static final double RADIUS = 3 / 32d;
    public record Port(Vec3 point, Vec3 normal) {}
    public record Connection(Port engine, Port tank, List<Vec3> points) {
        public Connection transform(UnaryOperator<Vec3> transform) {
            return new Connection(transformPort(engine, transform), transformPort(tank, transform),
                points.stream().map(transform).toList());
        }
        private static Port transformPort(Port port, UnaryOperator<Vec3> transform) {
            Vec3 point = transform.apply(port.point);
            return new Port(point, transform.apply(port.point.add(port.normal)).subtract(point).normalize());
        }
    }
    private SteamHoseGeometry() {}

    public static Connection connect(BlockState state, Vec3 centre, AABB tank, Vec3 selected) {
        Vec3 side;
        double height = 0, reach = .3;
        if (state.getBlock() instanceof AbstractBogeyBlock<?>) {
            boolean alongZ = state.getValue(AbstractBogeyBlock.AXIS) == Direction.Axis.Z;
            side = alongZ ? new Vec3(1, 0, 0) : new Vec3(0, 0, -1);
            if (tank.getCenter().subtract(centre).dot(side) < 0) side = side.scale(-1);
            // BogeyStyle lowers the native frame by 1.5 blocks. These sockets
            // coincide with the internal engine model's x=+/-9, y=13 ports.
            height = 13 / 16d - 1.5 - 1 / 128d + (AllBlocks.LARGE_BOGEY.has(state) ? .25 : 0);
            reach = 9 / 16d;
        } else {
            Vec3 diff = centre.subtract(tank.getCenter());
            side = Math.abs(diff.x) >= Math.abs(diff.z) ? new Vec3(diff.x > 0 ? 1 : -1, 0, 0)
                : new Vec3(0, 0, diff.z > 0 ? 1 : -1);
        }
        Port from = new Port(centre.add(side.scale(reach)).add(0, height, 0), side);
        double y = Math.clamp(selected.y, tank.minY + .3, tank.maxY - .3);
        Vec3 end = side.x != 0 ? new Vec3(side.x > 0 ? tank.maxX + .015 : tank.minX - .015, y,
                Math.clamp(selected.z, tank.minZ + .3, tank.maxZ - .3))
            : new Vec3(Math.clamp(selected.x, tank.minX + .3, tank.maxX - .3), y,
                side.z > 0 ? tank.maxZ + .015 : tank.minZ - .015);
        return between(from, new Port(end, side));
    }

    public static Connection between(Port from, Port to) {
        double distance = from.point.distanceTo(to.point);
        double bow = Math.clamp(distance * .3, .35, 1.15);
        Vec3 a = from.point.add(from.normal.scale(bow)), b = to.point.add(to.normal.scale(bow));
        int steps = Math.clamp((int) Math.ceil(distance * 8), 16, 64);
        var points = new ArrayList<Vec3>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps, s = 1 - t;
            Vec3 point = from.point.scale(s * s * s).add(a.scale(3 * s * s * t))
                .add(b.scale(3 * s * t * t)).add(to.point.scale(t * t * t));
            points.add(point.add(0, -Math.min(.18, distance * .035) * Math.pow(Math.sin(Math.PI * t), 2), 0));
        }
        return new Connection(from, to, List.copyOf(points));
    }
}
