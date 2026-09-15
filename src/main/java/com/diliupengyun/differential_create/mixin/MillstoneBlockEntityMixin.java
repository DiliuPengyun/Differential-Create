package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.WorkAccumulator;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MillstoneBlockEntity.class)
public abstract class MillstoneBlockEntityMixin {
	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();
	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$pauseBlockedOutput(CallbackInfo ci) {
		if (com.diliupengyun.differential_create.kinetics.MachineActivity.millOutputBlocked((MillstoneBlockEntity) (Object) this))
			ci.cancel();
	}
	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/millstone/MillstoneBlockEntity;getProcessingSpeed()I"))
	private int differentialcreate$shaftTravel(MillstoneBlockEntity self) {
		if (!self.getLevel().isClientSide) self.setChanged();
		return differentialcreate$work.advance(Math.abs(self.getSpeed()) / 16.0);
	}
	@Inject(method = "process", at = @At("RETURN"))
	private void differentialcreate$complete(CallbackInfo ci) { differentialcreate$work.reset(); }
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCWorkFraction", differentialcreate$work.remainder());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$work.restore(tag.getDouble("DCWorkFraction"));
	}
}
