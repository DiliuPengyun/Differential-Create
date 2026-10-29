package com.diliupengyun.differential_create.client;

import java.util.HashMap;
import java.util.Map;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Steam follows this carriage's supplied input rotors; native wheel and horn sounds remain separate. */
public final class TrainSteamSounds {
    private final Map<BlockPos, SteamSoundAnimation> engines = new HashMap<>();

    public void tick(CarriageContraptionEntity entity) {
        var mc = Minecraft.getInstance(); var sample = ClientTrainKinetics.soundSample(entity);
        if (sample == null || entity.level() != mc.level || mc.cameraEntity == null || entity.getContraption() == null
            || !entity.validForRender || entity.firstPositionUpdate || !entity.level().tickRateManager().runsNormally()) {
            engines.clear(); return;
        }
        double age = ClientKineticAngles.renderTick() - sample.at();
        // Lost updates cannot keep a disconnected or unloaded engine sounding.
        if (age < 0 || age > 10) { engines.clear(); return; }
        engines.keySet().removeIf(pos -> sample.rotors().stream().noneMatch(r -> r.pos().equals(pos)));
        for (var rotor : sample.rotors()) {
            var block = entity.getContraption().getBlocks().get(rotor.pos());
            if (block == null || block.nbt() == null || !block.nbt().getCompound("BogeyData").getBoolean(SteamBogey.ENGINE)
                || entity.getContraption().isHiddenInPortal(rotor.pos())) {
                engines.remove(rotor.pos()); continue;
            }
            double turns = rotor.turns() + rotor.rpm() * age / 1200;
            var animation = engines.computeIfAbsent(rotor.pos(), pos -> new SteamSoundAnimation());
            if (!animation.advance(turns, rotor.rpm(), rotor.steamSupply())) continue;
            Vec3 at = entity.toGlobalVector(Vec3.atCenterOf(rotor.pos()).add(0, .5, 0), 1);
            if (at.distanceToSqr(mc.cameraEntity.getEyePosition()) > 64 * 64) continue;
            float seated = mc.cameraEntity.getVehicle() instanceof CarriageContraptionEntity ? .65f : 1;
            float volume = .35f * (float) Math.sqrt(rotor.steamSupply()) * seated;
            float pitch = .9f + .2f * (float) Math.clamp(Math.abs(rotor.rpm()) / 512, 0, 1);
            AllSoundEvents.STEAM.playAt(entity.level(), at, volume, pitch, false);
        }
    }
}
