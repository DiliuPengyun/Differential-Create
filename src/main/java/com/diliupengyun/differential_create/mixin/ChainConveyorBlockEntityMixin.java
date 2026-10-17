package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ChainConveyorTransport;
import com.diliupengyun.differential_create.kinetics.IChainPackageMotion;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChainConveyorBlockEntity.class)
public abstract class ChainConveyorBlockEntityMixin {
    // Keep the native kinetic tick and connection validation, replace only transport.
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;getSpeed()F"), cancellable = true)
    private void differentialcreate$transport(CallbackInfo ci) {
        ChainConveyorTransport.tick((ChainConveyorBlockEntity) (Object) this);
        ci.cancel();
    }

    @Inject(method = "addTravellingPackage", at = @At("RETURN"))
    private void differentialcreate$enterChain(ChainConveyorPackage box, BlockPos connection, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        var self = (ChainConveyorBlockEntity) (Object) this;
        var motion = ((IChainPackageMotion) box).differentialcreate$motion();
        motion.directionKnown = true;
        motion.reversed = self.getSpeed() == 0 ? self.reversed : self.getSpeed() < 0;
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$saveDirection(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        tag.putBoolean("DCChainReversed", ((ChainConveyorBlockEntity) (Object) this).reversed);
    }
    @Inject(method = "read", at = @At(value = "INVOKE", target =
        "Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;updateBoxWorldPositions()V"))
    private void differentialcreate$restoreDirection(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("DCChainReversed")) ((ChainConveyorBlockEntity) (Object) this).reversed = tag.getBoolean("DCChainReversed");
    }
    @Redirect(method = "calculateConnectionStats", at = @At(value = "INVOKE", target =
        "Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;getSpeed()F"))
    private float differentialcreate$stationaryGeometry(ChainConveyorBlockEntity self) {
        float speed = self.getSpeed();
        return speed == 0 && self.reversed ? -1 : speed;
    }
}
