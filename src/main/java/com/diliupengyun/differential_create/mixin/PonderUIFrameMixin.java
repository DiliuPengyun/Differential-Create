package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderPlayback;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Finish the current interpolation interval before changing playback speed. */
@Mixin(PonderUI.class)
public abstract class PonderUIFrameMixin {
    @Shadow private int extendedTickLength;
    @Shadow private int extendedTickTimer;
    @Shadow private boolean identifyMode;
    @Unique private int differentialcreate$intervalLength;
    @Unique private float differentialcreate$pausedPartial;

    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$rememberInterval(CallbackInfo ci) {
        differentialcreate$intervalLength = extendedTickLength;
    }

    @Inject(method = "tick", at = @At(value = "FIELD",
        target = "Lnet/createmod/ponder/foundation/ui/PonderUI;extendedTickTimer:I",
        opcode = Opcodes.GETFIELD, ordinal = 0))
    private void differentialcreate$finishInterval(CallbackInfo ci) {
        // A disappearing caption (or the slow-reading toggle) can select a
        // shorter interval while its old countdown still runs. Keep the old
        // denominator until that countdown finishes; otherwise partial ticks
        // become negative and every moving section visibly jumps backwards.
        if (extendedTickTimer > 0) extendedTickLength = differentialcreate$intervalLength;
    }

    @Inject(method = "lambda$init$9", at = @At("HEAD"))
    private void differentialcreate$identifyFromDisplayedPose(CallbackInfo ci) {
        var ui = (PonderUI) (Object) this;
        if (!identifyMode) {
            differentialcreate$pausedPartial = PonderUI.getPartialTicks();
            PonderPlayback.clear(ui);
        } else {
            float raw = net.createmod.catnip.animation.AnimationTickHolder.getPartialTicksUI();
            float partial = (raw + extendedTickLength - extendedTickTimer) / (extendedTickLength + 1);
            PonderPlayback.resume(ui, PonderUI.ponderPartialTicksPaused, partial);
        }
    }

    @ModifyExpressionValue(method = "lambda$init$9", at = @At(value = "INVOKE",
        target = "Lnet/createmod/catnip/animation/AnimationTickHolder;getPartialTicksUI()F"))
    private float differentialcreate$pauseActualPartial(float raw) {
        return differentialcreate$pausedPartial;
    }

    @ModifyReturnValue(method = "getPartialTicks", at = @At("RETURN"))
    private static float differentialcreate$resumeActualPartial(float partial) {
        return Minecraft.getInstance().screen instanceof PonderUI ui ? PonderPlayback.partial(ui, partial) : partial;
    }

    @Inject(method = {"replay", "seekToTime"}, at = @At("HEAD"))
    private void differentialcreate$discardResumeOnSeek(CallbackInfo ci) {
        PonderPlayback.clear((PonderUI) (Object) this);
    }
}
