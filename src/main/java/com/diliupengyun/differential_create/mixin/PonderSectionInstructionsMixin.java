package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderPhysicalSection;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderSceneBuilder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.createmod.ponder.foundation.PonderSceneBuilder$PonderWorldInstructions")
public abstract class PonderSectionInstructionsMixin {
    @Shadow @Final private PonderSceneBuilder this$0;
    @ModifyVariable(method = "showIndependentSection", at = @At("HEAD"), argsOnly = true)
    private net.createmod.ponder.api.scene.Selection differentialcreate$trainSupply(net.createmod.ponder.api.scene.Selection selection) {
        return com.diliupengyun.differential_create.client.ponder.PonderNativeTrain.prepare(this$0, selection);
    }
    @Inject(method = "rotateSection", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$rotation(ElementLink<WorldSectionElement> section, double x, double y, double z,
        int duration, CallbackInfo ci) {
        this$0.addInstruction(new PonderPhysicalSection(section, new Vec3(x, y, z), duration, true)); ci.cancel();
    }
    @Inject(method = "moveSection", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$translation(ElementLink<WorldSectionElement> section, Vec3 delta, int duration, CallbackInfo ci) {
        this$0.addInstruction(new PonderPhysicalSection(section, delta, duration, false)); ci.cancel();
    }
}
