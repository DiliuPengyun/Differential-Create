package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientSawAnimations;
import com.diliupengyun.differential_create.client.SawItemAnimation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SawBlockEntity.class)
public abstract class SawItemAnimationMixin implements SawItemAnimation.Owner {
    @Unique private final SawItemAnimation differentialcreate$item = new SawItemAnimation();
    @Override public SawItemAnimation differentialcreate$itemAnimation() { return differentialcreate$item; }
    @WrapMethod(method = "tick")
    private void differentialcreate$actualItemMotion(Operation<Void> original) {
        var self = (SawBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide) { original.call(); return; }
        differentialcreate$item.begin(self);
        original.call();
        differentialcreate$item.finish(self);
        if (self.getLevel() instanceof PonderLevel) differentialcreate$item.advance(false, 1);
        else ClientSawAnimations.track(self, differentialcreate$item);
    }
}
