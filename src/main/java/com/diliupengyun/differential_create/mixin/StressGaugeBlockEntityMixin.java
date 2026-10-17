package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.LoadGaugeBehaviour;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StressGaugeBlockEntity.class)
public abstract class StressGaugeBlockEntityMixin {
    @Inject(method = "addBehaviours", at = @At("RETURN"))
    private void differentialcreate$loadInstrument(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        behaviours.add(new LoadGaugeBehaviour((StressGaugeBlockEntity) (Object) this));
    }

    @Inject(method = "updateFromNetwork", at = @At("RETURN"))
    private void differentialcreate$physicalDial(float maxStress, float currentStress, int size, CallbackInfo ci) {
        differentialcreate$refresh();
    }

    @Inject(method = "onSpeedChanged", at = @At("RETURN"))
    private void differentialcreate$keepStationaryReading(float previous, CallbackInfo ci) {
        differentialcreate$refresh();
    }

    private void differentialcreate$refresh() {
        var gauge = (StressGaugeBlockEntity) (Object) this;
        var behaviour = gauge.getBehaviour(LoadGaugeBehaviour.TYPE);
        if (behaviour != null) behaviour.refresh();
    }

    @Redirect(method = "onObserved", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;equal(FF)Z"))
    private boolean differentialcreate$balancedAdvancement(float dial, float oldFullScale) {
        var reading = LoadGaugeBehaviour.reading((StressGaugeBlockEntity) (Object) this);
        return com.diliupengyun.differential_create.kinetics.LoadGaugeScale.available(reading)
            && Math.abs(reading.fraction() - 1) < 1e-5;
    }
}
