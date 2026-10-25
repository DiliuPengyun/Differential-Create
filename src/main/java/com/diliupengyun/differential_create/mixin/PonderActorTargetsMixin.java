package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native assembly removes these blocks from terrain; previews retain them for rendering. */
@Mixin(BlockBreakingMovementBehaviour.class)
public abstract class PonderActorTargetsMixin {
    @Inject(method = "canBreak", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$terrainOnly(Level world, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (world instanceof PonderLevel preview && (PonderSimulation.of(preview).mountedBlock(pos)
            || !PonderSimulation.of(preview).physical(preview, pos))) cir.setReturnValue(false);
    }
}
