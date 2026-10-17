package com.diliupengyun.differential_create.mixin;

import java.util.function.UnaryOperator;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.scene.Selection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Includes retained native storyboards: no instantaneous RPM assignment in the preview. */
@Mixin(targets = "com.simibubi.create.foundation.ponder.CreateSceneBuilder$WorldInstructions")
public abstract class PonderKineticInstructionsMixin {
    @Shadow @Final private CreateSceneBuilder this$0;
    @Inject(method = "modifyKineticSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$commandDrive(Selection selection, UnaryOperator<Float> operation, CallbackInfo ci) {
        this$0.addInstruction(scene -> PonderKinetics.of(scene.getWorld()).command(scene.getWorld(), selection, operation));
        ci.cancel();
    }
}
