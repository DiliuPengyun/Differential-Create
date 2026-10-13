package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.TrainStationParking;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StationBlockEntity.class)
public abstract class StationParkingMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/station/GlobalStation;getImminentTrain()Lcom/simibubi/create/content/trains/entity/Train;"))
    private void differentialcreate$recognizeParkedTrain(CallbackInfo ci) {
        var block = (StationBlockEntity) (Object) this;
        if (Math.floorMod(block.getLevel().getGameTime() + block.getBlockPos().asLong(), 20) == 0) TrainStationParking.recover(block);
    }

    @Inject(method = "tryDisassembleTrain", at = @At("HEAD"))
    private void differentialcreate$recoverBeforeDisassembly(ServerPlayer sender, CallbackInfoReturnable<Boolean> cir) {
        TrainStationParking.recover((StationBlockEntity) (Object) this);
    }
}
