package com.diliupengyun.differential_create.mixin;

import java.util.function.Supplier;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.DisplayWorldSectionInstruction;
import net.createmod.ponder.foundation.instruction.FadeIntoSceneInstruction;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayWorldSectionInstruction.class)
public abstract class PonderSectionJoiningMixin extends FadeIntoSceneInstruction<WorldSectionElement> {
    @Shadow @Final private Supplier<WorldSectionElement> mergeOnto;

    protected PonderSectionJoiningMixin(int ticks, Direction direction, WorldSectionElement element) {
        super(ticks, direction, element);
    }

    @Inject(method = "firstTick", at = @At("RETURN"))
    private void differentialcreate$followDuringFade(PonderScene scene, CallbackInfo ci) {
        if (mergeOnto != null) PonderSimulation.of(scene.getWorld()).joining().add(element, mergeOnto.get());
    }
}
