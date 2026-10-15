package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native sync updates the current virtual BE; keep its rebuild source current too. */
@Mixin(FluidTankMountedStorage.class)
public abstract class FluidTankMountedStorageMixin {
    @Inject(method = "afterSync", at = @At("HEAD"))
    private void differentialcreate$saveSyncedFluid(Contraption contraption, BlockPos pos, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        var block = contraption.getBlocks().get(pos);
        if (level == null || block == null || block.nbt() == null) return;
        var storage = (FluidTankMountedStorage) (Object) this;
        var snapshot = new FluidTank(storage.getCapacity());
        snapshot.setFluid(storage.getFluid().copy());
        block.nbt().put("TankContent", snapshot.writeToNBT(level.registryAccess(), new CompoundTag()));
    }
}
