package com.diliupengyun.differential_create.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import com.diliupengyun.differential_create.trains.SteamHosePort;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared endpoints for the installed hose and the placement preview. */
public final class SteamHoseGeometry {
    public static final double RADIUS = 3 / 32d;
    public record Port(Vec3 point, Vec3 normal) {
        public Port transform(UnaryOperator<Vec3> transform) {
            Vec3 at = transform.apply(point);
            return new Port(at, transform.apply(point.add(normal)).subtract(at).normalize());
        }
    }
    public record Connection(Port engine, Port tank, List<Vec3> points) {
        public Connection transform(UnaryOperator<Vec3> transform) {
            return new Connection(engine.transform(transform), tank.transform(transform),
                points.stream().map(transform).toList());
        }
    }
    private SteamHoseGeometry() {}

    public static Connection connect(BlockState state, Vec3 centre, AABB tank, Vec3 selected, SteamHosePort port) {
        java.util.Objects.requireNonNull(port, "Hose geometry requires the saved surface attachment");
        Port from;
        if (state.getBlock() instanceof AbstractBogeyBlock<?>) {
            // Both native bogey sizes share the static core model: its lid is
            // at block Y=1, independent of the animated frame below it.
            from = new Port(centre.add(0, .5 + .015, 0), new Vec3(0, 1, 0));
        } else {
            Vec3 diff = centre.subtract(tank.getCenter());
            Vec3 side = Math.abs(diff.x) >= Math.abs(diff.z) ? new Vec3(diff.x > 0 ? 1 : -1, 0, 0)
                : new Vec3(0, 0, diff.z > 0 ? 1 : -1);
            from = new Port(centre.add(side.scale(.3)), side);
        }
        Vec3 normal = port.normal();
        return between(from, new Port(selected.subtract(.5, .5, .5).add(port.point()).add(normal.scale(.015)), normal));
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
