package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.SteamExhaustAnimation;
import com.diliupengyun.differential_create.client.SteamExhaustParticles;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamEngineParticlesMixin {
    @Unique private final SteamExhaustAnimation differentialcreate$exhaust = new SteamExhaustAnimation();

    @Inject(method = "spawnParticles", at = @At("HEAD"))
    private void differentialcreate$bypassExhaust(CallbackInfo ci) {
        SteamExhaustParticles.tick((SteamEngineBlockEntity) (Object) this, differentialcreate$exhaust);
    }
}
