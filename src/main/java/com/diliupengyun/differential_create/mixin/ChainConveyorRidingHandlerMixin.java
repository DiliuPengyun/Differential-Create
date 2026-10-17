package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ChainConveyorTravel;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRidingHandler;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChainConveyorRidingHandler.class)
public abstract class ChainConveyorRidingHandlerMixin {
    @Redirect(method = "updateTargetPosition", at = @At(value = "INVOKE", target =
        "Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;loopThresholdCrossed(FFF)Z"))
    private static boolean differentialcreate$unwrappedCrossing(ChainConveyorBlockEntity conveyor, float end, float start, float target) {
        return ChainConveyorTravel.crosses(start, target, ChainConveyorTravel.degrees(conveyor.getSpeed()) * ServerSpeedProvider.get());
    }
}
