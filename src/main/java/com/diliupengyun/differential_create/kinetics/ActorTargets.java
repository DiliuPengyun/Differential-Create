package com.diliupengyun.differential_create.kinetics;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Observe only targets with live work, including remove-and-replace operations
 * whose final block state is identical. Weak owners do not retain unloaded carts. */
public final class ActorTargets {
    private static final Map<Level, Map<Long, Set<ContraptionActorWork>>> targets = new WeakHashMap<>();
    private ActorTargets() {}
    static synchronized void watch(ContraptionActorWork work, BlockPos pos) {
        if (pos.equals(work.watchedTarget)) return;
        forget(work);
        work.watchedTarget = pos.immutable();
        targets.computeIfAbsent(work.context().world, $ -> new HashMap<>())
            .computeIfAbsent(pos.asLong(), $ -> Collections.newSetFromMap(new WeakHashMap<>())).add(work);
    }
    public static synchronized void forget(ContraptionActorWork work) {
        if (work.watchedTarget == null) return;
        var level = work.context().world; var positions = targets.get(level);
        if (positions != null) {
            long pos = work.watchedTarget.asLong(); var owners = positions.get(pos);
            if (owners != null) { owners.remove(work); if (owners.isEmpty()) positions.remove(pos); }
            if (positions.isEmpty()) targets.remove(level);
        }
        work.watchedTarget = null;
    }
    public static synchronized void changed(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        var positions = targets.get(level); if (positions == null) return;
        var owners = positions.get(pos.asLong()); if (owners == null) return;
        for (var work : owners) {
            if (MovingDeployerWork.isHand(work.context()) && work.hand().firing) continue;
            work.context().data.putBoolean("DCActorInvalidated", true);
        }
    }
}
