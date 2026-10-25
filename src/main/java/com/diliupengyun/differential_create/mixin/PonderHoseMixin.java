package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

@Mixin(HosePulleyBlockEntity.class)
public abstract class PonderHoseMixin {
    @WrapOperation(method = "getMovementSpeed", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/utility/ServerSpeedProvider;get()F"))
    private float differentialcreate$previewClock(Operation<Float> original) {
        return ((HosePulleyBlockEntity) (Object) this).getLevel() instanceof net.createmod.ponder.api.level.PonderLevel ? 1 : original.call();
    }
    @ModifyReturnValue(method = "lambda$new$0", at = @At("RETURN"))
    private BlockPos differentialcreate$displayedRoot(BlockPos original) {
        var self = (HosePulleyBlockEntity) (Object) this;
        if (self.getLevel() instanceof net.createmod.ponder.api.level.PonderLevel preview)
            return BlockPos.containing(com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(preview).point(self.getBlockPos())
                .add(net.minecraft.world.phys.Vec3.atLowerCornerOf(original.subtract(self.getBlockPos()))).add(.001, .001, .001));
        return original;
    }
}
