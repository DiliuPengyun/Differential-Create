package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.WorkAccumulator;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PressingBehaviour.class)
public abstract class PressingBehaviourMixin {
	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();

	@ModifyExpressionValue(method = "applyInWorld", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/press/PressingBehaviour$PressingBehaviourSpecifics;tryProcessInWorld(Lnet/minecraft/world/entity/item/ItemEntity;Z)Z"))
	private boolean differentialcreate$continueAfterFailedItem(boolean processed, @Local(ordinal = 0) LocalBooleanRef bulk) {
		// This local only controls the loop's break, not the recipe's batch size.
		// Skip failed items, then restore the configured limit after each success.
		bulk.set(!processed || ((PressingBehaviour) (Object) this).specifics.canProcessInBulk());
		return processed;
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/press/PressingBehaviour;getRunningTickSpeed()I"))
	private int differentialcreate$shaftTravel(PressingBehaviour self) {
		float speed = Math.abs(self.specifics.getKineticSpeed());
		if (speed == 0) return 0;
		if (!self.getWorld().isClientSide) self.blockEntity.setChanged();
		int progress = differentialcreate$work.advance(speed / 8.0);
		// Leave the one-shot contact marker even at sub-integer speeds. The
		// borrowed unit is repaid before any subsequent progress is allowed.
		if (self.runningTicks == 120 && progress == 0) {
			differentialcreate$work.borrowOne();
			return 1;
		}
		return progress;
	}
	@Inject(method = "start", at = @At("HEAD"))
	private void differentialcreate$start(PressingBehaviour.Mode mode, CallbackInfo ci) { differentialcreate$work.reset(); }
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCWorkFraction", differentialcreate$work.remainder());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$work.restore(tag.getDouble("DCWorkFraction"));
	}
}
