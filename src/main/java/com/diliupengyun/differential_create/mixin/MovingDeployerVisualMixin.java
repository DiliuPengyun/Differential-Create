package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientMovingHands;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.deployer.DeployerActorVisual;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(DeployerActorVisual.class)
public abstract class MovingDeployerVisualMixin extends ActorVisual {
    @Shadow RotatingInstance shaft;
    protected MovingDeployerVisualMixin(VisualizationContext vc, VirtualRenderWorld world, MovementContext ctx) { super(vc, world, ctx); }
    @Inject(method = "deploymentDistance", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$paidExtension(CallbackInfoReturnable<Float> cir) {
        if (ClientMovingHands.managed(context)) cir.setReturnValue((float) ClientMovingHands.extension(context));
    }
    @Inject(method = "beginFrame", at = @At("HEAD"))
    private void differentialcreate$paidAngle(CallbackInfo ci) {
        if (!ClientMovingHands.managed(context)) return;
        shaft.setRotationalSpeed(0);
        shaft.rotationOffset = ClientMovingHands.angle(context) + KineticBlockEntityVisual.rotationOffset(context.state,
            KineticBlockEntityVisual.rotationAxis(context.state), context.localPos);
        shaft.setChanged();
    }
}
