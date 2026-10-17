package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.CrushingKinetics;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrushingWheelControllerBlock.class)
public class CrushingWheelControllerBlockMixin {
    @Inject(method="updateSpeed",at=@At("HEAD"),cancellable=true)
    private void differentialcreate$pair(BlockState state, LevelAccessor level, BlockPos pos, CallbackInfo ci) {
        if(level.getBlockEntity(pos) instanceof CrushingWheelControllerBlockEntity controller) CrushingKinetics.update(controller);
        ci.cancel();
    }
}
