package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.minecraft.client.Minecraft;

/** The output half reads the same snapshot clock as the surrounding shafts. */
public final class ClientSequencerAngles {
    private ClientSequencerAngles() {}

    public static RotationFrame outputRotation(SequencedGearshiftBlockEntity be) {
        if (be.getLevel() instanceof net.createmod.ponder.api.level.PonderLevel level) {
            Double angle = com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(level)
                .sequencerOutputAngle(be, net.createmod.catnip.animation.AnimationTickHolder.getPartialTicks(level));
            return new RotationFrame(Minecraft.getInstance().levelRenderer.getTicks(), angle == null ? 0 : angle, 0);
        }
        var frame = ClientNetworkSpeeds.outputRotationOf(be);
        if (frame != null) return frame;
        // Chunk NBT can supply a parked pose before the first physical snapshot.
        // It must never drive a competing interpolation stream once synced.
        double angle = ((ISequencerDynamics) be).differentialcreate$motion().shaftAngle() * 360;
        return new RotationFrame(Minecraft.getInstance().levelRenderer.getTicks(), angle, 0);
    }
}
