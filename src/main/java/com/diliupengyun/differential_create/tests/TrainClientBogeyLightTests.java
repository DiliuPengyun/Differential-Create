package com.diliupengyun.differential_create.tests;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.SteamBogeyModels;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Actual daylit carriage pixels, including live condenser texture changes in the instanced visual. */
public final class TrainClientBogeyLightTests {
    private static int stage, frames;
    private static CompletableFuture<?> work;
    private static Vec3 originalPosition, cameraPosition;
    private static float yaw, pitch;
    private static boolean hideGui, bob, flying;
    private static int[] warm;
    private TrainClientBogeyLightTests() {}

    public static boolean tick(Minecraft mc, CarriageContraptionEntity entity) {
        var bogey = entity.getCarriage().leadingBogey();
        switch (stage) {
            case 0 -> {
                if (!bogey.bogeyData.getBoolean(SteamBogey.ENGINE)) throw new AssertionError("Light fixture did not assemble an installed engine");
                if (bogey.bogeyData.getBoolean(SteamBogey.CONDENSER)) throw new AssertionError("Light fixture must start with a copper engine cover");
                if (!VisualizationManager.supportsVisualization(mc.level)) throw new AssertionError("Carriage light check needs the actual instanced rendering path");
                originalPosition = mc.player.position(); yaw = mc.player.getYRot(); pitch = mc.player.getXRot();
                hideGui = mc.options.hideGui; bob = mc.options.bobView().get(); flying = mc.player.getAbilities().flying;
                var at = world(entity, .5f, .5f);
                cameraPosition = at.add(1.3, .6, -1.3);
                work = mc.getSingleplayerServer().submit(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.teleportTo(cameraPosition.x, cameraPosition.y, cameraPosition.z);
                    player.serverLevel().setDayTime(6000); return null;
                });
                mc.options.hideGui = true; mc.options.bobView().set(false); stage = 1;
            }
            case 1 -> {
                if (!work.isDone()) return false;
                work.join();
                if (mc.player.position().distanceTo(cameraPosition) > .2) return false;
                var delta = world(entity, .5f, .5f).subtract(mc.player.getEyePosition());
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z))));
                if (++frames < 30) return false;
                warm = sample(mc, entity, false, "bogey-moving-core-lighting.png");
                // Exercise the native visual's existing instance without rebuilding the carriage.
                bogey.bogeyData.putBoolean(SteamBogey.CONDENSER, true);
                frames = 0; stage = 2;
            }
            case 2 -> {
                if (++frames < 25) return false;
                sample(mc, entity, true, "bogey-moving-condenser.png");
                bogey.bogeyData.remove(SteamBogey.CONDENSER);
                frames = 0; stage = 3;
            }
            case 3 -> {
                if (++frames < 25) return false;
                sample(mc, entity, false, "bogey-moving-condenser-removed.png");
                DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_CONDENSER_PIXELS_PASS: native carriage visual changes copper to cooled panels and back");
                mc.options.hideGui = hideGui; mc.options.bobView().set(bob);
                mc.player.setYRot(yaw); mc.player.setXRot(pitch);
                work = mc.getSingleplayerServer().submit(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = flying; player.onUpdateAbilities();
                    player.teleportTo(originalPosition.x, originalPosition.y, originalPosition.z); return null;
                });
                stage = 4;
            }
            case 4 -> {
                if (!work.isDone()) return false;
                work.join(); if (mc.player.position().distanceTo(originalPosition) > .2) return false;
                stage = 5;
            }
            case 5 -> { return true; }
            default -> throw new AssertionError("Unknown bogey light stage");
        }
        return false;
    }

    private static int[] sample(Minecraft mc, CarriageContraptionEntity entity, boolean cold, String name) {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of("../../..", "train-interaction", name).toAbsolutePath().normalize());
            int[] colors = new int[12]; int checks = 0;
            for (float x : new float[]{.3f, .7f}) for (float z : new float[]{.3f, .7f}) {
                var camera = mc.gameRenderer.getMainCamera();
                var point = world(entity, x, z).subtract(camera.getPosition());
                var view = new Quaternionf(camera.rotation()).conjugate().transform(point.toVector3f());
                var clip = mc.gameRenderer.getProjectionMatrix(fov(mc)).transform(new Vector4f(view, 1));
                int px = (int) ((clip.x / clip.w * .5 + .5) * image.getWidth());
                int py = (int) ((.5 - clip.y / clip.w * .5) * image.getHeight());
                if (clip.w <= 0 || px < 2 || py < 2 || px >= image.getWidth() - 2 || py >= image.getHeight() - 2)
                    throw new AssertionError("Cover lighting sample is outside the camera");
                int red = 0, green = 0, blue = 0;
                for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) {
                    int pixel = image.getPixelRGBA(px + dx, py + dy);
                    red += pixel & 255; green += pixel >>> 8 & 255; blue += pixel >>> 16 & 255;
                }
                red /= 25; green /= 25; blue /= 25;
                DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_LIGHT_SAMPLE: cold={} {},{} screen={},{} rgb={},{},{}", cold, x, z, px, py, red, green, blue);
                if (cold) {
                    if (blue < 60 || blue <= red || Math.abs(red - warm[checks * 3]) + Math.abs(green - warm[checks * 3 + 1]) + Math.abs(blue - warm[checks * 3 + 2]) < 80)
                        throw new AssertionError("Condenser did not replace the copper panel with a lit cold-colored texture");
                } else if (red < 60 || red <= green * 1.4 || red <= blue * 1.8)
                    throw new AssertionError("Daylit moving engine cover is black or lost its native copper color");
                colors[checks * 3] = red; colors[checks * 3 + 1] = green; colors[checks * 3 + 2] = blue;
                checks++;
            }
            DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_LIGHT_PASS: {} actual daylit cover pixels, cold={}", checks, cold);
            return colors;
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static double fov(Minecraft mc) {
        try {
            var method = mc.gameRenderer.getClass().getDeclaredMethod("getFov", net.minecraft.client.Camera.class, float.class, boolean.class);
            method.setAccessible(true);
            return (double) method.invoke(mc.gameRenderer, mc.gameRenderer.getMainCamera(), mc.getTimer().getGameTimeDeltaPartialTick(true), true);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Vec3 world(CarriageContraptionEntity entity, float x, float z) {
        var carriage = entity.getCarriage(); var pose = new PoseStack();
        pose.translate(entity.getX(), entity.getY(), entity.getZ());
        CarriageContraptionEntityRenderer.translateBogey(pose, carriage.leadingBogey(), carriage.bogeySpacing,
            entity.getViewYRot(1), entity.getViewXRot(1), 1);
        pose.translate(0, -1.5 - 1 / 128f, 0);
        pose.translate(-.5, SteamBogeyModels.COVER_Y, -.5);
        pose.translate(.5, .5, .5); pose.scale(SteamBogeyModels.COVER_SCALE, 1, SteamBogeyModels.COVER_SCALE); pose.translate(-.5, -.5, -.5);
        var point = pose.last().pose().transformPosition(new Vector3f(x, 16.015625f / 16, z));
        return new Vec3(point.x, point.y, point.z);
    }
}
