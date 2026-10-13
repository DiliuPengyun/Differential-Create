package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.kinetics.RatioSettingPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RatioPrecisionScreen extends Screen {
    private final BlockPos pos;
    private final int initial;
    private EditBox ratio;
    private Button confirm;
    public RatioPrecisionScreen(BlockPos pos, int value) { super(Component.translatable("gui.differentialcreate.ratio_precise")); this.pos = pos; initial = value; }
    @Override protected void init() {
        int x = width / 2, y = height / 2;
        ratio = new EditBox(font, x - 90, y - 15, 180, 20, title);
        ratio.setMaxLength(24); ratio.setValue(Double.toString(initial / 16d)); addRenderableWidget(ratio);
        confirm = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> submit()).bounds(x - 90, y + 32, 85, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 5, y + 32, 85, 20).build());
        ratio.setResponder(value -> confirm.active = raw() != null); setInitialFocus(ratio);
    }
    private Integer raw() {
        try { double value = Double.parseDouble(ratio.getValue()); return Double.isFinite(value) && Math.abs(value) <= 16 ? (int) Math.round(value * 16) : null; }
        catch (NumberFormatException ignored) { return null; }
    }
    private void submit() { Integer value = raw(); if (value == null) return; PacketDistributor.sendToServer(new RatioSettingPayload(pos, value)); onClose(); }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 42, 0xFBDC7D);
        Integer value = raw();
        graphics.drawCenteredString(font, value == null ? Component.translatable("gui.differentialcreate.ratio_invalid")
            : Component.translatable("gui.differentialcreate.ratio_result", value / 16d), width / 2, height / 2 + 12, 0xFFFFFF);
    }
    @Override public boolean isPauseScreen() { return false; }
}
