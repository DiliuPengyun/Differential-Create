package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;

/** Accepted server pose travels with vanilla entity data, including late tracking. */
public interface IMinecartOrientation {
    record Snapshot(float yaw, float pitch, boolean rotating) {}
    Snapshot differentialcreate$orientation();
    void differentialcreate$orientation(Snapshot snapshot);
    static void apply(OrientedContraptionEntity entity, Snapshot snapshot) {
        entity.prevYaw = entity.yaw; entity.prevPitch = entity.pitch;
        entity.yaw = snapshot.yaw(); entity.pitch = snapshot.pitch();
    }
}
