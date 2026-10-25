package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PloughMovementBehaviour.class)
public abstract class PonderPloughMixin extends com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour {
    private static class PreviewUse extends UseOnContext {
        PreviewUse(MovementContext ctx, BlockPos pos) {
            super(ctx.world, null, InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_HOE),
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, .5, 0), Direction.UP, pos, false));
        }
    }
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$till(MovementContext ctx, BlockPos pos, CallbackInfo ci) {
        if (!PhysicsPreview.enabled(ctx.world)) return;
        ci.cancel();
        // Preserve the normal breaking operation before trying the hoe action.
        super.visitNewPosition(ctx, pos);
        if (!ctx.world.getBlockState(pos).isAir()) return;
        var ground = pos.below(); var use = new PreviewUse(ctx, ground);
        var tilled = ctx.world.getBlockState(ground).getToolModifiedState(use, ItemAbilities.HOE_TILL, true);
        if (tilled == null) return;
        if (ContraptionActorWork.queue(ctx, "plough", pos, ContraptionActorWork.CYCLE_TRAVEL)) return;
        if (ContraptionActorWork.of(ctx).firing()) ctx.world.setBlockAndUpdate(ground, tilled);
    }
}
