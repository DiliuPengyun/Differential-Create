package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MachinePhase;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A complete input/output movement takes 1024 local RPM-ticks, at any speed. */
@Mixin(ArmBlockEntity.class)
public abstract class ArmBlockEntityMixin implements ShaftWork {
	@Shadow float chasedPointProgress;
	@Unique private double differentialcreate$progress = Double.NaN;
	@Unique private float differentialcreate$published = Float.NaN;
	@Unique private boolean differentialcreate$event;
	@Unique private double differentialcreate$progress() {
		if (chasedPointProgress != differentialcreate$published || !Double.isFinite(differentialcreate$progress))
			differentialcreate$progress = differentialcreate$published = chasedPointProgress;
		return differentialcreate$progress;
	}
	@Override public double differentialcreate$remainingTravel() {
		return MachinePhase.of((ArmBlockEntity) (Object) this).startsWith("MOVE_TO_")
			? Math.max(0, 1 - differentialcreate$progress()) * 1024 : Double.POSITIVE_INFINITY;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		var self = (ArmBlockEntity) (Object) this;
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		differentialcreate$progress = complete ? 1 : differentialcreate$progress() + travel / 1024;
		chasedPointProgress = differentialcreate$published = (float) differentialcreate$progress;
		self.setChanged();
		if (complete) MachineWork.event(() -> {
			if (self.isRemoved()) return;
			differentialcreate$event = true;
			try { self.tick(); } finally { differentialcreate$event = false; }
		});
	}
	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V"))
	private void differentialcreate$once(ArmBlockEntity self, Operation<Void> original) {
		if (!differentialcreate$event) original.call(self);
	}
	@Inject(method = "tickMovementProgress", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$defer(CallbackInfoReturnable<Boolean> cir) {
		if (differentialcreate$event) cir.setReturnValue(true);
		else if (MachineWork.deferred((ArmBlockEntity) (Object) this)
			&& Double.isFinite(differentialcreate$remainingTravel())) cir.setReturnValue(false);
	}
	@ModifyExpressionValue(method = "tickMovementProgress", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(FF)F"))
    private float differentialcreate$uncapped(float original) {
        return (float) com.diliupengyun.differential_create.kinetics.PhysicsPreview.travel((ArmBlockEntity) (Object) this);
    }
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCArmProgress", differentialcreate$progress());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$published = chasedPointProgress;
		differentialcreate$progress = tag.getDouble("DCArmProgress");
	}
}
