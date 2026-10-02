package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeployerMovementBehaviour.class)
public abstract class DeployerMovementBehaviourMixin {
	@Inject(method = "visitNewPosition", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerMovementBehaviour;activate(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/content/kinetics/deployer/DeployerFakePlayer;Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity$Mode;)V"), cancellable = true)
	private void differentialcreate$queueAction(MovementContext context, BlockPos pos, CallbackInfo ci) {
		if (ContraptionActorWork.queue(context, "deploy", pos, ContraptionActorWork.CYCLE_TRAVEL)) ci.cancel();
	}
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$waitForWork(MovementContext context, CallbackInfo ci) {
		if (ContraptionActorWork.supported(context) && ContraptionActorWork.of(context).pending()) {
			context.stall = true; ci.cancel();
		}
	}
	@Inject(method = "activate", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$chargeRepeatedBreaking(MovementContext context, BlockPos pos, DeployerFakePlayer player, @Coerce Object mode, CallbackInfo ci) {
		if (ContraptionActorWork.queue(context, "deploy", pos, ContraptionActorWork.CYCLE_TRAVEL)) ci.cancel();
	}
	@Inject(method = "cancelStall", at = @At("RETURN"))
	private void differentialcreate$cancelWork(MovementContext context, CallbackInfo ci) {
		if (!context.world.isClientSide) ContraptionActorWork.of(context).cancel();
	}
}
