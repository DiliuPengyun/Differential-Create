package com.diliupengyun.differential_create.trains;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = "differentialcreate")
public final class SteamHoseItem extends Item {
    public SteamHoseItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || player.isSpectator()) return InteractionResultHolder.pass(stack);
        cancelSelection(stack, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    public static boolean hasSelection(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return data.contains("Tank") || data.contains("Engine");
    }
    public static boolean selectsEngine(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains("Engine");
    }
    public static SteamHosePort selectedTankPort(ItemStack stack) {
        return hasSelection(stack) && !selectsEngine(stack)
            ? SteamHosePort.read(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()) : null;
    }
    public static boolean isEngine(BlockEntity entity) {
        return entity instanceof SteamEngineBlockEntity || SteamBogey.powered(entity);
    }
    public static BlockEntity selectedEndpoint(Level level, ItemStack stack) {
        if (!hasSelection(stack)) return null;
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!level.dimension().location().toString().equals(data.getString("Dimension"))) return null;
        boolean engine = selectsEngine(stack);
        if (!engine && SteamHosePort.read(data) == null) return null;
        var pos = BlockPos.of(data.getLong(engine ? "Engine" : "Tank"));
        if (!level.isLoaded(pos)) return null;
        var entity = level.getBlockEntity(pos);
        return (engine ? isEngine(entity) : entity instanceof FluidTankBlockEntity) ? entity : null;
    }
    /** Minecraft tries the empty main hand first; a held offhand hose must win over empty-hand actions. */
    public static InteractionHand interactionHand(Player player, InteractionHand requested) {
        return requested == InteractionHand.MAIN_HAND && player.getMainHandItem().isEmpty() && player.isShiftKeyDown()
            && player.getOffhandItem().getItem() instanceof SteamHoseItem ? InteractionHand.OFF_HAND : requested;
    }
    private static void cancelSelection(ItemStack stack, Player player) {
        clearSelection(stack);
        player.displayClientMessage(Component.empty(), true);
    }
    private static void clearSelection(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove("Tank"); data.remove("Engine"); data.remove("Dimension");
        data.remove(SteamHosePort.KEY);
        if (data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA); else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (player == null || player.isSpectator()) return InteractionResult.PASS;
        var stack = context.getItemInHand();
        // Cancelling a pending selection never edits the clicked endpoint, even if it is already linked.
        if (player.isShiftKeyDown() && hasSelection(stack)) {
            cancelSelection(stack, player);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        var entity = level.getBlockEntity(context.getClickedPos());
        boolean engine = isEngine(entity);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide && player.mayBuild() && engine) {
                boolean linked = ((SteamHoseLinks.EngineLink) entity).differentialcreate$hose() != null;
                SteamHoseLinks.unlink(entity);
                player.displayClientMessage(Component.translatable("gui.differentialcreate.hose." + (linked ? "unlinked" : "not_linked")), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (entity instanceof AbstractBogeyBlockEntity && !engine) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.preview.install"), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!(entity instanceof FluidTankBlockEntity) && !engine) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!hasSelection(stack) || selectsEngine(stack) == engine) {
            // Same-kind clicks replace the first endpoint; either kind may start a connection.
            data.remove(engine ? "Tank" : "Engine");
            data.putLong(engine ? "Engine" : "Tank", context.getClickedPos().asLong());
            data.putString("Dimension", level.dimension().location().toString());
            SteamHosePort.write(data, engine ? null : SteamHosePort.fromHit(context.getClickedPos(), context.getClickedFace(), context.getClickLocation()));
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            player.displayClientMessage(Component.translatable("gui.differentialcreate.hose." + (engine ? "engine_selected" : "selected")), true);
        } else {
            var selected = selectedEndpoint(level, stack);
            if (selected == null || selected.getBlockPos().distSqr(entity.getBlockPos()) > SteamHoseLinks.MAX_LENGTH * SteamHoseLinks.MAX_LENGTH) {
                player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.invalid"), true);
                return InteractionResult.FAIL;
            }
            var port = engine ? selectedTankPort(stack) : SteamHosePort.fromHit(context.getClickedPos(), context.getClickedFace(), context.getClickLocation());
            SteamHoseLinks.connect(engine ? entity : selected, (FluidTankBlockEntity) (engine ? selected : entity), port);
            clearSelection(stack);
            player.displayClientMessage(Component.translatable("gui.differentialcreate.hose.connected"), true);
        }
        return InteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("gui.differentialcreate.hose.tooltip.connect").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.differentialcreate.hose.tooltip.cancel").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.differentialcreate.hose.tooltip.unlink").withStyle(ChatFormatting.GRAY));
    }

    /** Intercept before the clicked block can consume the interaction (containers, bogey styles, etc.). */
    @SubscribeEvent public static void blockInteraction(PlayerInteractEvent.RightClickBlock event) {
        var hand = interactionHand(event.getEntity(), event.getHand());
        if (!(event.getEntity().getItemInHand(hand).getItem() instanceof SteamHoseItem hose)) return;
        var result = hose.useOn(new UseOnContext(event.getEntity(), hand, event.getHitVec()));
        if (result == InteractionResult.PASS) return;
        event.setCanceled(true);
        event.setCancellationResult(result);
    }
    private static boolean cancelEntityInteraction(PlayerInteractEvent event) {
        var player = event.getEntity();
        var stack = player.getItemInHand(interactionHand(player, event.getHand()));
        if (!(stack.getItem() instanceof SteamHoseItem) || !player.isShiftKeyDown() || player.isSpectator()) return false;
        cancelSelection(stack, player);
        return true;
    }
    @SubscribeEvent public static void airInteraction(PlayerInteractEvent.RightClickItem event) {
        var hand = interactionHand(event.getEntity(), event.getHand());
        if (hand == event.getHand() || event.getEntity().isSpectator()) return;
        cancelSelection(event.getEntity().getItemInHand(hand), event.getEntity());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }
    @SubscribeEvent public static void entityInteraction(PlayerInteractEvent.EntityInteract event) {
        if (!cancelEntityInteraction(event)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }
    @SubscribeEvent public static void preciseEntityInteraction(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!cancelEntityInteraction(event)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }
}
