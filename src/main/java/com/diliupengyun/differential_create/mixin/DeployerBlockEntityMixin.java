package com.diliupengyun.differential_create.mixin;

import java.util.List;

import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.WorkAccumulator;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeployerBlockEntity.class)
public abstract class DeployerBlockEntityMixin {
	// Keep the held item and use/punch mode in the normal view.
	@Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$energyView(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		if (!isPlayerSneaking) return;
		KineticGoggleInfo.append((DeployerBlockEntity) (Object) this, tooltip, true);
		cir.setReturnValue(true);
	}

	@Inject(method = "addToGoggleTooltip", at = @At("RETURN"))
	private void differentialcreate$dynamicsView(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		if (!isPlayerSneaking)
			KineticGoggleInfo.append((DeployerBlockEntity) (Object) this, tooltip, false);
	}

	@Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$suppressNominalStress() { return false; }

	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();
	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;getTimerSpeed()I", ordinal = 0))
	private int differentialcreate$shaftTravel(DeployerBlockEntity self) {
		if (!self.getLevel().isClientSide) self.setChanged();
		return differentialcreate$work.advance(Math.abs(self.getSpeed()) * 2.0);
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
