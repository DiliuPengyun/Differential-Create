package com.diliupengyun.differential_create.client;

import java.util.Map;
import java.util.WeakHashMap;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.client.Minecraft;

/** Preserve a held item pose during pause/freeze and accepted frozen-world steps. */
public final class ClientSawAnimations {
    private static final Map<SawBlockEntity, SawItemAnimation> saws = new WeakHashMap<>();
    private ClientSawAnimations() {}
    public static void track(SawBlockEntity saw, SawItemAnimation animation) { saws.put(saw, animation); }
    public static void tick() {
        var mc = Minecraft.getInstance();
        saws.keySet().removeIf(saw -> saw.isRemoved() || saw.getLevel() != mc.level);
        if (mc.level == null || mc.isPaused()) return;
        boolean frozen = mc.level.tickRateManager().isFrozen();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        saws.values().forEach(animation -> animation.advance(frozen, partial));
    }
}
