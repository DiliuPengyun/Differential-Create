package com.diliupengyun.differential_create.tests;

import java.nio.file.Path;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.LoadGaugeScale;
import com.mojang.blaze3d.platform.Lighting;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Quaternionf;

/** Opt-in visual QA of the actual baked head/needle, without opening a player save. */
public final class LoadGaugeClientSmokeTests {
    private static int frames;
    private static boolean preview, done;
    private LoadGaugeClientSmokeTests() {}

    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (!preview) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                var model = AllPartialModels.GAUGE_HEAD_STRESS.get();
                if (model == mc.getModelManager().getMissingModel()) throw new AssertionError("Load gauge head failed to bake");
                var quads = model.getQuads(AllBlocks.STRESSOMETER.getDefaultState(), null,
                    net.minecraft.util.RandomSource.create(0));
                boolean nativeTexture = quads.stream().allMatch(quad -> quad.getSprite().contents().name()
                    .equals(ResourceLocation.fromNamespaceAndPath("create", "block/gauge")));
                if (!nativeTexture || quads.size() != 16)
                    throw new AssertionError("Load gauge must retain the native texture and add only three parked-position hatches");
                if (!net.minecraft.client.resources.language.I18n.exists("gui.differentialcreate.goggles.load_undefined"))
                    throw new AssertionError("Load gauge status translations must be present in the client resources");
                Class.forName("com.simibubi.create.content.kinetics.gauge.GaugeVisual$Stress");
                mc.options.guiScale().set(1);
                mc.resizeDisplay();
                mc.setScreen(new Preview());
                frames = 0;
                preview = true;
                return;
            }
            if (++frames < 30) return;
            var path = Path.of(System.getProperty("differentialcreate.gaugePreviewPath", "load-gauge-preview.png"));
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(path); }
            DifferentialCreate.LOGGER.info("DC_LOAD_GAUGE_CLIENT_PASS: baked load head and native needle rendered in six calibrated states; {}", path);
            done = true;
            mc.stop();
        } catch (Throwable error) {
            DifferentialCreate.LOGGER.error("DC_LOAD_GAUGE_CLIENT_FAIL", error);
            done = true;
            mc.stop();
        }
    }

    private static final class Preview extends Screen {
        private static final double[] VALUES = {0, .5, 1, 2, Double.POSITIVE_INFINITY};
        Preview() { super(Component.literal("Load gauge preview")); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF252A2C);
            graphics.drawCenteredString(font, "Load Gauge", width / 2, 18, 0xE8DFC9);
            graphics.drawCenteredString(font, "0% - 100% at centre - infinity at full travel", width / 2, 34, 0xB3B4AE);
            int cardWidth = (width - 80) / 3, cardHeight = (height - 100) / 2;
            var state = AllBlocks.STRESSOMETER.getDefaultState();
            for (int i = 0; i < 6; i++) {
                var reading = i == 5 ? LoadFactor.Reading.UNDEFINED : new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, VALUES[i]);
                int x = 30 + i % 3 * (cardWidth + 10), y = 60 + i / 3 * (cardHeight + 10);
                graphics.fill(x, y, x + cardWidth, y + cardHeight, 0xFF343B3D);
                var label = i == 5 ? Component.translatable("gui.differentialcreate.goggles.load_undefined")
                    : Component.literal(LoadGaugeScale.percent(reading.fraction()));
                graphics.drawCenteredString(font, label, x + cardWidth / 2, y + 14, 0xF2E8D1);
                float scale = Math.min(cardWidth - 35, cardHeight - 50) / .625f;
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(x + cardWidth / 2f + scale / 2, y + cardHeight / 2f + 10 + scale / 2, 300);
                pose.scale(scale, -scale, scale);
                pose.mulPose(new Quaternionf().rotationY((float) (-Math.PI / 2)));
                Lighting.setupForFlatItems();
                var buffer = graphics.bufferSource().getBuffer(RenderType.solid());
                CachedBuffers.partial(AllPartialModels.GAUGE_HEAD_STRESS, state).light(LightTexture.FULL_BRIGHT)
                    .overlay(OverlayTexture.NO_OVERLAY).renderInto(pose, buffer);
                float pivot = 5.75f / 16;
                CachedBuffers.partial(AllPartialModels.GAUGE_DIAL, state).translate(0, pivot, pivot)
                    .rotate((float) (-Math.PI / 2 * LoadGaugeScale.position(reading)), Direction.EAST)
                    .translate(0, -pivot, -pivot).light(LightTexture.FULL_BRIGHT)
                    .overlay(OverlayTexture.NO_OVERLAY).renderInto(pose, buffer);
                graphics.flush();
                Lighting.setupFor3DItems();
                pose.popPose();
            }
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}
