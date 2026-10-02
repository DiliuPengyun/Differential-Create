package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HarvesterMovementBehaviour.class)
public abstract class HarvesterMovementBehaviourMixin {
	@Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$harvestWork(MovementContext context, BlockPos pos, CallbackInfo ci) {
		if (!ContraptionActorWork.supported(context)) return;
		var state = context.world.getBlockState(pos);
		var self = (HarvesterMovementBehaviour) (Object) this;
		if (!state.isAir() && !AllTags.AllBlockTags.NON_HARVESTABLE.matches(state)
			&& (self.isValidCrop(context.world, pos, state) || self.isValidOther(context.world, pos, state))
			&& ContraptionActorWork.queue(context, "harvest", pos, ContraptionActorWork.CYCLE_TRAVEL)) ci.cancel();
	}
}
