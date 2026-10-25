package com.diliupengyun.differential_create.mixin;

import net.minecraft.world.level.block.SoundType;
import com.simibubi.create.AllSoundEvents;
import org.spongepowered.asm.mixin.Shadow;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
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
public abstract class PressingBehaviourMixin implements ShaftWork {
	@Unique private double differentialcreate$position;
	@Unique private boolean differentialcreate$contact;
	@Unique private long differentialcreate$beltContactTick = Long.MIN_VALUE;
	@Shadow int entityScanCooldown;
	@Shadow protected abstract void applyInWorld();
	@Shadow protected abstract void applyOnBasin();
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/BeltProcessingBehaviour;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		PressingBehaviour self = (PressingBehaviour) (Object) this;
		if (self.running && self.blockEntity instanceof KineticBlockEntity be
			&& MachineWork.deferred(be)) ci.cancel();
	}
	@Override public double differentialcreate$remainingTravel() {
		PressingBehaviour self = (PressingBehaviour) (Object) this;
		if (!self.running || self.mode == PressingBehaviour.Mode.BELT
			&& differentialcreate$beltContactTick == self.getWorld().getGameTime()) return Double.POSITIVE_INFINITY;
		return Math.max(0, (differentialcreate$contact ? 240 : 120) - differentialcreate$position) * 8;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		PressingBehaviour self = (PressingBehaviour) (Object) this;
		double boundary = differentialcreate$contact ? 240 : 120;
		differentialcreate$position = Math.min(boundary, differentialcreate$position + travel / 8);
		self.prevRunningTicks = self.runningTicks;
		self.runningTicks = Math.max(differentialcreate$contact ? 121 : 0, (int) Math.floor(differentialcreate$position));
		self.blockEntity.setChanged();
		if ((boundary - differentialcreate$position) * 8 > MachineWork.EPSILON) return;
		differentialcreate$position = boundary;
		MachineWork.event(() -> {
			if (self.blockEntity.isRemoved()) return;
			if (!differentialcreate$contact) {
				differentialcreate$contact = true;
				self.runningTicks = 120;
				if (self.inWorld()) applyInWorld();
				if (self.onBasin()) applyOnBasin();
				if (self.mode == PressingBehaviour.Mode.BELT) differentialcreate$beltContactTick = self.getWorld().getGameTime();
				else self.runningTicks = 121; // Contact is an event, not another unit of shaft work.
				var support = self.getPos().below(2);
				if (self.getWorld().getBlockState(support).getSoundType(self.getWorld(), support, null) == SoundType.WOOL)
					AllSoundEvents.MECHANICAL_PRESS_ACTIVATION_ON_BELT.playOnServer(self.getWorld(), self.getPos());
				else AllSoundEvents.MECHANICAL_PRESS_ACTIVATION.playOnServer(self.getWorld(), self.getPos(), .5f,
					.75f + Math.abs(self.specifics.getKineticSpeed()) / 1024f);
			} else {
				self.runningTicks = 240;
				entityScanCooldown = Math.max(entityScanCooldown, 2);
				self.finished = true;
				self.running = false;
				self.particleItems.clear();
				self.specifics.onPressingCompleted();
			}
			self.blockEntity.sendData();
		});
	}
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
        double speed = self.blockEntity instanceof KineticBlockEntity shaft
            ? com.diliupengyun.differential_create.kinetics.PhysicsPreview.travel(shaft) : Math.abs(self.specifics.getKineticSpeed());
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
	private void differentialcreate$start(PressingBehaviour.Mode mode, CallbackInfo ci) {
		differentialcreate$work.reset(); differentialcreate$position = 0; differentialcreate$contact = false;
		differentialcreate$beltContactTick = Long.MIN_VALUE;
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCWorkFraction", differentialcreate$work.remainder());
		tag.putDouble("DCPressPosition", differentialcreate$position);
		tag.putBoolean("DCPressContact", differentialcreate$contact);
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$work.restore(tag.getDouble("DCWorkFraction"));
		differentialcreate$position = tag.getDouble("DCPressPosition");
		differentialcreate$contact = tag.getBoolean("DCPressContact");
	}
}
