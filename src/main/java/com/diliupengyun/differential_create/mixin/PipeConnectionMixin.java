package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.diliupengyun.differential_create.kinetics.PumpPressure;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.data.Couple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipeConnection.class)
public abstract class PipeConnectionMixin implements IPipePressure {
    @Shadow Couple<Float> pressure;
    @Unique private final PumpPressure differentialcreate$pumps = new PumpPressure();

    @Override
    public void differentialcreate$addPumpPressure(PumpBlockEntity pump, boolean inbound, float value) {
        ((PipeConnection) (Object) this).addPressure(inbound, value);
        differentialcreate$pumps.add(pump, inbound, value);
    }

    @Inject(method = "getPressure", at = @At("HEAD"))
    private void differentialcreate$pressure(CallbackInfoReturnable<Couple<Float>> cir) {
        differentialcreate$pumps.refresh(pressure);
    }

    @Inject(method = {"getInboundPressure", "getOutwardPressure"}, at = @At("HEAD"))
    private void differentialcreate$directionalPressure(CallbackInfoReturnable<Float> cir) {
        differentialcreate$pumps.refresh(pressure);
    }

    @Inject(method = "addPressure", at = @At("HEAD"))
    private void differentialcreate$beforeAdding(CallbackInfo ci) {
        differentialcreate$pumps.refresh(pressure);
    }

    @Inject(method = {"wipePressure", "deserializeNBT"}, at = @At("HEAD"))
    private void differentialcreate$clearProviders(CallbackInfo ci) {
        differentialcreate$pumps.clear();
    }
}
