package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamEngineParticlesMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$hoseRenderer(CallbackInfo ci) {
        com.diliupengyun.differential_create.client.SteamHoseRenderer.observe((SteamEngineBlockEntity) (Object) this);
    }
    @Inject(method = "spawnParticles", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$actualCylinderIntake(CallbackInfo ci) {
        var shaft = ((SteamEngineBlockEntity) (Object) this).getShaft();
        Float intake = shaft == null ? null : ClientNetworkSpeeds.steamIntakeOf(shaft);
        if (intake != null && intake <= 0) ci.cancel();
    }
}
