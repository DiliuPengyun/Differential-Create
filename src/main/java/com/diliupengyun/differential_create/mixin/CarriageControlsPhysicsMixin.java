package com.diliupengyun.differential_create.mixin;

import java.util.Collection;
import com.llamalad7.mixinextras.sugar.Local;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.diliupengyun.differential_create.trains.CarriageSteamState;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageControlsPhysicsMixin {
    @Inject(method = "control", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Train;maxSpeed()F"), cancellable = true)
    private void differentialcreate$valveControl(BlockPos pos, Collection<Integer> controls, Player player,
        CallbackInfoReturnable<Boolean> cir, @Local(name = "targetSpeed") int direction) {
        var entity = (CarriageContraptionEntity) (Object) this; var carriage = entity.getCarriage();
        if (carriage != null) TrainPhysics.manual(carriage.train, direction, controls.contains(4));
        cir.setReturnValue(true);
    }
    @Inject(method = "setCarriage", at = @At("RETURN"))
    private void differentialcreate$restore(Carriage carriage, CallbackInfo ci) {
        var entity = (CarriageContraptionEntity) (Object) this;
        if (entity.level().isClientSide || carriage.train == null || !(entity.getContraption() instanceof CarriageContraption cc)) return;
        TrainPhysics.carriage(carriage).apply(cc, TrainPhysics.state(carriage.train).gear, false, TrainPhysics.hasLever(carriage.train));
    }
}
