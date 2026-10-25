package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.instruction.FadeInOutInstruction;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.createmod.ponder.foundation.instruction.TextInstruction;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.createmod.ponder.foundation.registration.PonderLocalization;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Inventory every native/addon caption; gate reading time only for our own scenes. */
public final class PonderTextAudit {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}&&[^\\p{IsHan}]]+(?:['’][\\p{L}]+)*");
    private PonderTextAudit() {}

    @SuppressWarnings("unchecked")
    public static void verify() throws Exception {
        var english = language("create", "en_us");
        var chinese = language("create", "zh_cn");
        var ownedEnglish = language("differentialcreate", "en_us");
        var ownedChinese = language("differentialcreate", "zh_cn");
        ownedEnglish.entrySet().forEach(e -> english.add(e.getKey(), e.getValue()));
        ownedChinese.entrySet().forEach(e -> chinese.add(e.getKey(), e.getValue()));
        var localization = new PonderLocalization() {
            @Override public String getSpecific(ResourceLocation scene, String key) { return key(scene, key); }
            @Override public String getSpecific(ResourceLocation scene, String key, Object... params) { return key(scene, key); }
        };
        Field schedule = field(PonderScene.class, "schedule");
        Field totalTicks = field(TickingInstruction.class, "totalTicks");
        int fadeTicks = field(FadeInOutInstruction.class, "fadeTime").getInt(null);
        Field element = field(TextInstruction.class, "element");
        Field getter = field(TextWindowElement.class, "textGetter");
        var seen = new HashSet<ResourceLocation>();
        var rows = new JsonArray();
        var manifest = new JsonArray();
        var failures = new ArrayList<String>();
        int scenes = 0, captions = 0;
        for (var entry : PonderIndex.getSceneAccess().getRegisteredEntries()) {
            var scene = PonderSceneRegistry.compileScene(localization, entry.getValue(), null);
            if (!seen.add(scene.getId())) continue;
            boolean relevant = scene.getNamespace().equals(DifferentialCreate.MODID)
                || scene.getNamespace().equals("create");
            if (!relevant) continue;
            scenes++;
            var sceneCaptions = new JsonArray();
            var instructions = (List<PonderInstruction>) schedule.get(scene);
            int start = 0;
            for (var instruction : instructions) {
                if (instruction instanceof TextInstruction) {
                    var supplier = (Supplier<String>) getter.get(element.get(instruction));
                    String key = supplier.get();
                    if (!key.startsWith(scene.getNamespace() + ".ponder.")) continue; // Shared UI labels.
                    // Fade time at either end does not earn reading time.
                    // MarkAsFinished only enables Next: the UI keeps ticking existing captions.
                    int ticks = totalTicks.getInt(instruction) - 2 * fadeTicks;
                    captions++;
                    var manifestRow = new JsonObject();
                    manifestRow.addProperty("key", key);
                    manifestRow.addProperty("start_tick", start);
                    manifestRow.addProperty("readable_ticks", ticks);
                    for (String code : List.of("zh_cn", "en_us")) {
                        var language = code.equals("en_us") ? english : chinese;
                        String value = language.has(key) ? language.get(key).getAsString()
                            : localization.specific.get(scene.getId()).get(key.substring(key.lastIndexOf('.') + 1));
                        if (value == null || value.isBlank() || ticks <= 0)
                            throw new AssertionError("Invalid Ponder caption or duration: " + key + " / " + code);
                        manifestRow.addProperty(code, value);
                        String plain = value.replaceAll("§.", "");
                        long han = plain.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
                        long words = WORD.matcher(plain).results().count();
                        double slow = han / 5d + words / 3d, fast = han / 8d + words / 5d;
                        var row = new JsonObject();
                        row.addProperty("key", key); row.addProperty("language", code); row.addProperty("text", value);
                        row.addProperty("start_tick", start); row.addProperty("readable_ticks", ticks);
                        row.addProperty("han", han); row.addProperty("words", words);
                        row.addProperty("slow_seconds", slow); row.addProperty("fast_seconds", fast);
                        row.addProperty("pass", slow <= ticks / 20d + 1e-9);
                        rows.add(row);
                        // Native tutorials keep Create's own pacing; the budget only gates our own lessons.
                        if (slow > ticks / 20d + 1e-9 && scene.getNamespace().equals(DifferentialCreate.MODID))
                            failures.add(key + " [" + code + "]: " + slow + "s > " + ticks / 20d + "s");
                    }
                    sceneCaptions.add(manifestRow);
                }
                // Conditional machine waits do not have a fixed duration. Actual
                // caption windows are recorded by the animation audit during playback.
                if (instruction.isBlocking() && instruction instanceof TickingInstruction)
                    start += totalTicks.getInt(instruction);
            }
            if (!sceneCaptions.isEmpty()) {
                var sceneRow = new JsonObject();
                sceneRow.addProperty("scene", scene.getId().toString());
                sceneRow.add("captions", sceneCaptions);
                manifest.add(sceneRow);
            }
        }
        Path directory = Path.of(System.getProperty("differentialcreate.ponderPreviewPath", "ponder-previews"));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("text-readability.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rows));
        Files.writeString(directory.resolve("ponder-manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        DifferentialCreate.LOGGER.info("DC_PONDER_TEXT_AUDIT: {} scenes, {} captions, {} addon bilingual readings over budget", scenes, captions, failures.size());
        if (!failures.isEmpty()) throw new AssertionError("Ponder reading budget exceeded: " + String.join("; ", failures));
    }

    private static String key(ResourceLocation scene, String key) { return scene.getNamespace() + ".ponder." + scene.getPath() + "." + key; }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static JsonObject language(String namespace, String code) throws Exception {
        try (var reader = Minecraft.getInstance().getResourceManager()
            .getResource(ResourceLocation.fromNamespaceAndPath(namespace, "lang/" + code + ".json")).orElseThrow().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
