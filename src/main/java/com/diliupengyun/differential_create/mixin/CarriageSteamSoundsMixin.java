package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.TrainSteamSounds;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.AllSoundEvents.SoundEntry;
import com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSounds;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CarriageSounds.class)
public abstract class CarriageSteamSoundsMixin {
    @Shadow CarriageContraptionEntity entity;
    @Unique private final TrainSteamSounds differentialcreate$steam = new TrainSteamSounds();

    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$actualSteam(DimensionalCarriageEntity carriage, CallbackInfo ci) {
        differentialcreate$steam.tick(entity);
    }
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V", ordinal = 0))
    private void differentialcreate$replaceTravelSteam(SoundEntry sound, Level level, Vec3 pos, float volume, float pitch, boolean delay) {
        if (sound != AllSoundEvents.STEAM) sound.playAt(level, pos, volume, pitch, delay);
    }
    // Only the two wheel-timed exhaust calls are replaced. Native stopping
    // release and whistle calls later in tick retain their original behavior.
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V", ordinal = 1))
    private void differentialcreate$replaceTravelBurst(SoundEntry sound, Level level, Vec3 pos, float volume, float pitch, boolean delay) {
        differentialcreate$replaceTravelSteam(sound, level, pos, volume, pitch, delay);
    }
}
