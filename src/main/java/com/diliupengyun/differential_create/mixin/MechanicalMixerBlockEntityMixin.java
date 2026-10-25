package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.diliupengyun.differential_create.kinetics.WorkAccumulator;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mixing and head travel consume shaft travel calibrated at 64 RPM. */
@Mixin(MechanicalMixerBlockEntity.class)
public abstract class MechanicalMixerBlockEntityMixin extends BasinOperatingBlockEntity implements ShaftWork {
	@Unique private final WorkAccumulator differentialcreate$travel = new WorkAccumulator();
	@Unique private double differentialcreate$remaining = -1;
	@Unique private ResourceLocation differentialcreate$recipe;
	protected MechanicalMixerBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }

	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/processing/basin/BasinOperatingBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$workTick(CallbackInfo ci) {
		ci.cancel();
		MechanicalMixerBlockEntity self = (MechanicalMixerBlockEntity) (Object) this;
		if (!self.running || level == null || MachineWork.deferred(self)) return;
        double rate = com.diliupengyun.differential_create.kinetics.PhysicsPreview.travel(self) / 64.0;
		if (rate == 0) return;
		if (!level.isClientSide) setChanged();
		if (self.runningTicks >= 40) {
			self.running = false;
			self.runningTicks = 0;
			basinChecker.scheduleUpdate();
			return;
		}
		if (self.runningTicks != 20) {
			int next = self.runningTicks + differentialcreate$travel.advance(rate);
			self.runningTicks = Math.min(self.runningTicks < 20 ? 20 : 40, next);
			return;
		}
        if (level.isClientSide && !com.diliupengyun.differential_create.kinetics.PhysicsPreview.enabled(level)) { self.renderParticles(); return; }
		if (currentRecipe == null && differentialcreate$recipe != null)
			currentRecipe = level.getRecipeManager().byKey(differentialcreate$recipe).map(holder -> holder.value()).orElse(null);
		if (!matchBasinRecipe(currentRecipe)) {
			var matches = getMatchingRecipes();
			currentRecipe = matches.isEmpty() ? null : matches.get(0);
			differentialcreate$remaining = -1;
			if (currentRecipe == null) { self.runningTicks = 21; sendData(); return; }
		}
		if (differentialcreate$remaining < 0) {
			double recipeSpeed = currentRecipe instanceof StandardProcessingRecipe<?> recipe && recipe.getProcessingDuration() > 0
				? recipe.getProcessingDuration() / 100.0 : 1;
			differentialcreate$remaining = 3 * Math.ceil(recipeSpeed * 15) + 1;
		}
		differentialcreate$remaining -= rate;
		self.processingTicks = (int) Math.ceil(Math.max(0, differentialcreate$remaining));
		setChanged();
		if (differentialcreate$remaining <= 0) {
			self.runningTicks = 21;
			self.processingTicks = -1;
			differentialcreate$remaining = -1;
			applyBasinRecipe();
			sendData();
		}
	}


	@Override public double differentialcreate$remainingTravel() {
		MechanicalMixerBlockEntity self = (MechanicalMixerBlockEntity) (Object) this;
		if (!self.running) return Double.POSITIVE_INFINITY;
		if (self.runningTicks != 20)
			return Math.max(0, (self.runningTicks < 20 ? 20 : 40) - self.runningTicks - differentialcreate$travel.remainder()) * 64;
		if (currentRecipe == null && differentialcreate$recipe != null)
			currentRecipe = level.getRecipeManager().byKey(differentialcreate$recipe).map(holder -> holder.value()).orElse(null);
		if (!matchBasinRecipe(currentRecipe)) {
			var matches = getMatchingRecipes();
			currentRecipe = matches.isEmpty() ? null : matches.get(0);
			differentialcreate$remaining = -1;
			if (currentRecipe == null) return 0;
		}
		if (differentialcreate$remaining < 0) {
			double duration = currentRecipe instanceof StandardProcessingRecipe<?> recipe && recipe.getProcessingDuration() > 0
				? recipe.getProcessingDuration() / 100.0 : 1;
			differentialcreate$remaining = 3 * Math.ceil(duration * 15) + 1;
		}
		return Math.max(0, differentialcreate$remaining) * 64;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		MechanicalMixerBlockEntity self = (MechanicalMixerBlockEntity) (Object) this;
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		setChanged();
		if (self.runningTicks != 20) {
			int end = self.runningTicks < 20 ? 20 : 40;
			self.runningTicks += differentialcreate$travel.advance(travel / 64);
			if (!complete) return;
			self.runningTicks = end;
			differentialcreate$travel.reset();
			if (end == 40) { self.running = false; self.runningTicks = 0; basinChecker.scheduleUpdate(); }
			sendData();
			return;
		}
		differentialcreate$remaining -= travel / 64;
		self.processingTicks = (int) Math.ceil(Math.max(0, differentialcreate$remaining));
		if (!complete) return;
		self.runningTicks = 21;
		self.processingTicks = -1;
		differentialcreate$remaining = -1;
		MachineWork.event(() -> {
			if (self.isRemoved()) return;
			if (currentRecipe != null) applyBasinRecipe();
			sendData();
		});
	}

	@Inject(method = "startProcessingBasin", at = @At("RETURN"))
	private void differentialcreate$start(CallbackInfo ci) {
		if (((MechanicalMixerBlockEntity) (Object) this).runningTicks == 0) {
			differentialcreate$remaining = -1;
			differentialcreate$travel.reset();
		}
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCMixRemaining", differentialcreate$remaining);
		tag.putDouble("DCMixTravel", differentialcreate$travel.remainder());
		if (level != null && currentRecipe != null)
			level.getRecipeManager().getRecipes().stream().filter(holder -> holder.value() == currentRecipe).findFirst()
				.ifPresent(holder -> tag.putString("DCMixRecipe", holder.id().toString()));
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$remaining = tag.contains("DCMixRemaining") ? tag.getDouble("DCMixRemaining") : -1;
		differentialcreate$travel.restore(tag.getDouble("DCMixTravel"));
		differentialcreate$recipe = ResourceLocation.tryParse(tag.getString("DCMixRecipe"));
	}
}
