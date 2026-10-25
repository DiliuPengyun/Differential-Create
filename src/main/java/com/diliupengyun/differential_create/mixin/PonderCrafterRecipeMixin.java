package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Virtual crafters use their real grid, recipe manager and output, like world crafters. */
@Mixin(MechanicalCrafterBlockEntity.class)
public abstract class PonderCrafterRecipeMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/crafter/MechanicalCrafterBlockEntity;isVirtual()Z", ordinal = 1))
    private boolean differentialcreate$realRecipe(boolean virtual) {
        return virtual && !PhysicsPreview.enabled(((MechanicalCrafterBlockEntity) (Object) this).getLevel());
    }
}
