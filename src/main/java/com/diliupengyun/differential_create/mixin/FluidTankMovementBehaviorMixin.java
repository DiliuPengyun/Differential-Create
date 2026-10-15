package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankMovementBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moving tanks do not run the world BE tick/lazyTick that animate and reveal boiler gauges. */
@Mixin(FluidTankMovementBehavior.class)
public abstract class FluidTankMovementBehaviorMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void differentialcreate$movingBoilerGauge(MovementContext context, CallbackInfo ci) {
        if (!context.world.isClientSide) return;
        if (context.contraption.getBlockEntityClientSide(context.localPos) instanceof FluidTankBlockEntity tank
            && tank.isController() && tank.boiler.isActive()) {
            tank.boiler.gauge.tickChaser();
            tank.boiler.updateOcclusion(tank);
        }
    }
}
