package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidValveBlockEntity.class)
public abstract class PonderValveMixin implements com.diliupengyun.differential_create.client.ponder.PonderValveStroke {
    @Shadow LerpedFloat pointer;
    @Override public float differentialcreate$opening() { return pointer.getValue(); }
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$actualStroke(CallbackInfo ci) {
        var valve = (FluidValveBlockEntity) (Object) this; var world = valve.getLevel();
        if (!PhysicsPreview.enabled(world)) return;
        float value = (float) Math.clamp(pointer.getValue() + Math.signum(valve.getSpeed()) * PhysicsPreview.travel(valve) / 320, 0, 1);
        pointer.chase(value, 0, LerpedFloat.Chaser.LINEAR); pointer.setValue(value);
        var state = valve.getBlockState();
        if (state.hasProperty(FluidValveBlock.ENABLED) && (value == 0 || value == 1) && state.getValue(FluidValveBlock.ENABLED) != (value == 1))
            world.setBlockAndUpdate(valve.getBlockPos(), state.setValue(FluidValveBlock.ENABLED, value == 1));
        ci.cancel();
    }
}
