package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.diliupengyun.differential_create.kinetics.IContraptionBreaker;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBreakingMovementBehaviour.class)
public abstract class BlockBreakingMovementBehaviourMixin implements IContraptionBreaker {
	@Shadow protected abstract void destroyBlock(MovementContext context, BlockPos pos);
	@Shadow protected abstract void onBlockBroken(MovementContext context, BlockPos pos, BlockState state);
	@Shadow protected abstract boolean shouldDestroyStartBlock(BlockState state);
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$holdQueuedOperation(MovementContext context, CallbackInfo ci) {
		if (ContraptionActorWork.supported(context) && ContraptionActorWork.of(context).pending()) {
			context.stall = false; ci.cancel();
		}
	}
	@Inject(method = "tickBreaker", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$physicalBreaking(MovementContext context, CallbackInfo ci) {
		if (!ContraptionActorWork.supported(context)) return;
		var work = ContraptionActorWork.of(context);
		if (!work.motionManaged()) work.differentialcreate$remainingTravel();
		context.stall = false;
		ci.cancel();
	}
	@Inject(method = "cancelStall", at = @At("RETURN"))
	private void differentialcreate$clearWork(MovementContext context, CallbackInfo ci) {
		if (!context.world.isClientSide) ContraptionActorWork.of(context).cancel();
	}
	@Override public void differentialcreate$finishBreaking(MovementContext context, BlockPos pos) {
		context.world.destroyBlockProgress(context.data.getInt("BreakerId"), pos, -1);
		BlockPos target = pos;
		while (context.world.getBlockState(target.above()).getBlock() instanceof FallingBlock) target = target.above();
		var state = context.world.getBlockState(target);
		context.stall = false;
		if (shouldDestroyStartBlock(state)) destroyBlock(context, target);
		onBlockBroken(context, pos, state);
		context.data.remove("Progress"); context.data.remove("TicksUntilNextProgress"); context.data.remove("BreakingPos");
	}
}
