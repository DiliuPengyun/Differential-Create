package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.MixerHeadAnimation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MechanicalMixerRenderer.class)
public abstract class MechanicalMixerRendererMixin {
    @ModifyArg(method = "renderSafe(Lcom/simibubi/create/content/kinetics/mixer/MechanicalMixerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/SuperByteBuffer;rotateCentered(FLnet/minecraft/core/Direction;)Ldev/engine_room/flywheel/lib/transform/Affine;"), index = 0)
    private float differentialcreate$integratedHead(float original, @Local(argsOnly = true) MechanicalMixerBlockEntity mixer,
        @Local(argsOnly = true) float partial) {
        return (float) Math.toRadians(((MixerHeadAnimation.Owner) mixer).differentialcreate$headAnimation().degrees(partial));
    }
}
