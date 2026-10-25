package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import net.createmod.ponder.foundation.PonderScene;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refresh after all scene edits, before either normal rendering or seek snapshots. */
@Mixin(PonderScene.class)
public abstract class PonderMeasurementsMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void differentialcreate$measure(CallbackInfo ci) {
        var world = ((PonderScene) (Object) this).getWorld();
        com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(world).finish(world);
        PonderKinetics.of(world).refreshInstruments(world);
    }
}
