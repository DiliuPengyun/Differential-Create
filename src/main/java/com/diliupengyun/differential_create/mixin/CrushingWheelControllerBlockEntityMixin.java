package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MachineActivity;
import com.diliupengyun.differential_create.kinetics.CrushingKinetics;
import com.diliupengyun.differential_create.kinetics.CrushingWork;
import com.diliupengyun.differential_create.kinetics.ICrushingDynamics;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrushingWheelControllerBlockEntity.class)
public abstract class CrushingWheelControllerBlockEntityMixin implements ShaftWork, ICrushingDynamics {
	@Unique private double differentialcreate$reaction;
	@Override public double differentialcreate$reaction() { return differentialcreate$reaction; }
	@Override public void differentialcreate$reaction(double torque) {
		if (Math.abs(torque - differentialcreate$reaction) <= 1e-8) return;
		differentialcreate$reaction = torque;
		((CrushingWheelControllerBlockEntity) (Object) this).sendData();
	}
	@Unique private double differentialcreate$remaining = Double.NaN;
	@Unique private float differentialcreate$published = Float.NaN;
	@Unique private boolean differentialcreate$event;
	@Unique private double differentialcreate$remaining() {
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		if (self.inventory.remainingTime != differentialcreate$published || !Double.isFinite(differentialcreate$remaining)) {
			differentialcreate$remaining = self.inventory.remainingTime;
			differentialcreate$published = self.inventory.remainingTime;
		}
		return differentialcreate$remaining;
	}
	@Override public double differentialcreate$remainingTravel() {
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		if (!(self.isOccupied() && !self.hasEntity() && self.inventory.remainingTime > 0)) return Double.POSITIVE_INFINITY;
		return Math.max(0, differentialcreate$remaining() - (self.inventory.appliedRecipe ? 0 : 20))
			* CrushingWork.travelPerProgress(self.inventory.appliedRecipe ? 1 : self.inventory.getStackInSlot(0).getCount());
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		differentialcreate$remaining = differentialcreate$remaining() - travel
			/ CrushingWork.travelPerProgress(self.inventory.appliedRecipe ? 1 : self.inventory.getStackInSlot(0).getCount());
		self.inventory.remainingTime = differentialcreate$published = (float) differentialcreate$remaining;
		self.setChanged();
		if (!complete) return;
		MachineWork.event(() -> {
			if (self.isRemoved()) return;
			// Cross Create's strict '<' marker only for its callback; no extra shaft work is charged.
			self.inventory.remainingTime = self.inventory.appliedRecipe ? 0 : Math.nextDown((float) 20);
			boolean processing = !self.inventory.appliedRecipe;
			differentialcreate$event = true;
			try {
				self.tick();
				// ProcessingInventory.clear() ends the countdown inside applyRecipe; export is a zero-work event.
				if (processing && self.inventory.appliedRecipe && self.inventory.remainingTime <= 0) self.tick();
			} finally { differentialcreate$event = false; }
			differentialcreate$remaining = self.inventory.remainingTime;
			differentialcreate$published = self.inventory.remainingTime;
		});
	}
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;tick()V"))
	private void differentialcreate$once(CrushingWheelControllerBlockEntity self, Operation<Void> original) {
		if (!differentialcreate$event) original.call(self);
	}
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		if (!differentialcreate$event && !self.getLevel().isClientSide && Double.isFinite(differentialcreate$remainingTravel())) ci.cancel();
	}
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
	private float differentialcreate$shaftTravel(float original) {
		if (differentialcreate$event) return 0;
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		return (float) (Math.abs(self.crushingspeed) * 4 / (self.inventory.appliedRecipe ? 1 : CrushingWork.batchFactor(self.inventory.getStackInSlot(0).getCount())));
	}
	@ModifyExpressionValue(method = "tick", at = @At(value = "FIELD", target = "Lcom/simibubi/create/content/kinetics/crusher/CrushingWheelControllerBlockEntity;crushingspeed:F"))
	private float differentialcreate$atRest(float speed) { return differentialcreate$event && speed == 0 ? Float.MIN_NORMAL : speed; }
	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$captureWork(CallbackInfo ci) {
		if (!differentialcreate$event) {
			CrushingKinetics.update((CrushingWheelControllerBlockEntity) (Object) this);
			MachineActivity.beforeCrusherTick((CrushingWheelControllerBlockEntity) (Object) this);
		}
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCProcessingRemaining", differentialcreate$remaining());
		if (packet) tag.putDouble("DCCrushingReaction", differentialcreate$reaction);
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		CrushingWheelControllerBlockEntity self = (CrushingWheelControllerBlockEntity) (Object) this;
		differentialcreate$published = self.inventory.remainingTime;
		differentialcreate$remaining = tag.contains("DCProcessingRemaining") ? tag.getDouble("DCProcessingRemaining") : Double.NaN;
		differentialcreate$reaction = tag.getDouble("DCCrushingReaction");
	}
}
