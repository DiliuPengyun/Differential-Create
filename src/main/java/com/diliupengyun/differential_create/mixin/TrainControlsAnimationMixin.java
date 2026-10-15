package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keep Create's models, steering, folding and interpolation; the main handle is a regulator/brake. */
@Mixin(ControlsMovementBehaviour.class)
public abstract class TrainControlsAnimationMixin {
    @ModifyArg(method = "renderInContraption", at = @At(value = "INVOKE",
        target = "Lnet/createmod/catnip/animation/LerpedFloat;chase(DDLnet/createmod/catnip/animation/LerpedFloat$Chaser;)Lnet/createmod/catnip/animation/LerpedFloat;", ordinal = 2), index = 0)
    private double differentialcreate$regulator(double speed, @Local(argsOnly = true) MovementContext context) {
        if (!(context.contraption.entity instanceof CarriageContraptionEntity entity)) return 0;
        var state = ClientTrainKinetics.freshState(entity);
        // Native angles: idle -25 degrees, full admission +10, service brake -45.
        return state == null ? 0 : state.braking() ? -2d / 7 : state.admission() * .5;
    }

    @ModifyArg(method = "renderInContraption", at = @At(value = "INVOKE",
        target = "Lnet/createmod/catnip/animation/LerpedFloat;chase(DDLnet/createmod/catnip/animation/LerpedFloat$Chaser;)Lnet/createmod/catnip/animation/LerpedFloat;", ordinal = 5), index = 1)
    private double differentialcreate$returnHandle(double speed) { return .2; }
}
