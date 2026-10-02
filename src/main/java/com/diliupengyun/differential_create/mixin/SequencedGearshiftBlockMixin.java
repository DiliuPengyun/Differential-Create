package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** run() queues the new ratio; changing only the instruction must retain the input and context. */
@Mixin(SequencedGearshiftBlock.class)
public abstract class SequencedGearshiftBlockMixin {
	@Inject(method = "areStatesKineticallyEquivalent", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$instructionState(BlockState oldState, BlockState newState, CallbackInfoReturnable<Boolean> cir) {
		if (oldState.getBlock() == newState.getBlock()
			&& oldState.getValue(BlockStateProperties.HORIZONTAL_AXIS) == newState.getValue(BlockStateProperties.HORIZONTAL_AXIS)
			&& oldState.getValue(SequencedGearshiftBlock.VERTICAL) == newState.getValue(SequencedGearshiftBlock.VERTICAL))
			cir.setReturnValue(true);
	}
}
