package com.diliupengyun.differential_create.client;

import java.util.List;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseItem;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.diliupengyun.differential_create.trains.SteamHosePort;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.foundation.gui.RemovedGuiUtils;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.createmod.catnip.gui.element.BoxElement;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Create's checkered selection boxes and its configured goggles tooltip styling. */
@EventBusSubscriber(modid = "differentialcreate", value = Dist.CLIENT)
public final class SteamHosePreview {
    private static final String SOURCE = "differentialcreate:hose_source", TARGET = "differentialcreate:hose_target";
    private static final ResourceLocation GOGGLES = ResourceLocation.fromNamespaceAndPath("create", "goggle_info");
    private static SteamHoseGeometry.Connection connection;
    private static boolean active, valid;
    private static String message;
    private static int color;
    private SteamHosePreview() {}
    public static SteamHoseGeometry.Connection connection() { return connection; }
    public static boolean valid() { return valid; }
    public static boolean active() { return active; }

    public static void clear() {
        connection = null; active = false; valid = false; message = null; color = 0xFFCC6C;
        Outliner.getInstance().remove(SOURCE);
        Outliner.getInstance().remove(TARGET);
    }
    private static void cancelPreview(PlayerInteractEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() == Minecraft.getInstance().player
            && event.getEntity().isShiftKeyDown() && event.getEntity().getItemInHand(
                SteamHoseItem.interactionHand(event.getEntity(), event.getHand())).getItem() instanceof SteamHoseItem) clear();
    }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void clickedBlock(PlayerInteractEvent.RightClickBlock event) { cancelPreview(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void clickedAir(PlayerInteractEvent.RightClickItem event) { cancelPreview(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void clickedEntity(PlayerInteractEvent.EntityInteract event) { cancelPreview(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void clickedEntityPart(PlayerInteractEvent.EntityInteractSpecific event) { cancelPreview(event); }

    public static void tick() {
        clear();
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.player.isSpectator()) return;
        ItemStack held = ItemStack.EMPTY;
        for (var hand : InteractionHand.values()) {
            if (mc.player.getItemInHand(hand).getItem() instanceof SteamHoseItem) { held = mc.player.getItemInHand(hand); break; }
        }
        if (held.isEmpty() || !SteamHoseItem.hasSelection(held)) return;
        active = true;
        boolean engineFirst = SteamHoseItem.selectsEngine(held);
        var source = SteamHoseItem.selectedEndpoint(mc.level, held);
        message = engineFirst ? "waiting_boiler" : "waiting";
        if (source != null) highlight(SOURCE, bounds(source), color);
        else { message = "missing"; color = 0xFF7171; }
        if (mc.player.isShiftKeyDown()) { message = "cancel_selection"; return; }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        var target = mc.level.getBlockEntity(hit.getBlockPos());
        if (target instanceof AbstractBogeyBlockEntity && !SteamBogey.powered(target)) {
            message = "install"; color = 0xFFCC6C;
            highlight(TARGET, target.getBlockState().getShape(mc.level, target.getBlockPos()).bounds().move(target.getBlockPos()), color);
            return;
        }
        boolean targetEngine = SteamHoseItem.isEngine(target);
        if (!(target instanceof FluidTankBlockEntity) && !targetEngine) return;
        AABB outline = bounds(target);
        if (targetEngine == engineFirst) {
            color = 0xFFCC6C;
            highlight(TARGET, outline, color);
            message = engineFirst ? "choose_engine" : "choose"; return;
        }
        if (source == null) {
            highlight(TARGET, outline, color); return;
        }
        valid = source.getBlockPos().distSqr(target.getBlockPos()) <= SteamHoseLinks.MAX_LENGTH * SteamHoseLinks.MAX_LENGTH;
        color = valid ? 0x77D99B : 0xFF7171;
        message = valid ? (engineFirst ? "connect_boiler" : "connect") : "range";
        highlight(TARGET, outline, color);
        var engine = engineFirst ? source : target;
        var tank = (FluidTankBlockEntity) (engineFirst ? target : source);
        var port = engineFirst ? SteamHosePort.fromHit(hit.getBlockPos(), hit.getDirection(), hit.getLocation()) : SteamHoseItem.selectedTankPort(held);
        connection = SteamHoseGeometry.connect(engine.getBlockState(), Vec3.atCenterOf(engine.getBlockPos()),
            SteamHoseRenderer.bounds(tank), Vec3.atCenterOf(tank.getBlockPos()), port);
    }
    private static AABB bounds(BlockEntity entity) {
        return entity instanceof FluidTankBlockEntity tank ? SteamHoseRenderer.bounds(tank)
            : entity.getBlockState().getShape(entity.getLevel(), entity.getBlockPos()).bounds().move(entity.getBlockPos());
    }
    private static void highlight(Object key, AABB bounds, int color) {
        Outliner.getInstance().showAABB(key, bounds).colored(color).lineWidth(1 / 32f)
            .withFaceTexture(AllSpecialTextures.THIN_CHECKERED);
    }
    public static void suppressGoggles(RenderGuiLayerEvent.Pre event) {
        if (active && event.getName().equals(GOGGLES)) event.setCanceled(true);
    }
    public static void overlay(GuiGraphics graphics, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (!active || message == null || mc.options.hideGui || mc.screen != null) return;
        var lines = List.of(Component.translatable("item.differentialcreate.steam_hose").withStyle(ChatFormatting.GOLD),
            Component.translatable("gui.differentialcreate.hose.preview." + message).withColor(color),
            Component.translatable("gui.differentialcreate.hose.preview.cancel", Component.keybind("key.sneak")).withStyle(ChatFormatting.GRAY));
        var cfg = AllConfigs.client();
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        int maxTextWidth = Math.max(40, Math.min(220, width - 48));
        int textWidth = Math.min(maxTextWidth, lines.stream().mapToInt(mc.font::width).max().orElse(0));
        int textHeight = lines.stream().mapToInt(line -> mc.font.split(line, maxTextWidth).size()).sum() * mc.font.lineHeight + 20;
        int x = Math.clamp(width / 2 + cfg.overlayOffsetX.get(), 20, Math.max(20, width - textWidth - 32));
        int y = Math.clamp(height / 2 + cfg.overlayOffsetY.get(), 30, Math.max(30, height - textHeight));
        int background = cfg.overlayCustomColor.get() ? cfg.overlayBackgroundColor.get() : BoxElement.COLOR_VANILLA_BACKGROUND.scaleAlpha(.75f).getRGB();
        int top = cfg.overlayCustomColor.get() ? cfg.overlayBorderColorTop.get() : BoxElement.COLOR_VANILLA_BORDER.getFirst().getRGB();
        int bottom = cfg.overlayCustomColor.get() ? cfg.overlayBorderColorBot.get() : BoxElement.COLOR_VANILLA_BORDER.getSecond().getRGB();
        GuiGameElement.of(ModContent.STEAM_HOSE.asStack()).at(x + 10, y - 16, 450).render(graphics);
        RemovedGuiUtils.drawHoveringText(graphics, lines, x, y, width, height, maxTextWidth, background, top, bottom, mc.font);
    }
}
