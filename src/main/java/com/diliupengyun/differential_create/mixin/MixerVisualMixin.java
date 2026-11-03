package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.MixerHeadAnimation;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MixerVisual;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MixerVisual.class)
public abstract class MixerVisualMixin {
    @Shadow @Final private RotatingInstance mixerHead;
    @Shadow @Final private MechanicalMixerBlockEntity mixer;

    @Inject(method = "transformHead", at = @At("RETURN"))
    private void differentialcreate$integratedHead(float offset, float partial, CallbackInfo ci) {
        ((MixerHeadAnimation.Owner) mixer).differentialcreate$headAnimation().apply(mixerHead, partial);
    }
}
