package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.BacktankCharge;
import com.diliupengyun.differential_create.kinetics.BacktankChargeData;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.simibubi.create.content.equipment.armor.BacktankBlockEntity;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BacktankBlockEntity.class)
public abstract class BacktankBlockEntityMixin extends KineticBlockEntity implements ShaftWork {
    @Shadow private int capacityEnchantLevel;
    @Unique private final BacktankCharge differentialcreate$charge = new BacktankCharge();

    protected BacktankBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$physicalCharging(CallbackInfo ci) {
        var self = (BacktankBlockEntity) (Object) this;
        // The common clock alone pays for server work. Keep Create's client particles.
        if (self.getLevel() != null && !self.getLevel().isClientSide) {
            self.airLevelTimer = 0;
            ci.cancel();
        }
    }

    @Override public double differentialcreate$remainingTravel() {
        var self = (BacktankBlockEntity) (Object) this;
        if (self.airLevel >= BacktankUtil.maxAir(capacityEnchantLevel)
            || self.getBlockState().getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false))
            return Double.POSITIVE_INFINITY;
        return differentialcreate$charge.remainingTravel(self.airLevel, BacktankUtil.maxAir(capacityEnchantLevel));
    }

    @Override public void differentialcreate$advanceTravel(double travel) {
        var self = (BacktankBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide || !Double.isFinite(differentialcreate$remainingTravel())) return;
        int previousSignal = self.getComparatorOutput();
        int capacity = BacktankUtil.maxAir(capacityEnchantLevel);
        self.airLevel = differentialcreate$charge.advance(self.airLevel, capacity, travel);
        self.setChanged();
        boolean signalChanged = previousSignal != self.getComparatorOutput();
        if (signalChanged) self.getLevel().updateNeighbourForOutputSignal(self.getBlockPos(), self.getBlockState().getBlock());
        if (self.airLevel == capacity || signalChanged) self.sendData();
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$saveCharge(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        tag.putDouble(BacktankChargeData.KEY, differentialcreate$charge.fraction());
        tag.putInt("Timer", 0);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$loadCharge(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        differentialcreate$charge.restore(tag.getDouble(BacktankChargeData.KEY));
        differentialcreate$clearFullCharge();
        ((BacktankBlockEntity) (Object) this).airLevelTimer = 0;
    }
    @Inject(method = "setAirLevel", at = @At("RETURN"))
    private void differentialcreate$airChanged(int air, CallbackInfo ci) { differentialcreate$clearFullCharge(); }

    @Inject(method = "getComponentPatch", at = @At("RETURN"), cancellable = true)
    private void differentialcreate$exportCharge(CallbackInfoReturnable<DataComponentPatch> cir) {
        differentialcreate$clearFullCharge();
        cir.setReturnValue(BacktankChargeData.withFraction(cir.getReturnValue(), differentialcreate$charge.fraction()));
    }
    @Inject(method = "setComponentPatch", at = @At("RETURN"))
    private void differentialcreate$importCharge(DataComponentPatch patch, CallbackInfo ci) {
        differentialcreate$charge.restore(BacktankChargeData.fraction(patch));
        differentialcreate$clearFullCharge();
    }
    @Inject(method = "applyImplicitComponents", at = @At("RETURN"))
    private void differentialcreate$componentCharge(DataComponentInput input, CallbackInfo ci) {
        differentialcreate$charge.restore(BacktankChargeData.fraction(input.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)));
    }
    @Unique private void differentialcreate$clearFullCharge() {
        if (((BacktankBlockEntity) (Object) this).airLevel >= BacktankUtil.maxAir(capacityEnchantLevel)) differentialcreate$charge.reset();
    }
}
