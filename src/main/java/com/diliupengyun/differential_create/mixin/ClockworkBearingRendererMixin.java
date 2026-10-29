package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.BearingAnimation;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClockworkBearingBlockEntity.class)
public abstract class ClockworkBearingRendererMixin {
    @Inject(method = "getHourArmSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$hourPlayback(CallbackInfoReturnable<Float> cir) { differentialcreate$playback(cir, true); }
    @Inject(method = "getMinuteArmSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$minutePlayback(CallbackInfoReturnable<Float> cir) { differentialcreate$playback(cir, false); }
    private void differentialcreate$playback(CallbackInfoReturnable<Float> cir, boolean hour) {
        var self = (ClockworkBearingBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide) return;
        var clock = (ClockworkBearingAccessor) self;
        var entity = hour ? clock.differentialcreate$hour() : clock.differentialcreate$minute();
        if (entity != null && ClientNetworkSpeeds.clockRotationOf(self, entity.getId()) != null) cir.setReturnValue(0f);
    }
    @Inject(method = "getInterpolatedAngle", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$topPose(float partialTicks, CallbackInfoReturnable<Float> cir) {
        var self = (ClockworkBearingBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide) return;
        var root = ((ClockworkBearingAccessor) self).differentialcreate$hour();
        if (root == null) return;
        var frame = ClientNetworkSpeeds.clockRotationOf(self, root.getId());
        if (frame != null) cir.setReturnValue(BearingAnimation.angleAt(frame, partialTicks + 1));
    }
}
