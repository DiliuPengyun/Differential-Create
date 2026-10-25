package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.AllSoundEvents;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.diliupengyun.differential_create.kinetics.MachinePhase;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.diliupengyun.differential_create.kinetics.WorkAccumulator;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MechanicalCrafterBlockEntity.class)
public abstract class MechanicalCrafterBlockEntityMixin implements ShaftWork {

	@Shadow protected int countDown;
	@Unique private boolean differentialcreate$event;
	@Unique private int differentialcreate$step;
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V"))
	private void differentialcreate$once(MechanicalCrafterBlockEntity self,
		Operation<Void> original) {
		if (!differentialcreate$event) original.call(self);
	}
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		if (!differentialcreate$event && MachineWork.deferred((MechanicalCrafterBlockEntity) (Object) this)
			&& differentialcreate$working()) ci.cancel();
	}
	@Unique private boolean differentialcreate$working() {
		String phase = MachinePhase.of((MechanicalCrafterBlockEntity) (Object) this);
		return phase.equals("ASSEMBLING") || phase.equals("EXPORTING") || phase.equals("CRAFTING");
	}
	@Override public double differentialcreate$remainingTravel() {
		return differentialcreate$working() ? Math.max(0, countDown - differentialcreate$work.remainder()) : Double.POSITIVE_INFINITY;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		MechanicalCrafterBlockEntity self = (MechanicalCrafterBlockEntity) (Object) this;
		String phase = MachinePhase.of(self);
		int before = countDown;
		countDown -= differentialcreate$work.advance(travel);
		if (complete) { countDown = 0; differentialcreate$work.reset(); }
		self.setChanged();
		if (phase.equals("CRAFTING") && before >= 1000 && countDown < 1000) {
			AllSoundEvents.CRAFTER_CLICK.playOnServer(self.getLevel(), self.getBlockPos(), 1, 2);
			AllSoundEvents.CRAFTER_CRAFT.playOnServer(self.getLevel(), self.getBlockPos());
		}
		if (complete) MachineWork.event(() -> {
			if (self.isRemoved() || !phase.equals(MachinePhase.of(self))) return;
			differentialcreate$step = 1; // Activate Create's strict '< 0' callback at an exact zero budget.
			differentialcreate$event = true;
			try { self.tick(); } finally { differentialcreate$event = false; }
		});
	}

	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();
	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/crafter/MechanicalCrafterBlockEntity;getCountDownSpeed()I"))
	private int differentialcreate$shaftTravel(MechanicalCrafterBlockEntity self) {
		if (!self.getLevel().isClientSide) self.setChanged();
		if (differentialcreate$event) return differentialcreate$step;
        return differentialcreate$work.advance(com.diliupengyun.differential_create.kinetics.PhysicsPreview.travel(self));
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCWorkFraction", differentialcreate$work.remainder());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$work.restore(tag.getDouble("DCWorkFraction"));
	}
}
