package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity.RemovalReason;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContraptionEntity.class)
public abstract class TrainControlsExitMixin {
    @Inject(method = "stopControlling", at = @At("HEAD"))
    private void differentialcreate$cancelAssist(BlockPos pos, CallbackInfo ci) {
        differentialcreate$releaseControls();
    }
    @Inject(method = "remove", at = @At("HEAD"))
    private void differentialcreate$removedDriver(RemovalReason reason, CallbackInfo ci) {
        if (((AbstractContraptionEntity) (Object) this).getControllingPlayer().isPresent()) differentialcreate$releaseControls();
    }
    @Unique private void differentialcreate$releaseControls() {
        if (!((Object) this instanceof CarriageContraptionEntity entity) || entity.level().isClientSide) return;
        var carriage = entity.getCarriage();
        if (carriage == null || carriage.train == null) return;
        TrainPhysics.endStationAssist(carriage.train);
        var state = TrainPhysics.state(carriage.train);
        state.direction = 0; state.neutralRev = false; state.manualCommand = false; state.navigationCommand = false;
    }
}
