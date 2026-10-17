package com.diliupengyun.differential_create.trains;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Installed on an existing bogey engine through the same interaction as the engine itself. */
public final class CondenserPipeItem extends Item {
    public CondenserPipeItem(Properties properties) { super(properties); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.differentialcreate.condenser_pipe.tooltip.install").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.differentialcreate.condenser_pipe.tooltip.water").withStyle(ChatFormatting.GRAY));
    }
}
