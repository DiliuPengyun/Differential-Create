package com.diliupengyun.differential_create.mixin;

import java.util.UUID;
import com.diliupengyun.differential_create.kinetics.ISequenceContext;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.SequenceContext;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SequenceContext.class)
public abstract class SequenceContextMixin implements ISequenceContext {
	@Unique private UUID differentialcreate$id = UUID.randomUUID();
	@Override public UUID differentialcreate$instructionId() { return differentialcreate$id; }
	@Override public void differentialcreate$instructionId(UUID id) { differentialcreate$id = id; }
	@Inject(method = "serializeNBT", at = @At("RETURN"))
	private void differentialcreate$write(CallbackInfoReturnable<CompoundTag> cir) {
		cir.getReturnValue().putUUID("DCInstructionId", differentialcreate$id);
	}
	@Inject(method = "fromNBT", at = @At("RETURN"))
	private static void differentialcreate$read(CompoundTag tag, CallbackInfoReturnable<SequenceContext> cir) {
		if (cir.getReturnValue() != null && tag.hasUUID("DCInstructionId"))
			((ISequenceContext) (Object) cir.getReturnValue()).differentialcreate$instructionId(tag.getUUID("DCInstructionId"));
	}
}
