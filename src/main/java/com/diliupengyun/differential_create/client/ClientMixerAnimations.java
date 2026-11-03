package com.diliupengyun.differential_create.client;

import java.util.Map;
import java.util.WeakHashMap;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import net.minecraft.client.Minecraft;

/** Resolve mixer input segments after the shared network animation has advanced. */
public final class ClientMixerAnimations {
    private static final Map<MechanicalMixerBlockEntity, MixerHeadAnimation> mixers = new WeakHashMap<>();
    private ClientMixerAnimations() {}

    public static void track(MechanicalMixerBlockEntity mixer, MixerHeadAnimation animation) { mixers.put(mixer, animation); }

    public static void tick() {
        var mc = Minecraft.getInstance();
        mixers.keySet().removeIf(mixer -> mixer.isRemoved() || mixer.getLevel() != mc.level);
        if (mc.level == null || mc.isPaused()) return;
        boolean frozen = mc.level.tickRateManager().isFrozen();
        int tick = mc.levelRenderer.getTicks();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        mixers.forEach((mixer, animation) -> {
            var frame = ClientNetworkSpeeds.rotationOf(mixer);
            double start = frame == null ? 0 : frame.angleAt(tick);
            double end = frame == null ? mixer.getSpeed() * .3 : frame.angleAt(tick + 1);
            // Before the first network snapshot only the incremental fallback is known.
            if (frame == null) animation.advanceFallback(frozen ? 0 : end, frozen, partial);
            else animation.advance(start, end, frozen, partial);
        });
    }
}
