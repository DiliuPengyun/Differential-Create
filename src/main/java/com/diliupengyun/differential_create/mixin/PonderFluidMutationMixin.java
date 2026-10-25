package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native hose transfers must commit paid source blocks inside the isolated preview. */
@Mixin({FluidDrainingBehaviour.class, FluidFillingBehaviour.class})
public abstract class PonderFluidMutationMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;isVirtual()Z"))
    private boolean differentialcreate$actualPreviewFluid(SmartBlockEntity be, Operation<Boolean> original) {
        return !PhysicsPreview.enabled(be.getLevel()) && original.call(be);
    }
}
