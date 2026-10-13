package com.diliupengyun.differential_create.client;

import java.util.List;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseItem;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/** Create's checkered selection boxes and its configured goggles tooltip styling. */
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

    public static void tick() {
        connection = null; active = false; valid = false; message = null; color = 0xFFCC6C;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.player.isSpectator()) return;
        ItemStack held = ItemStack.EMPTY;
        for (var hand : InteractionHand.values()) {
            if (mc.player.getItemInHand(hand).getItem() instanceof SteamHoseItem) { held = mc.player.getItemInHand(hand); break; }
        }
        if (held.isEmpty()) return;
        active = true;
        var data = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        FluidTankBlockEntity source = null;
        boolean selected = data.contains("Tank");
        message = selected ? "waiting" : "choose";
        if (selected) {
            var pos = BlockPos.of(data.getLong("Tank"));
            if (mc.level.dimension().location().toString().equals(data.getString("Dimension")) && mc.level.isLoaded(pos)
                && mc.level.getBlockEntity(pos) instanceof FluidTankBlockEntity part) {
                source = part;
                highlight(SOURCE, SteamHoseRenderer.bounds(part), 0xFFCC6C);
            } else { message = "missing"; color = 0xFF7171; }
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        var target = mc.level.getBlockEntity(hit.getBlockPos());
        if (target instanceof FluidTankBlockEntity part) {
            highlight(TARGET, SteamHoseRenderer.bounds(part), 0xFFCC6C);
            message = "choose"; return;
        }
        if (target instanceof AbstractBogeyBlockEntity && !SteamBogey.powered(target)) {
            message = "install"; color = 0xFFCC6C;
            highlight(TARGET, target.getBlockState().getShape(mc.level, target.getBlockPos()).bounds().move(target.getBlockPos()), color);
            return;
        }
        if (!(target instanceof SteamEngineBlockEntity) && !SteamBogey.powered(target)) return;
        AABB outline = target.getBlockState().getShape(mc.level, target.getBlockPos()).bounds().move(target.getBlockPos());
        if (mc.player.isShiftKeyDown()) {
            message = "disconnect"; highlight(TARGET, outline, color); return;
        }
        if (source == null) {
            var linked = SteamHoseLinks.tank(target);
            if (!selected && linked != null) {
                message = "linked"; color = 0x77D99B; highlight(SOURCE, SteamHoseRenderer.bounds(linked), color);
            }
            highlight(TARGET, outline, color); return;
        }
        valid = source.getBlockPos().distSqr(target.getBlockPos()) <= SteamHoseLinks.MAX_LENGTH * SteamHoseLinks.MAX_LENGTH;
        color = valid ? 0x77D99B : 0xFF7171;
        message = valid ? "connect" : "range";
        highlight(TARGET, outline, color);
        connection = SteamHoseGeometry.connect(target.getBlockState(), Vec3.atCenterOf(target.getBlockPos()),
            SteamHoseRenderer.bounds(source), Vec3.atCenterOf(source.getBlockPos()));
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
        int textWidth = lines.stream().mapToInt(mc.font::width).max().orElse(0);
        int x = Math.clamp(width / 2 + cfg.overlayOffsetX.get(), 20, Math.max(20, width - textWidth - 20));
        int y = Math.clamp(height / 2 + cfg.overlayOffsetY.get(), 30, height - 52);
        int background = cfg.overlayCustomColor.get() ? cfg.overlayBackgroundColor.get() : BoxElement.COLOR_VANILLA_BACKGROUND.scaleAlpha(.75f).getRGB();
        int top = cfg.overlayCustomColor.get() ? cfg.overlayBorderColorTop.get() : BoxElement.COLOR_VANILLA_BORDER.getFirst().getRGB();
        int bottom = cfg.overlayCustomColor.get() ? cfg.overlayBorderColorBot.get() : BoxElement.COLOR_VANILLA_BORDER.getSecond().getRGB();
        GuiGameElement.of(ModContent.STEAM_HOSE.asStack()).at(x + 10, y - 16, 450).render(graphics);
        RemovedGuiUtils.drawHoveringText(graphics, lines, x, y, width, height, -1, background, top, bottom, mc.font);
    }
}
