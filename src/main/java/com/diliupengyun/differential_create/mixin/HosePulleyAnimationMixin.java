package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.HosePulleyAnimation;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shared by the ordinary renderer and Flywheel, in real worlds and Ponder. */
@Mixin(HosePulleyBlockEntity.class)
public abstract class HosePulleyAnimationMixin {
    @Shadow LerpedFloat offset;

    @Inject(method = "getInterpolatedOffset", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$visibleStartup(float partialTick, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(HosePulleyAnimation.offset(offset.getValue(partialTick)));
    }
}
