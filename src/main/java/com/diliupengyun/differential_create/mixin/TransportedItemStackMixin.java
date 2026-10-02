package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.IFanProcessingProgress;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TransportedItemStack.class)
public abstract class TransportedItemStackMixin implements IFanProcessingProgress {
	@Shadow public int processingTime;
	@Unique private double differentialcreate$remaining = Double.NaN;
	@Unique private int differentialcreate$published;
	@Override public double differentialcreate$fanRemaining() {
		if (!Double.isFinite(differentialcreate$remaining) || processingTime != differentialcreate$published)
			differentialcreate$remaining = differentialcreate$published = processingTime;
		return differentialcreate$remaining;
	}
	@Override public void differentialcreate$fanRemaining(double remaining) {
		differentialcreate$remaining = remaining;
		processingTime = differentialcreate$published = remaining < 0 ? -1 : (int) Math.ceil(remaining);
	}
	@Inject(method = "getSimilar", at = @At("RETURN"))
	private void differentialcreate$copy(CallbackInfoReturnable<TransportedItemStack> cir) {
		((IFanProcessingProgress) cir.getReturnValue()).differentialcreate$fanRemaining(differentialcreate$fanRemaining());
	}
	@Inject(method = "serializeNBT", at = @At("RETURN"))
	private void differentialcreate$write(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
		cir.getReturnValue().putDouble("DCFanRemaining", differentialcreate$fanRemaining());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private static void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries,
		CallbackInfoReturnable<TransportedItemStack> cir) {
		if (tag.contains("DCFanRemaining"))
			((IFanProcessingProgress) cir.getReturnValue()).differentialcreate$fanRemaining(tag.getDouble("DCFanRemaining"));
	}
	@Inject(method = "clearFanProcessingData", at = @At("RETURN"))
	private void differentialcreate$clear(CallbackInfo ci) { differentialcreate$remaining = Double.NaN; }
}
