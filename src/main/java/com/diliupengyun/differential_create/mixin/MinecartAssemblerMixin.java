package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CartAssemblerBlockEntity.class)
public abstract class MinecartAssemblerMixin {
    @Inject(method = "assemble", at = @At("HEAD"))
    private void differentialcreate$beforeAssembly(Level level, BlockPos pos, AbstractMinecart cart, CallbackInfo ci) {
        MinecartPhysics.state(cart).assembling = !level.isClientSide && cart.getPassengers().isEmpty();
        if (!level.isClientSide && cart instanceof MinecartFurnace furnace && cart.getPassengers().isEmpty()) {
            MinecartPhysics.state(cart).savedPushX = furnace.xPush;
            MinecartPhysics.state(cart).savedPushZ = furnace.zPush;
        }
    }
    @Inject(method = "assemble", at = @At("RETURN"))
    private void differentialcreate$attachMass(Level level, BlockPos pos, AbstractMinecart cart, CallbackInfo ci) {
        if (MinecartPhysics.state(cart).assembling) {
            MinecartPhysics.assembled(cart); MinecartPhysics.state(cart).assembling = false;
        }
    }
    @Inject(method = "disassembleCart", at = @At("HEAD"))
    private void differentialcreate$beforeDetach(AbstractMinecart cart, CallbackInfo ci) {
        if (MinecartPhysics.enabled(cart)) MinecartPhysics.refresh(cart);
    }
    @Inject(method = "disassembleCart", at = @At("RETURN"))
    private void differentialcreate$detachMass(AbstractMinecart cart, CallbackInfo ci) {
        if (MinecartPhysics.enabled(cart)) MinecartPhysics.refresh(cart);
    }
    @Redirect(method = "tryAssemble", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;setDeltaMovement(DDD)V"))
    private void differentialcreate$launch(AbstractMinecart cart, double x, double y, double z) {
        MinecartPhysics.target(cart, new Vec3(x, y, z));
    }
    @Redirect(method = "tryAssemble", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void differentialcreate$drive(AbstractMinecart cart, Vec3 target) { MinecartPhysics.target(cart, target); }
}
