package com.diliupengyun.differential_create.mixin;

import java.util.Collection;
import java.util.ArrayList;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageControlsPhysicsMixin {
    @ModifyArg(method = "control", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Navigation;findNearestApproachable(Z)Lcom/simibubi/create/content/trains/station/GlobalStation;"), index = 0)
    private boolean differentialcreate$stationDirection(boolean forward, @Local(name = "inverted") boolean inverted) {
        var train = ((CarriageContraptionEntity) (Object) this).getCarriage().train;
        var state = TrainPhysics.state(train); state.cabDirection = inverted ? -1 : 1;
        int gear = TrainPhysics.hasLever(train) ? state.gear : 1;
        return Math.abs(train.speed) > 1e-6 ? train.speed > 0
            : state.cabDirection * (gear == TrainMechanics.REVERSE_GEAR ? -1 : 1) > 0;
    }

    @ModifyVariable(method = "control", at = @At("HEAD"), argsOnly = true)
    private Collection<Integer> differentialcreate$driveKeys(Collection<Integer> controls,
        @Share("serviceBrake") LocalBooleanRef serviceBrake, @Share("neutralRev") LocalBooleanRef neutralRev) {
        serviceBrake.set(controls.contains(1));
        var carriage = ((CarriageContraptionEntity) (Object) this).getCarriage();
        if (carriage == null) return controls;
        var result = new ArrayList<>(controls);
        result.removeIf(key -> key == 0 || key == 1);
        if (serviceBrake.get()) result.remove(Integer.valueOf(4));
        int gear = TrainPhysics.hasLever(carriage.train) ? TrainPhysics.state(carriage.train).gear : 1;
        neutralRev.set(gear == 0 && controls.contains(0) && !serviceBrake.get());
        if (controls.contains(0) && !serviceBrake.get() && gear != 0)
            result.add(gear == TrainMechanics.REVERSE_GEAR ? 1 : 0);
        // Native code still handles station prompts, steering and cab orientation.
        // Only its drive inputs change: S brakes, and W drives the selected gear.
        return result;
    }

    @Inject(method = "control", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Train;maxSpeed()F"), cancellable = true)
    private void differentialcreate$valveControl(BlockPos pos, Collection<Integer> controls, Player player,
        CallbackInfoReturnable<Boolean> cir, @Local(name = "targetSpeed") int direction,
        @Share("serviceBrake") LocalBooleanRef serviceBrake, @Share("neutralRev") LocalBooleanRef neutralRev) {
        var entity = (CarriageContraptionEntity) (Object) this; var carriage = entity.getCarriage();
        boolean brake = serviceBrake.get() || controls.contains(4);
        if (carriage != null) {
            // Cancelling this injection returns before RETURN hooks can run.
            TrainPhysics.endStationAssist(carriage.train);
            if (controls.contains(4)) TrainPhysics.park(carriage.train);
            else TrainPhysics.manual(carriage.train, brake ? 0 : direction, brake, neutralRev.get());
        }
        cir.setReturnValue(true);
    }
    @Inject(method = "control", at = @At("RETURN"))
    private void differentialcreate$stationControl(BlockPos pos, Collection<Integer> controls, Player player,
        CallbackInfoReturnable<Boolean> cir, @Share("serviceBrake") LocalBooleanRef serviceBrake,
        @Share("neutralRev") LocalBooleanRef neutralRev) {
        var entity = (CarriageContraptionEntity) (Object) this; var carriage = entity.getCarriage();
        if (entity.level().isClientSide || carriage == null || carriage.train == null) return;
        var train = carriage.train; var state = TrainPhysics.state(train);
        if (!cir.getReturnValue()) { TrainPhysics.endStationAssist(train); return; }
        if (controls.contains(4) && !serviceBrake.get()) {
            if (train.getCurrentStation() != null) {
                TrainPhysics.endStationAssist(train); TrainPhysics.park(train); return;
            }
            if (train.navigation.destination != null) {
                var block = entity.getContraption().getBlocks().get(pos);
                state.cabDirection = block.state().getValue(ControlsBlock.FACING) == entity.getInitialOrientation().getCounterClockWise() ? 1 : -1;
                state.stationAssist = true; state.manualCommand = true; state.neutralRev = neutralRev.get();
                return;
            }
        }
        TrainPhysics.endStationAssist(train);
    }
    @Inject(method = "setCarriage", at = @At("RETURN"))
    private void differentialcreate$restore(Carriage carriage, CallbackInfo ci) {
        var entity = (CarriageContraptionEntity) (Object) this;
        if (entity.level().isClientSide || carriage.train == null || !(entity.getContraption() instanceof CarriageContraption cc)) return;
        TrainPhysics.carriage(carriage).apply(cc, TrainPhysics.state(carriage.train).gear, false, TrainPhysics.hasLever(carriage.train));
    }
}
