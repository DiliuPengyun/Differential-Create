package com.diliupengyun.differential_create.mixin;

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
public abstract class MechanicalCrafterBlockEntityMixin {
	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();
	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/crafter/MechanicalCrafterBlockEntity;getCountDownSpeed()I"))
	private int differentialcreate$shaftTravel(MechanicalCrafterBlockEntity self) {
		if (!self.getLevel().isClientSide) self.setChanged();
		return differentialcreate$work.advance(Math.abs(self.getSpeed()));
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
