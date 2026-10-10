package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import com.simibubi.create.content.redstone.rail.ControllerRailBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ControllerRailBlock.class)
public abstract class MinecartControllerRailMixin {
    @Inject(method = "onMinecartPass", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$finiteDrive(BlockState state, Level level, BlockPos pos, AbstractMinecart cart, CallbackInfo ci) {
        if (level.isClientSide) return;
        ci.cancel();
        var direction = Vec3.atLowerCornerOf(ControllerRailBlock.getAccelerationVector(state));
        double target = cart.getMaxSpeedWithRail() * state.getValue(ControllerRailBlock.POWER) / 15f;
        if (cart instanceof MinecartFurnace furnace) { furnace.xPush = direction.x; furnace.zPush = direction.z; }
        var motion = cart.getDeltaMovement();
        if ((motion.dot(direction) >= 0 || motion.lengthSqr() < .0001) && target > 0)
            MinecartPhysics.target(cart, direction.scale(target));
        else {
            var difference = Vec3.atCenterOf(pos).subtract(cart.position());
            MinecartPhysics.target(cart, new Vec3(difference.x / 16, 0, difference.z / 16));
            if (cart instanceof MinecartFurnace furnace) furnace.xPush = furnace.zPush = 0;
        }
    }
}
