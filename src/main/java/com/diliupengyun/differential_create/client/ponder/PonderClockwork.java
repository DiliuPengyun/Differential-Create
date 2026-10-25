package com.diliupengyun.differential_create.client.ponder;

import java.util.IdentityHashMap;
import java.util.Map;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Clock changes are demonstration inputs; the native clock controller moves each hand. */
public final class PonderClockwork {
    private static final class Clock {
        double hours, minutes;
        boolean running;
        long time() { return Math.floorMod(Math.round(hours / 30 * 1000 - 6000 + Math.floorMod(Math.round(minutes / 6), 60) * 1000d / 60), 24000); }
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
        if (minute) clock.minutes += degrees; else clock.hours += degrees;
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
        clock.running = false; clock.hours = clock.minutes = 0;
        var state = (ClockworkBearingAccessor) bearing;
        state.differentialcreate$hourAngle(0); state.differentialcreate$minuteAngle(0);
    }
    public void release(WorldSectionElement section) { hands.remove(section); }
    double motionRatio(WorldSectionElement section, double fallback) {
        var hand = hands.get(section);
        if (hand == null) return fallback;
        double rpm = hand.bearing().getSpeed();
        if (Math.abs(rpm) < 1e-9) return 0;
        return (hand.minute() ? hand.bearing().getMinuteArmSpeed() : hand.bearing().getHourArmSpeed()) / (.3 * rpm);
    }
    public void reset() { clocks.clear(); hands.clear(); }
    public void tick() {
        clocks.forEach((bearing, clock) -> {
            if (!clock.running || bearing.isRemoved() || hands.entrySet().stream().noneMatch(entry -> entry.getValue().bearing() == bearing && entry.getKey().isVisible())) return;
            var state = (ClockworkBearingAccessor) bearing;
            state.differentialcreate$hourAngle(state.differentialcreate$hourAngle() + bearing.getHourArmSpeed());
            state.differentialcreate$minuteAngle(state.differentialcreate$minuteAngle() + bearing.getMinuteArmSpeed());
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
