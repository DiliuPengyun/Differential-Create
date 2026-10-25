package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.simibubi.create.foundation.utility.BlockHelper;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockHelper.class)
public abstract class PonderBlockLootMixin {
    @Inject(method = "destroyBlockAs", at = @At("HEAD"))
    private static void differentialcreate$actualLoot(Level level, BlockPos pos, Player player, ItemStack tool,
        float effectChance, Consumer<ItemStack> collect, CallbackInfo ci) {
        if (level instanceof PonderLevel world && world.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)
            && (player == null || !player.isCreative())) com.diliupengyun.differential_create.client.ponder.PonderLoot.collect(
                world, pos.immutable(), world.getBlockState(pos), tool, collect);
    }
}
