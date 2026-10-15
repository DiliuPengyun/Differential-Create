package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SawBlockEntity.class)
public abstract class SawBlockEntityMixin implements ShaftWork {
	@Unique private double differentialcreate$remaining = Double.NaN;
	@Unique private float differentialcreate$published = Float.NaN;
	@Unique private boolean differentialcreate$event;
	@Unique private double differentialcreate$remaining() {
		SawBlockEntity self = (SawBlockEntity) (Object) this;
		if (self.inventory.remainingTime != differentialcreate$published || !Double.isFinite(differentialcreate$remaining)) {
			differentialcreate$remaining = self.inventory.remainingTime;
			differentialcreate$published = self.inventory.remainingTime;
		}
		return differentialcreate$remaining;
	}
	@Override public double differentialcreate$remainingTravel() {
		SawBlockEntity self = (SawBlockEntity) (Object) this;
		if (!(MachineActivity.working(self))) return Double.POSITIVE_INFINITY;
		return Math.max(0, differentialcreate$remaining() - (self.inventory.appliedRecipe ? 0 : 5)) * (24);
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		SawBlockEntity self = (SawBlockEntity) (Object) this;
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		differentialcreate$remaining = differentialcreate$remaining() - travel / (24);
		self.inventory.remainingTime = differentialcreate$published = (float) differentialcreate$remaining;
		self.setChanged();
		if (!complete) return;
		MachineWork.event(() -> {
			if (self.isRemoved()) return;
			// Cross Create's strict '<' marker only for its callback; no extra shaft work is charged.
			self.inventory.remainingTime = self.inventory.appliedRecipe ? 0 : Math.nextDown((float) 5);
			differentialcreate$event = true;
			try { self.tick(); } finally { differentialcreate$event = false; }
			differentialcreate$remaining = self.inventory.remainingTime;
			differentialcreate$published = self.inventory.remainingTime;
		});
	}
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/BlockBreakingKineticBlockEntity;tick()V"))
	private void differentialcreate$once(SawBlockEntity self, Operation<Void> original) {
		if (!differentialcreate$event) original.call(self);
	}
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		SawBlockEntity self = (SawBlockEntity) (Object) this;
		if (!differentialcreate$event && MachineWork.deferred(self) && Double.isFinite(differentialcreate$remainingTravel())) ci.cancel();
	}
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
	private float differentialcreate$shaftTravel(float original) {
		if (differentialcreate$event) return 0;
		SawBlockEntity self = (SawBlockEntity) (Object) this;
		return Math.abs(self.getSpeed()) / 24;
	}
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/saw/SawBlockEntity;getSpeed()F"))
	private float differentialcreate$atRest(float speed) { return differentialcreate$event && speed == 0 ? Float.MIN_NORMAL : speed; }
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCProcessingRemaining", differentialcreate$remaining());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		SawBlockEntity self = (SawBlockEntity) (Object) this;
		differentialcreate$published = self.inventory.remainingTime;
		differentialcreate$remaining = tag.contains("DCProcessingRemaining") ? tag.getDouble("DCProcessingRemaining") : Double.NaN;
	}
}
