package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.IChainPackageMotion;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChainConveyorPackage.class)
public abstract class ChainConveyorPackageMixin implements IChainPackageMotion {
    @Unique private final State differentialcreate$motion = new State();
    @Override public State differentialcreate$motion() { return differentialcreate$motion; }

    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$saveDirection(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        if (differentialcreate$motion.directionKnown)
            cir.getReturnValue().putBoolean("DCChainReversed", differentialcreate$motion.reversed);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private static void differentialcreate$loadDirection(CompoundTag tag, HolderLookup.Provider registries,
        CallbackInfoReturnable<ChainConveyorPackage> cir) {
        var motion = ((IChainPackageMotion) cir.getReturnValue()).differentialcreate$motion();
        motion.directionKnown = tag.contains("DCChainReversed");
        motion.reversed = tag.getBoolean("DCChainReversed");
    }
}
