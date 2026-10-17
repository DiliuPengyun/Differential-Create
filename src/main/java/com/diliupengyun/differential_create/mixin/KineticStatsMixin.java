package com.diliupengyun.differential_create.mixin;

import java.util.List;

import com.diliupengyun.differential_create.client.KineticItemTooltip;
import com.simibubi.create.foundation.item.KineticStats;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KineticStats.class)
public abstract class KineticStatsMixin {
    @Inject(method = "getKineticStats", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$physicalInventoryStats(Block block, Player player,
        CallbackInfoReturnable<List<Component>> cir) {
        cir.setReturnValue(KineticItemTooltip.describe(block, player, Screen.hasShiftDown()));
    }
}
