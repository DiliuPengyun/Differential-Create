package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.diliupengyun.differential_create.kinetics.MovingDeployerWork;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeployerMovementBehaviour.class)
public abstract class DeployerMovementBehaviourMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$paidVisit(MovementContext context, BlockPos pos, CallbackInfo ci) {
        if (!context.world.isClientSide && MovingDeployerWork.supported(context)) {
            var hand = ContraptionActorWork.of(context).hand();
            if (!hand.firing) { hand.visit(pos); ci.cancel(); }
        }
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$physicalCycle(MovementContext context, CallbackInfo ci) {
        if (!context.world.isClientSide && MovingDeployerWork.supported(context)) {
            ContraptionActorWork.of(context).hand().prepare(); context.stall = false; ci.cancel();
        }
    }
    @Inject(method = "cancelStall", at = @At("HEAD"))
    private void differentialcreate$cancel(MovementContext context, CallbackInfo ci) {
        if (!context.world.isClientSide && MovingDeployerWork.supported(context))
            ContraptionActorWork.of(context).hand().cancel("cancelled");
    }
}
