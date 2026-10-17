package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** The impeller is fixed to the shaft, including its phase and zero-speed pose. */
@Mixin(EncasedFanRenderer.class)
public abstract class EncasedFanRendererMixin {
    @ModifyArgs(method = "renderSafe(Lcom/simibubi/create/content/kinetics/fan/EncasedFanBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/EncasedFanRenderer;kineticRotationTransform(Lnet/createmod/catnip/render/SuperByteBuffer;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction$Axis;FI)Lnet/createmod/catnip/render/SuperByteBuffer;"))
    private void differentialcreate$shaftDrivenImpeller(Args args) {
        KineticBlockEntity fan = args.get(1);
        args.set(3, ClientKineticAngles.shaftAngleOf(fan));
    }
}
