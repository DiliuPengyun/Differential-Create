package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderFluidInterfaceScene;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.FluidMovementActorScenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the native stage and captions, scheduling operations around actual motion and transfer. */
@Mixin(FluidMovementActorScenes.class)
public abstract class FluidInterfaceSceneMixin {
    @Inject(method = "transfer", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$physicalLesson(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        PonderFluidInterfaceScene.transfer(builder, util);
        ci.cancel();
    }
}
