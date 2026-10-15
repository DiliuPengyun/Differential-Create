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

/** Actual daylight pixels from the carriage bogey visual, which receives no packed-light updates. */
public final class TrainClientBogeyLightTests {
    private static int stage, frames;
    private static CompletableFuture<?> work;
    private static Vec3 originalPosition, cameraPosition;
    private static float yaw, pitch;
    private static boolean hideGui, bob, flying;
    private TrainClientBogeyLightTests() {}

    public static boolean tick(Minecraft mc, CarriageContraptionEntity entity) {
        var bogey = entity.getCarriage().leadingBogey();
        switch (stage) {
            case 0 -> {
                if (!bogey.bogeyData.getBoolean(SteamBogey.ENGINE)) throw new AssertionError("Light fixture did not assemble an installed engine");
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
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    Path path = Path.of("../../..", "train-interaction", "bogey-moving-core-lighting.png").toAbsolutePath().normalize();
                    image.writeToFile(path);
                    int checks = 0;
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
                        DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_LIGHT_SAMPLE: {},{} screen={},{} rgb={},{},{}", x, z, px, py, red / 25, green / 25, blue / 25);
                        if (red / 25 < 60 || red <= green * 1.4 || red <= blue * 1.8)
                            throw new AssertionError("Daylit moving engine cover is black or lost its native copper color");
                        checks++;
                    }
                    DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_LIGHT_PASS: {} actual daylit copper-cover pixels in a native carriage visual", checks);
                } catch (java.io.IOException error) { throw new AssertionError(error); }
                finally {
                    mc.options.hideGui = hideGui; mc.options.bobView().set(bob);
                    mc.player.setYRot(yaw); mc.player.setXRot(pitch);
                    work = mc.getSingleplayerServer().submit(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        player.getAbilities().flying = flying; player.onUpdateAbilities();
                        player.teleportTo(originalPosition.x, originalPosition.y, originalPosition.z); return null;
                    });
                    stage = 2;
                }
            }
            case 2 -> {
                if (!work.isDone()) return false;
                work.join(); if (mc.player.position().distanceTo(originalPosition) > .2) return false;
                stage = 3;
            }
            case 3 -> { return true; }
            default -> throw new AssertionError("Unknown bogey light stage");
        }
        return false;
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
