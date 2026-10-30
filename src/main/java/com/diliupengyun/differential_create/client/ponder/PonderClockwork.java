package com.diliupengyun.differential_create.client.ponder;

import java.util.IdentityHashMap;
import java.util.Map;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.kinetics.ShaftPotential;
import net.createmod.catnip.math.AngleHelper;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Clock changes are targets; finite passive couplings move each physical hand. */
public final class PonderClockwork {
    private static final class Clock {
        double hours, minutes, minuteOrigin;
        boolean running, active;
        final Motion hour = new Motion(), minute = new Motion();
        long time() {
            double minutePhase = minutes - minuteOrigin;
            return Math.floorMod(Math.round(hours / 30 * 1000 - 6000 + minutePhase * 1000 / 360), 24000);
        }
    }
    public static final class Motion {
        public double angle, previousAngle, speed, inertia, drag;
        ContraptionGeometry.Shape shape;
        int axis;
    }
    public record Output(ClockworkBearingBlockEntity bearing, boolean minute, Motion motion, ShaftPotential gravity, float target) {
        public double ratio() { return minute ? 1 : .5; }
        public double error(double travel) {
            return AngleHelper.getShortestAngleDiff((float) (((motion.angle + travel) * 360 * ratio()) % 360), target) / (360 * ratio());
        }
    }
    private record Hand(ClockworkBearingBlockEntity bearing, boolean minute, int axis, Vec3 start, double initial) {}
    private final Map<ClockworkBearingBlockEntity, Clock> clocks = new IdentityHashMap<>();
    private final Map<WorldSectionElement, Hand> hands = new IdentityHashMap<>();
    public Long time(ClockworkBearingBlockEntity bearing) { var clock = clocks.get(bearing); return clock == null ? null : clock.time(); }
    public void request(WorldSectionElement section, ClockworkBearingBlockEntity bearing, int axis, double degrees) {
        var world = (PonderLevel) bearing.getLevel();
        var facing = bearing.getBlockState().getValue(BlockStateProperties.FACING);
        var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
        int nearest = Integer.MAX_VALUE;
        for (var pos : selection) if (!world.getBlockState(pos).isAir())
            nearest = Math.min(nearest, pos.subtract(bearing.getBlockPos()).get(facing.getAxis()) * facing.getAxisDirection().getStep());
        boolean minute = nearest >= 3;
        var state = (ClockworkBearingAccessor) bearing;
        hands.putIfAbsent(section, new Hand(bearing, minute, axis, section.getAnimatedRotation(),
            minute ? state.differentialcreate$minuteAngle() : state.differentialcreate$hourAngle()));
        var clock = clocks.computeIfAbsent(bearing, ignored -> new Clock());
        if (minute) clock.minutes += degrees;
        else {
            clock.hours += degrees;
            // Minutes carry into the hour until the storyboard explicitly
            // updates that hour. A later hour command acknowledges the carry.
            clock.minuteOrigin = 360 * Math.floor(clock.minutes / 360);
        }
        clock.running = true;
        var cells = new java.util.HashMap<BlockPos, ContraptionGeometry.Cell>();
        for (var pos : selection) if (!world.getBlockState(pos).isAir()) {
            var relative = pos.subtract(bearing.getBlockPos());
            cells.put(pos.immutable(), new ContraptionGeometry.Cell(relative.getX(), relative.getY(), relative.getZ()));
        }
        PonderSimulation.of(world).mounting().bindSection(section, bearing, true, axis, cells, minute ? 1 : .5);
    }
    public void stop(PonderLevel world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof ClockworkBearingBlockEntity bearing)) return;
        var clock = clocks.computeIfAbsent(bearing, ignored -> new Clock());
        clock.running = false; clock.hours = clock.minutes = clock.minuteOrigin = 0;
        clock.hour.angle = clock.minute.angle = clock.hour.speed = clock.minute.speed = 0;
        clock.hour.previousAngle = clock.minute.previousAngle = 0;
        var state = (ClockworkBearingAccessor) bearing;
        state.differentialcreate$hourAngle(0); state.differentialcreate$minuteAngle(0);
    }
    public void release(WorldSectionElement section) { hands.remove(section); }
    double motionRatio(WorldSectionElement section, double fallback) {
        var hand = hands.get(section);
        return hand == null ? fallback : hand.minute() ? 1 : .5;
    }
    void active(ClockworkBearingBlockEntity bearing, boolean active) {
        var clock = clocks.get(bearing); if (clock != null) clock.active = active;
    }
    public Double topAngle(ClockworkBearingBlockEntity bearing, float fraction) {
        var clock = clocks.get(bearing);
        if (clock == null || !clock.active || !clock.running) return null;
        return (clock.hour.previousAngle + (clock.hour.angle - clock.hour.previousAngle) * fraction) * 180;
    }
    void mass(ClockworkBearingBlockEntity bearing, int axis, Map<Double, java.util.Set<ContraptionGeometry.Cell>> groups) {
        var clock = clocks.get(bearing); if (clock == null) return;
        for (double ratio : new double[]{.5, 1}) {
            var motion = ratio == .5 ? clock.hour : clock.minute;
            var cells = groups.getOrDefault(ratio, java.util.Set.of());
            var shape = ContraptionGeometry.measure(cells);
            double inertia = ratio * ratio * shape.rotationInertia()[axis];
            if (inertia > motion.inertia && motion.inertia > 0) motion.speed *= motion.inertia / inertia;
            motion.inertia = inertia; motion.drag = ratio * ratio * ratio * shape.rotationDrag()[axis];
            motion.shape = shape; motion.axis = axis;
        }
        clock.active = true;
    }
    public java.util.List<Output> outputs() {
        var result = new java.util.ArrayList<Output>();
        clocks.forEach((bearing, clock) -> {
            if (!clock.running || !clock.active || bearing.isRemoved()) return;
            var access = (ClockworkBearingAccessor) bearing;
            int mode = access.differentialcreate$operationMode().getValue();
            for (boolean minute : new boolean[]{false, true}) {
                var motion = minute ? clock.minute : clock.hour; double ratio = minute ? 1 : .5;
                if (!(motion.inertia > 0)) continue;
                float target = minute ? mode == 1 ? access.differentialcreate$hourTarget(false) : access.differentialcreate$minuteTarget()
                    : mode == 1 ? access.differentialcreate$minuteTarget() : access.differentialcreate$hourTarget(mode == 2);
                var gravity = ShaftPotential.NONE;
                if (motion.axis != 1) {
                    double weight = motion.shape.mass() * ShaftPotential.WEIGHT, angle = motion.angle * ratio * 2 * Math.PI;
                    double y = motion.shape.centre(1), tangent = motion.axis == 0 ? -motion.shape.centre(2) : motion.shape.centre(0);
                    gravity = new ShaftPotential(0, java.util.List.of(new ShaftPotential.Harmonic(
                        weight * (y * Math.cos(angle) + tangent * Math.sin(angle)),
                        weight * (tangent * Math.cos(angle) - y * Math.sin(angle)), 2 * Math.PI * ratio)));
                }
                result.add(new Output(bearing, minute, motion, gravity, target));
            }
        });
        return result;
    }
    public void reset() { clocks.clear(); hands.clear(); }
    public void tick() {
        clocks.forEach((bearing, clock) -> {
            if (!clock.running || bearing.isRemoved() || hands.entrySet().stream().noneMatch(entry -> entry.getValue().bearing() == bearing && entry.getKey().isVisible())) return;
            var state = (ClockworkBearingAccessor) bearing;
            state.differentialcreate$hourAngle((float) (clock.hour.angle * 180));
            state.differentialcreate$minuteAngle((float) (clock.minute.angle * 360));
        });
        hands.forEach((section, hand) -> {
            if (!section.isVisible()) return;
            var state = (ClockworkBearingAccessor) hand.bearing();
            double travel = (hand.minute() ? state.differentialcreate$minuteAngle() : state.differentialcreate$hourAngle()) - hand.initial();
            section.setAnimatedRotation(hand.start().add(hand.axis() == 0 ? travel : 0,
                hand.axis() == 1 ? travel : 0, hand.axis() == 2 ? travel : 0), false);
        });
    }
}
