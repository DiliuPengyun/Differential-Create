package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.MinecartElement;
import net.createmod.ponder.foundation.PonderSceneBuilder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.createmod.ponder.foundation.PonderSceneBuilder$PonderSpecialInstructions")
public abstract class PonderCartInstructionsMixin {
    @Shadow @Final private PonderSceneBuilder this$0;
    @Inject(method = "moveCart", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$drive(ElementLink<MinecartElement> link, Vec3 delta, int duration, CallbackInfo ci) {
        if (duration <= 0) return; // Revealing, placing or removing the demonstration is an operation.
        this$0.addInstruction(scene -> {
            var cart = scene.resolve(link);
            if (cart != null) PonderSimulation.of(scene.getWorld()).cart(cart, delta, duration);
        });
        ci.cancel();
    }
}
