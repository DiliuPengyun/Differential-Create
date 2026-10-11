package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientMovingHands;
import com.simibubi.create.content.kinetics.deployer.DeployerRenderer;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(DeployerRenderer.class)
public abstract class MovingDeployerRendererMixin {
    @ModifyArg(method = "renderInContraption", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"), index = 0)
    private static double differentialcreate$paidExtension(double nativeExtension, @Local(argsOnly = true) MovementContext ctx) {
        return ClientMovingHands.managed(ctx) ? ClientMovingHands.extension(ctx) : nativeExtension;
    }
    @ModifyArg(method = "renderInContraption", at = @At(value = "INVOKE",
        target = "Lnet/createmod/catnip/render/SuperByteBuffer;rotateCentered(FLnet/minecraft/core/Direction;)Ldev/engine_room/flywheel/lib/transform/Affine;"), index = 0)
    private static float differentialcreate$paidAngle(float nativeAngle, @Local(argsOnly = true) MovementContext ctx) {
        return ClientMovingHands.managed(ctx) ? ClientMovingHands.angle(ctx) : nativeAngle;
    }
}
