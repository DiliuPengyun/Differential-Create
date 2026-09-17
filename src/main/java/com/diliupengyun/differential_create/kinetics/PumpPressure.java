package com.diliupengyun.differential_create.kinetics;

import java.util.Map;
import java.util.WeakHashMap;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.Direction;

/** Keeps each pump's share of pipe pressure proportional to its live rotor speed. */
public final class PumpPressure {
    private static final class Contribution {
        final Direction facing;
        double inbound, outward;
        Contribution(Direction facing) { this.facing = facing; }
    }
    private final Map<PumpBlockEntity, Contribution> pumps = new WeakHashMap<>();
    private float previousInbound, previousOutward;

    /** Called after Create has added this contribution to the stored pressure. */
    public void add(PumpBlockEntity pump, boolean inbound, float pressure) {
        float speed = Math.abs(pump.getSpeed());
        if (!(speed > 0) || pressure == 0) return;
        var contribution = pumps.computeIfAbsent(pump,
            key -> new Contribution(key.getBlockState().getValue(PumpBlock.FACING)));
        if (inbound) {
            contribution.inbound += pressure / (double) speed;
            previousInbound += pressure;
        } else {
            contribution.outward += pressure / (double) speed;
            previousOutward += pressure;
        }
    }

    public void refresh(Couple<Float> pressure) {
        double inbound = 0, outward = 0;
        for (var entry : pumps.entrySet()) {
            var pump = entry.getKey();
            var level = pump.getLevel();
            var contribution = entry.getValue();
            if (level == null || pump.isRemoved() || !level.isLoaded(pump.getBlockPos())
                || level.getBlockEntity(pump.getBlockPos()) != pump
                || !(pump.getBlockState().getBlock() instanceof PumpBlock)
                || pump.getBlockState().getValue(PumpBlock.FACING) != contribution.facing) continue;
            double speed = Math.abs(pump.getSpeed());
            inbound += contribution.inbound * speed;
            outward += contribution.outward * speed;
        }
        float nextInbound = (float) inbound, nextOutward = (float) outward;
        // Preserve pressure supplied by other behaviours instead of scaling the whole pipe.
        if (nextInbound != previousInbound)
            pressure.setFirst(Math.max(0, pressure.getFirst() - previousInbound + nextInbound));
        if (nextOutward != previousOutward)
            pressure.setSecond(Math.max(0, pressure.getSecond() - previousOutward + nextOutward));
        previousInbound = nextInbound;
        previousOutward = nextOutward;
    }

    public void clear() {
        pumps.clear();
        previousInbound = previousOutward = 0;
    }
}
