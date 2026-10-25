package com.diliupengyun.differential_create.mixin;

import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class PonderBlockDropMixin {
    @Inject(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$previewDrop(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (!(level instanceof PonderLevel) || stack.isEmpty()) return;
        level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, stack.copy()));
        ci.cancel();
    }
}
