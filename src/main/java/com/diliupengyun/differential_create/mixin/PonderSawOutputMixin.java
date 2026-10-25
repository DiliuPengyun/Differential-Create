package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SawBlockEntity.class)
public abstract class PonderSawOutputMixin {
    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;get(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"), index = 1)
    private BlockPos differentialcreate$displayedOutlet(BlockPos target) {
        var saw = (SawBlockEntity) (Object) this;
        return PhysicsPreview.relative(saw.getLevel(), saw.getBlockPos(), target.subtract(saw.getBlockPos()));
    }
}
