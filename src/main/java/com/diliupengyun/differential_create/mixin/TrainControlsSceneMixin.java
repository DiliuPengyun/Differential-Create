package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.infrastructure.ponder.scenes.trains.TrainScenes;
import net.createmod.ponder.api.element.InputElementBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve the train, camera and timeline; replace the obsolete scroll-to-limit-speed cue. */
@Mixin(TrainScenes.class)
public abstract class TrainControlsSceneMixin {
    @Redirect(method = "controls", at = @At(value = "INVOKE",
        target = "Lnet/createmod/ponder/api/element/InputElementBuilder;scroll()Lnet/createmod/ponder/api/element/InputElementBuilder;"))
    private static InputElementBuilder differentialcreate$gearLever(InputElementBuilder input) {
        return input.withItem(ModContent.GEAR_LEVER.asStack());
    }
}
