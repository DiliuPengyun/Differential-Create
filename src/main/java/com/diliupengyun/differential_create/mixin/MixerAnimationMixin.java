package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientMixerAnimations;
import com.diliupengyun.differential_create.client.MixerHeadAnimation;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.kinetics.MixerStroke;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MechanicalMixerBlockEntity.class)
public abstract class MixerAnimationMixin implements MixerHeadAnimation.Owner {
    @Unique private final MixerHeadAnimation differentialcreate$head = new MixerHeadAnimation();
    @Override public MixerHeadAnimation differentialcreate$headAnimation() { return differentialcreate$head; }

    @WrapMethod(method = "tick")
    private void differentialcreate$sampleActualMotion(Operation<Void> original) {
        var self = (MechanicalMixerBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide) { original.call(); return; }
        var stroke = (MixerStroke) self;
        int gearing = !self.running ? 1 : self.runningTicks >= 15 && self.runningTicks <= 20 ? 4 : 2;
        differentialcreate$head.begin(stroke.differentialcreate$stroke(), gearing);
        original.call();
        differentialcreate$head.finish(stroke.differentialcreate$stroke());
        if (self.getLevel() instanceof PonderLevel world) {
            var physics = PonderKinetics.of(world);
            Double start = physics.angle(self, 0), end = physics.angle(self, 1);
            if (start == null || end == null) differentialcreate$head.advanceFallback(0, false, 1);
            else differentialcreate$head.advance(start, end, false, 1);
        } else ClientMixerAnimations.track(self, differentialcreate$head);
    }

    @Inject(method = "getRenderedHeadOffset", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$interpolateEarnedTravel(float partial, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(differentialcreate$head.offset(((MixerStroke) this).differentialcreate$stroke(), partial));
    }
}
