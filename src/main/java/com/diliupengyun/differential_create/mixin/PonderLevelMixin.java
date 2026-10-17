package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.IPonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import net.createmod.ponder.api.level.PonderLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PonderLevel.class)
public abstract class PonderLevelMixin implements IPonderKinetics {
    @Unique private final PonderKinetics differentialcreate$kinetics = new PonderKinetics();
    @Override public PonderKinetics differentialcreate$kinetics() { return differentialcreate$kinetics; }
    @Inject(method = "restore", at = @At("HEAD"))
    private void differentialcreate$rewindPhysics(CallbackInfo ci) { differentialcreate$kinetics.reset(); }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$advancePhysics(CallbackInfo ci) {
        differentialcreate$kinetics.tick((PonderLevel) (Object) this);
    }
}
