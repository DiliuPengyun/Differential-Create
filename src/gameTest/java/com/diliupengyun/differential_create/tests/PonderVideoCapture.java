package com.diliupengyun.differential_create.tests;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.google.gson.GsonBuilder;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;

/** Continuous, normally ticked Ponder footage. No seekToTime or whileSkipping calls. */
final class PonderVideoCapture {
    private record Clip(String name, PonderScene scene, String component) {}
    private static final List<Clip> clips = new ArrayList<>();
    private static final List<Map<String, Object>> manifest = new ArrayList<>();
    private static final Path directory = Path.of(System.getProperty("differentialcreate.ponderVideoPath", "ponder-videos"));
    private static final int fps = Integer.getInteger("differentialcreate.ponderVideoFps", 60);
    private static final int framesPerTick = fps / 20;
    private static final java.lang.reflect.Field schedule = field(PonderScene.class, "activeSchedule");
    private static final java.lang.reflect.Field imagePixels = field(com.mojang.blaze3d.platform.NativeImage.class, "pixels");
    private static int index = -1, frames, tail, width, height, subframe;
    private static VideoPreview preview;
    private static Process encoder;
    private static OutputStream stream;
    private static byte[] rgba;
    private static PonderAnimationAudit audit;
    private static final List<String> failures = new ArrayList<>();

    static boolean enabled() { return Boolean.getBoolean("differentialcreate.ponderVideo"); }

    static void start() throws Exception {
        if (fps < 20 || fps > 120 || fps % 20 != 0)
            throw new IllegalArgumentException("ponderVideoFps must be a multiple of 20 between 20 and 120");
        Files.createDirectories(directory);
        String encoderPath = System.getProperty("differentialcreate.ponderVideoEncoder", "");
        if (encoderPath.isBlank() || !Files.isRegularFile(Path.of(encoderPath)))
            throw new IllegalArgumentException("Set ponderVideoEncoder to a local ffmpeg executable");
        var filter = System.getProperty("differentialcreate.ponderVideoScenes", "");
        var component = System.getProperty("differentialcreate.ponderVideoComponent", "");
        var selected = java.util.Arrays.stream(filter.split(",")).map(String::trim)
            .filter(name -> !name.isEmpty()).collect(java.util.stream.Collectors.toSet());
        var unique = new LinkedHashMap<String, Clip>();
        for (var pair : PonderIndex.getSceneAccess().getRegisteredEntries()) {
            var entry = pair.getValue();
            if (!entry.getNamespace().equals("create") && !entry.getNamespace().equals(DifferentialCreate.MODID)) continue;
            if (!component.isBlank() && !entry.getComponent().toString().equals(component)) continue;
            var scene = PonderIndex.getSceneAccess().compile(List.of(entry)).getFirst();
            boolean nativeScene = scene.getId().getNamespace().equals("create");
            String name = (nativeScene ? "native-" : "") + scene.getId().getPath();
            if (entry.getComponent().getPath().equals("portable_fluid_interface")
                && (!nativeScene || scene.getId().getPath().equals("portable_storage_interface_redstone"))) name += "-fluid";
            if (!selected.isEmpty() && !selected.contains(name)) continue;
            unique.putIfAbsent(name, new Clip(name, scene, entry.getComponent().toString()));
        }
        clips.addAll(unique.values());
        if (!unique.keySet().containsAll(selected)) {
            var missing = new java.util.HashSet<>(selected);
            missing.removeAll(unique.keySet());
            throw new IllegalArgumentException("Unknown video scenes: " + missing);
        }
        clips.sort(Comparator.comparing((Clip c) -> c.name().startsWith("native-"))
            .thenComparing(Clip::name));
        if (clips.isEmpty()) throw new IllegalArgumentException("No video scenes selected");
        var mc = Minecraft.getInstance();
        mc.options.guiScale().set(Integer.getInteger("differentialcreate.ponderVideoGuiScale", 2));
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        mc.resizeDisplay();
        Files.writeString(directory.resolve("coverage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
            clips.stream().map(c -> Map.of("name", c.name(), "scene", c.scene().getId().toString(),
                "component", c.component(), "nominalTicks", c.scene().getTotalTime())).toList()));
        DifferentialCreate.LOGGER.info("DC_PONDER_VIDEO_START: {} complete scenes, {} fps, normal ticking with {} interpolated frames per tick", clips.size(), fps, framesPerTick);
    }

    static boolean frame() throws Exception {
        var mc = Minecraft.getInstance();
        if (index < 0) { next(); return false; }
        if (mc.screen != preview) throw new IllegalStateException("Video screen was replaced during capture");
        var clip = clips.get(index);
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            if (stream == null) {
                width = image.getWidth();
                height = image.getHeight();
                rgba = new byte[width * height * 4];
                encoder = new ProcessBuilder(System.getProperty("differentialcreate.ponderVideoEncoder"),
                    "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgba",
                    "-video_size", width + "x" + height, "-framerate", Integer.toString(fps), "-i", "pipe:0", "-an",
                    "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2:0:0",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart", directory.resolve(clip.name() + ".mp4").toString())
                    .redirectError(directory.resolve(clip.name() + ".encoder.log").toFile()).start();
                stream = new BufferedOutputStream(encoder.getOutputStream(), rgba.length);
            }
            if (image.getWidth() != width || image.getHeight() != height)
                throw new IllegalStateException("Window resized during video capture");
            org.lwjgl.system.MemoryUtil.memByteBuffer(imagePixels.getLong(image), rgba.length).get(rgba);
            stream.write(rgba);
        }
        frames++;
        if (++subframe < framesPerTick) return false;
        subframe = 0;
        if (frames > framesPerTick && failures.isEmpty()) {
            try { audit.sample(clip.scene()); }
            catch (Throwable failure) { failures.add(failure.toString()); }
        }
        if (((java.util.Collection<?>) schedule.get(clip.scene())).isEmpty()) tail++; else tail = 0;
        if (frames > 10000 * framesPerTick) throw new AssertionError("Video scene failed to finish: " + clip.name());
        if (tail >= 21) {
            stream.close(); stream = null;
            if (!encoder.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || encoder.exitValue() != 0)
                throw new IllegalStateException("Video encoder failed for " + clip.name());
            try { audit.verify(clip.scene()); }
            catch (Throwable failure) { failures.add(failure.toString()); }
            encoder = null;
            audit.save(clip.scene(), "video-" + clip.name());
            manifest.add(Map.of("name", clip.name(), "scene", clip.scene().getId().toString(),
                "component", clip.component(), "frames", frames, "fps", fps, "seconds", frames / (double) fps,
                "width", (width + 1) & ~1, "height", (height + 1) & ~1,
                "timelineTicks", clip.scene().getCurrentTime(), "issues", List.copyOf(failures)));
            Files.writeString(directory.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
            DifferentialCreate.LOGGER.info("DC_PONDER_VIDEO_CLIP: {}/{} {} {} frames; {} audit issues",
                index + 1, clips.size(), clip.name(), frames, failures.size());
            if (index + 1 == clips.size()) {
                long failed = manifest.stream().filter(row -> !((List<?>) row.get("issues")).isEmpty()).count();
                if (failed != 0) throw new AssertionError(failed + " video scenes failed animation audit; see manifest.json");
                return true;
            }
            next();
        } else preview.advance();
        return false;
    }

    static void abort() {
        if (encoder != null) encoder.destroyForcibly();
        encoder = null;
        if (stream != null) {
            try { stream.close(); }
            catch (java.io.IOException ignored) { /* Capture failure is reported by the smoke-test runner. */ }
            stream = null;
        }
    }

    private static void next() {
        index++;
        frames = tail = subframe = 0;
        failures.clear();
        audit = new PonderAnimationAudit();
        var scene = clips.get(index).scene();
        scene.begin();
        preview = new VideoPreview(scene);
        Minecraft.getInstance().setScreen(preview);
        DifferentialCreate.LOGGER.info("DC_PONDER_VIDEO_RECORD: {}/{} {}", index + 1, clips.size(), clips.get(index).name());
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) {
        try {
            var result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** Sample inside each normal UI tick too: tick-end-only captures conceal interpolation jitter. */
    private static final class VideoPreview extends PonderUI {
        VideoPreview(PonderScene scene) { super(new ArrayList<>(List.of(scene))); }
        @Override public void tick() { }
        void advance() { super.tick(); }
        @Override public boolean isComfyReadingEnabled() { return false; }
        @Override protected void renderVisibleScenes(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            super.renderVisibleScenes(graphics, -100, -100, (subframe + 1f) / framesPerTick);
        }
        @Override protected void renderWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            super.renderWidgets(graphics, -100, -100, (subframe + 1f) / framesPerTick);
        }
    }
}
