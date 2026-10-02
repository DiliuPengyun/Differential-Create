package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PumpBlockEntity.class)
public abstract class PumpBlockEntityMixin implements IPumpDynamics {
    @Unique private float differentialcreate$head;
    @Unique private double differentialcreate$flow;
    @Unique private double differentialcreate$torque;
    @Override public float differentialcreate$head() { return differentialcreate$head; }
    @Override public double differentialcreate$flow() { return differentialcreate$flow; }
    @Override public double differentialcreate$torque() { return differentialcreate$torque; }
    @Override public void differentialcreate$hydraulicState(double head, double flow, double torque) {
        differentialcreate$head = (float) head;
        differentialcreate$flow = flow;
        differentialcreate$torque = torque;
    }
    @Inject(method = "distributePressureTo", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$jointPressure(Direction side, CallbackInfo ci) {
        var self = (PumpBlockEntity) (Object) this;
        if (!self.getLevel().isClientSide && !self.isVirtual()) ci.cancel();
    }
    @Override public void differentialcreate$saveHydraulics(CompoundTag tag) {
        tag.putFloat("DCPumpHead", differentialcreate$head);
        tag.putDouble("DCPumpFlow", differentialcreate$flow);
        tag.putDouble("DCPumpTorque", differentialcreate$torque);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        differentialcreate$head = tag.getFloat("DCPumpHead");
        differentialcreate$flow = tag.getDouble("DCPumpFlow");
        differentialcreate$torque = tag.getDouble("DCPumpTorque");
    }
}
