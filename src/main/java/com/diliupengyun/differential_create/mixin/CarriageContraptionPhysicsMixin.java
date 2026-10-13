package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.TrainAssembly;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CarriageContraption.class)
public abstract class CarriageContraptionPhysicsMixin implements TrainAssembly.LayoutHolder {
    @Unique private TrainAssembly.Layout differentialcreate$layout;
    @Override public TrainAssembly.Layout differentialcreate$layout() { return differentialcreate$layout; }
    @Override public void differentialcreate$layout(TrainAssembly.Layout layout) { differentialcreate$layout = layout; }
    @Inject(method = "assemble", at = @At("RETURN"))
    private void differentialcreate$capture(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) differentialcreate$layout = TrainAssembly.capture(level, (CarriageContraption) (Object) this);
    }
    @Inject(method = "writeNBT", at = @At("RETURN"))
    private void differentialcreate$write(HolderLookup.Provider registries, boolean spawn, CallbackInfoReturnable<CompoundTag> cir) {
        if (differentialcreate$layout != null) cir.getReturnValue().put(TrainAssembly.KEY, differentialcreate$layout.write());
    }
    @Inject(method = "readNBT", at = @At("RETURN"))
    private void differentialcreate$read(Level level, CompoundTag tag, boolean spawn, CallbackInfo ci) {
        differentialcreate$layout = tag.contains(TrainAssembly.KEY) ? TrainAssembly.Layout.read(tag.getCompound(TrainAssembly.KEY)) : null;
    }
}
