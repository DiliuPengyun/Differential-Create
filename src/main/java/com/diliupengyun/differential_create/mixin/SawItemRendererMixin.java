package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.SawItemAnimation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SawRenderer.class)
public abstract class SawItemRendererMixin {
    @ModifyArg(method = "renderItems", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0), index = 2)
    private double differentialcreate$actualFeed(double original, @Local(argsOnly = true) SawBlockEntity saw,
        @Local(argsOnly = true) float partial) {
        return ((SawItemAnimation.Owner) saw).differentialcreate$itemAnimation().offset(saw, partial);
    }
}
