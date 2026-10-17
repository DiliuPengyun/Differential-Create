package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.FanVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use the same 1:1 impeller/shaft motion as the fallback renderer. */
@Mixin(FanVisual.class)
public abstract class FanVisualMixin extends KineticBlockEntityVisual<EncasedFanBlockEntity> {
    protected FanVisualMixin(VisualizationContext context, EncasedFanBlockEntity be, float partialTick) {
        super(context, be, partialTick);
    }

    @Inject(method = "getFanSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$shaftDrivenImpeller(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(blockEntity.getSpeed());
    }
}
