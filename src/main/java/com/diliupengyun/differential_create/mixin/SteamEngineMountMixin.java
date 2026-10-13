package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SteamEngineBlock.class)
public abstract class SteamEngineMountMixin {
    @Inject(method = "canAttach", at = @At("RETURN"), cancellable = true)
    private static void differentialcreate$solidMount(LevelReader level, BlockPos pos, Direction direction,
                                                     CallbackInfoReturnable<Boolean> cir) {
        BlockPos backing = pos.relative(direction);
        if (!cir.getReturnValue() && level.getBlockState(backing).isFaceSturdy(level, backing, direction.getOpposite())) cir.setReturnValue(true);
    }
}
