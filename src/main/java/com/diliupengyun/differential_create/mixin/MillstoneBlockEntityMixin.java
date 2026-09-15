package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Shadow;
import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
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
public abstract class MillstoneBlockEntityMixin implements ShaftWork {
	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();
	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$pauseBlockedOutput(CallbackInfo ci) {
		MillstoneBlockEntity self = (MillstoneBlockEntity) (Object) this;
		if (MachineActivity.millOutputBlocked(self)
			|| MachineWork.deferred(self) && self.timer > 0)
			ci.cancel();
	}

	@Shadow private void process() { throw new AssertionError(); }
	@Override public double differentialcreate$remainingTravel() {
		MillstoneBlockEntity self = (MillstoneBlockEntity) (Object) this;
		return MachineActivity.working(self)
			? Math.max(0, self.timer - differentialcreate$work.remainder()) * 16 : Double.POSITIVE_INFINITY;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		MillstoneBlockEntity self = (MillstoneBlockEntity) (Object) this;
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		self.timer -= differentialcreate$work.advance(travel / 16);
		self.setChanged();
		if (complete) {
			self.timer = 0; differentialcreate$work.reset();
			MachineWork.event(() -> { if (!self.isRemoved()) process(); });
		}
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
