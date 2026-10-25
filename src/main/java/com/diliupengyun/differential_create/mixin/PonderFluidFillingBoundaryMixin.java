package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The native cutaway reservoir has a finite domain, rather than an open abyss outside its drawing. */
@Mixin(FluidFillingBehaviour.class)
public abstract class PonderFluidFillingBoundaryMixin {
    @WrapOperation(method = "getAtPos", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState differentialcreate$cutawayWall(Level level, BlockPos pos, Operation<BlockState> original) {
        if (level instanceof PonderLevel preview && preview.scene != null
            && (preview.scene.getId().getPath().equals("hose_pulley") || preview.scene.getId().getPath().equals("hose_pulley_level"))
            && (pos.getX() < 0 || pos.getX() > 2 || pos.getZ() < 0 || pos.getZ() > 2 || pos.getY() < 1))
            return Blocks.BEDROCK.defaultBlockState();
        return original.call(level, pos);
    }
}
