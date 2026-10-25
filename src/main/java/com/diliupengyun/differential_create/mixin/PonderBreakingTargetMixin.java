package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({DrillBlockEntity.class, SawBlockEntity.class})
public abstract class PonderBreakingTargetMixin {
    @Inject(method = "getBreakingPos", at = @At("RETURN"), cancellable = true)
    private void differentialcreate$actualContact(CallbackInfoReturnable<BlockPos> cir) {
        var be = (BlockEntity) (Object) this;
        cir.setReturnValue(PhysicsPreview.relative(be.getLevel(), be.getBlockPos(), cir.getReturnValue().subtract(be.getBlockPos())));
    }
}
