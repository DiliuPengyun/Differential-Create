package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArmInteractionPoint.class)
public abstract class PonderArmInteractionMixin {
    @Shadow protected Level level;
    @Shadow @Final protected BlockPos pos;
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$displayedEndpoint(CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof PhysicsPreview preview && !preview.differentialcreate$physical(pos)) cir.setReturnValue(false);
    }
    @Inject(method = "getHandler", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$inventory(ArmBlockEntity arm, CallbackInfoReturnable<IItemHandler> cir) {
        if (PhysicsPreview.enabled(level)) cir.setReturnValue(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP));
    }
}
