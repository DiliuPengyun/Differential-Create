package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import net.createmod.catnip.math.BlockFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CapManipulationBehaviourBase.class)
public abstract class PonderCapabilityTargetMixin {
    @Inject(method = "getTarget", at = @At("RETURN"), cancellable = true)
    private void differentialcreate$displayedTarget(CallbackInfoReturnable<BlockFace> cir) {
        var self = (CapManipulationBehaviourBase<?, ?>) (Object) this;
        if (!PhysicsPreview.enabled(self.getWorld())) return;
        var face = cir.getReturnValue();
        var target = PhysicsPreview.neighbor(self.getWorld(), face.getPos(), face.getFace());
        cir.setReturnValue(new BlockFace(target.relative(face.getFace().getOpposite()), face.getFace()));
    }
}
