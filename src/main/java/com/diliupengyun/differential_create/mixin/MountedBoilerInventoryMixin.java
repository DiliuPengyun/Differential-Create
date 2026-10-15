package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Active boilers keep their block data, but never become ordinary mounted tanks. */
@Mixin(MountedStorageManager.class)
public abstract class MountedBoilerInventoryMixin {
    @Redirect(method = "addBlock", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/api/contraption/storage/fluid/MountedFluidStorageType;mount(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)Lcom/simibubi/create/api/contraption/storage/fluid/MountedFluidStorage;"))
    private MountedFluidStorage differentialcreate$ordinaryTanksOnly(MountedFluidStorageType<?> type, Level level,
        BlockState state, BlockPos pos, BlockEntity be) {
        if (be instanceof FluidTankBlockEntity tank) {
            var controller = tank.getControllerBE();
            if (controller != null && controller.boiler.isActive()) return null;
        }
        return type.mount(level, state, pos, be);
    }
}
