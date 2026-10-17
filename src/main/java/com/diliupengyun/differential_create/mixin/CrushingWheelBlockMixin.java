package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.CrushingKinetics;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrushingWheelBlock.class)
public class CrushingWheelBlockMixin {
    @Inject(method="updateControllers",at=@At("HEAD"),cancellable=true)
    private void differentialcreate$pair(BlockState state, Level level, BlockPos pos, Direction side, CallbackInfo ci) {
        CrushingKinetics.updateControllers(state,level,pos,side); ci.cancel();
    }
}
