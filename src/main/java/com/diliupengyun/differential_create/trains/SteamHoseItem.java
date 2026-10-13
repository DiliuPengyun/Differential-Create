package com.diliupengyun.differential_create.trains;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

public final class SteamHoseItem extends Item {
    public SteamHoseItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) clearSelection(stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    private static void clearSelection(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove("Tank"); data.remove("Dimension");
        if (data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA); else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (player == null || !player.mayBuild()) return InteractionResult.PASS;
        var entity = level.getBlockEntity(context.getClickedPos());
        if (!(entity instanceof FluidTankBlockEntity) && !(entity instanceof SteamEngineBlockEntity) && !SteamBogey.powered(entity)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var stack = context.getItemInHand();
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (entity instanceof FluidTankBlockEntity) {
            if (player.isShiftKeyDown()) { clearSelection(stack); return InteractionResult.CONSUME; }
            data.putLong("Tank", context.getClickedPos().asLong());
            data.putString("Dimension", level.dimension().location().toString());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.selected"), true);
        } else {
            var engine = entity;
            if (player.isShiftKeyDown()) {
                SteamHoseLinks.unlink(engine);
                player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.unlinked"), true);
            } else {
                BlockPos at = BlockPos.of(data.getLong("Tank"));
                if (!data.contains("Tank") || !level.dimension().location().toString().equals(data.getString("Dimension"))
                    || at.distSqr(engine.getBlockPos()) > SteamHoseLinks.MAX_LENGTH * SteamHoseLinks.MAX_LENGTH
                    || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof FluidTankBlockEntity part)) {
                    player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.invalid"), true);
                    return InteractionResult.FAIL;
                }
                SteamHoseLinks.connect(engine, part);
                clearSelection(stack);
                player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.connected"), true);
            }
        }
        return InteractionResult.CONSUME;
    }
}
