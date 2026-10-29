package com.diliupengyun.differential_create.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderLesson;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.client.KineticItemTooltip;
import com.diliupengyun.differential_create.content.ModContent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.PonderTooltipHandler;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opt-in playback, index reload and visual QA using the real client and Ponder renderer. */
public final class PonderClientSmokeTests {
    private static boolean started, done, localized, worldRequested;
    private static long deadline;
    private static int frames, checked, previewIndex = -1;
    private static List<StoryBoardEntry> entries;
    private static final Map<String, PonderScene> previews = new LinkedHashMap<>();
    private static final Map<String, PonderScene> auditScenes = new LinkedHashMap<>();
    private static final boolean AUDIT = Boolean.getBoolean("differentialcreate.ponderAudit");
    private static final String CAPTURE_FILTER = System.getProperty("differentialcreate.ponderCaptureScenes", "");
    private static final java.util.Set<String> CAPTURE_SCENES = new HashSet<>(List.of(CAPTURE_FILTER.split(",")));
    private static final String FRAME_AUDIT = System.getProperty("differentialcreate.ponderFrameAudit", "");
    private static final boolean ALL_FRAMES = FRAME_AUDIT.equals("all");
    private static final java.util.Set<String> FRAME_SCENES = new HashSet<>(List.of(FRAME_AUDIT.split(",")));
    private static final List<String> playbackFailures = new ArrayList<>();
    private record Capture(String name, PonderScene scene, int time) {}
    private static List<Capture> captures;
    private PonderClientSmokeTests() {}

    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (mc.getOverlay() != null || mc.screen == null && mc.level == null) return;
            if (!localized) {
                if (++frames < 30) return;
                localized = true;
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn");
                mc.reloadResourcePacks();
                frames = 0;
                return;
            }
            if (!started) {
                if (++frames < 20) return;
                if (!worldRequested) {
                    worldRequested = true;
                    deadline = System.nanoTime() + 240_000_000_000L;
                    mc.options.pauseOnLostFocus = false;
                    mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                    mc.options.renderDistance().set(2);
                    mc.options.simulationDistance().set(5);
                    var rules = new net.minecraft.world.level.GameRules();
                    rules.getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, null);
                    var settings = new net.minecraft.world.level.LevelSettings("Ponder verification",
                        net.minecraft.world.level.GameType.CREATIVE, false, net.minecraft.world.Difficulty.PEACEFUL,
                        true, rules, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("ponder_verification", settings,
                        new net.minecraft.world.level.levelgen.WorldOptions(42, false, false),
                        registry -> registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                            .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT)
                            .value().createWorldDimensions(), mc.screen);
                    return;
                }
                require(System.nanoTime() < deadline, "Timed out loading the disposable Ponder client world");
                if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
                // The level packet can arrive before its recipes. Real preview
                // processing must start only after the recipe synchronization.
                if (mc.level.getRecipeManager().getRecipes().isEmpty()) return;
                if (PonderVideoCapture.enabled()) {
                    PonderIndex.reload();
                    PonderVideoCapture.start();
                    started = true;
                    return;
                }
                resources();
                tooltips();
                motion();
                PonderReadoutAudit.verifyPhysics();
                PonderPhysicalAudit.verify();
                PonderIndex.reload();
                PonderSpoutAudit.verify();
                HosePulleyRenderAudit.verify();
                PonderTimingAudit.verify();
                nativeRegistrations();
                int before = PonderIndex.getSceneAccess().getRegisteredEntries().size();
                PonderIndex.reload();
                nativeRegistrations();
                require(before == PonderIndex.getSceneAccess().getRegisteredEntries().size(), "Index reload duplicates scenes");
                fluidTutorialOwnership();
                nativeCogwheelReadout();
                entries = PonderIndex.getSceneAccess().getRegisteredEntries().stream().map(Map.Entry::getValue)
                    .filter(e -> e.getNamespace().equals(DifferentialCreate.MODID)).toList();
                require(entries.size() >= PonderLesson.values().length, "Addon Ponder plugin was not registered");
                PonderTextAudit.verify();
                PonderFanAudit.verifyRenderer();
                ShaftRenderingAudit.verify();
                started = true;
            }
            if (PonderVideoCapture.enabled()) {
                if (PonderVideoCapture.frame()) {
                    DifferentialCreate.LOGGER.info("DC_PONDER_CLIENT_PASS: continuous video export finished; visual review is separate");
                    done = true;
                    mc.stop();
                }
                return;
            }
            if (checked < entries.size()) {
                var entry = entries.get(checked++);
                try {
                    var scene = PonderIndex.getSceneAccess().compile(List.of(entry)).getFirst();
                    var observation = new PlaybackObservation();
                    var animation = new PonderAnimationAudit();
                    for (int tick = 0; tick < 10000 && pendingInstructions(scene); tick++) {
                        scene.forEach(element -> element.whileSkipping(scene));
                        scene.tick();
                        observation.sample(scene);
                        animation.sample(scene);
                    }
                    require(!pendingInstructions(scene), "Storyboard did not finish: " + scene.getId());
                    previews.putIfAbsent(scene.getId().getPath(), scene);
                    if (AUDIT) auditScenes.putIfAbsent(scene.getId().getPath()
                        + (entry.getComponent().getPath().equals("portable_fluid_interface") ? "-fluid" : ""), scene);
                    if (AUDIT) animation.save(scene, entry.getComponent().getPath());
                    animation.verify(scene);
                    observation.verify(scene);
                } catch (Throwable failure) {
                    playbackFailures.add(entry.getComponent() + ": " + failure);
                    DifferentialCreate.LOGGER.error("DC_PONDER_STORYBOARD_FAIL: {}", entry.getComponent(), failure);
                }
                return;
            }
            if (captures == null) {
                if (!AUDIT) require(playbackFailures.isEmpty(), "Storyboard failures: " + String.join("; ", playbackFailures));
                if (previews.size() != PonderLesson.values().length) {
                    var missing = "A lesson has no reachable storyboard: " + previews.keySet();
                    if (!AUDIT) require(false, missing); else playbackFailures.add(missing);
                }
                // Also compile every item's complete scene list, checking native scenes remain accessible.
                var seen = new HashSet<ResourceLocation>();
                for (var entry : entries) if (seen.add(entry.getComponent())) {
                    var ids = new HashSet<ResourceLocation>();
                    for (var scene : PonderIndex.getSceneAccess().compile(entry.getComponent()))
                        require(ids.add(scene.getId()), "Duplicate lesson for item: " + entry.getComponent() + " / " + scene.getId());
                }
                replay();
                nativePlayback();
                var defaults = new LinkedHashMap<String, String>();
                PonderIndex.getLangAccess().provideLang(DifferentialCreate.MODID, defaults::put);
                var english = language("en_us");
                for (var entry : defaults.entrySet()) require(english.has(entry.getKey())
                    && english.get(entry.getKey()).getAsString().equals(entry.getValue()),
                    "English translation differs from storyboard defaults: " + entry.getKey());
                if (!Boolean.parseBoolean(System.getProperty("differentialcreate.ponderCapture", "true"))) {
                    require(playbackFailures.isEmpty(), "Storyboard failures: " + String.join("; ", playbackFailures));
                    DifferentialCreate.LOGGER.info("DC_PONDER_CLIENT_PASS: {} addon registrations and all selected native storyboards checked through their final instruction; screenshots disabled",
                        checked);
                    done = true;
                    mc.stop();
                    return;
                }
                captures = new ArrayList<>();
                for (var entry : (AUDIT ? auditScenes : previews).entrySet()) {
                    if (!CAPTURE_FILTER.isBlank() && !CAPTURE_SCENES.contains(entry.getKey())) continue;
                    var scene = entry.getValue();
                    if (!FRAME_AUDIT.isBlank()) {
                        if (ALL_FRAMES || FRAME_SCENES.contains(entry.getKey()))
                            for (int tick = 1; tick < scene.getTotalTime(); tick += ALL_FRAMES && entry.getKey().startsWith("native-") ? 4 : 2)
                                captures.add(new Capture(entry.getKey() + "-tick-" + String.format("%04d", tick), scene, tick));
                        continue;
                    }
                    if (AUDIT) {
                        for (int key = 0; key < scene.getKeyframeCount(); key++)
                            captures.add(new Capture(entry.getKey() + "-step-" + (key + 1), scene,
                                Math.min(scene.getTotalTime() - 1, scene.getKeyframeTime(key) + 20)));
                        captures.add(new Capture(entry.getKey() + "-end", scene, scene.getTotalTime() - 1));
                    } else captures.add(new Capture(entry.getKey(), scene,
                        scene.getKeyframeCount() == 0 ? Math.min(90, scene.getTotalTime() - 1)
                            : Math.min(scene.getTotalTime() - 1,
                                scene.getKeyframeTime(Math.min(1, scene.getKeyframeCount() - 1)) + 20)));
                }
                if (!AUDIT && FRAME_AUDIT.isBlank()) {
                    var cogwheel = auditScenes.get("native-cogwheel");
                    require(cogwheel != null, "Native cogwheel preview was not captured");
                    captures.add(new Capture("native-cogwheel", cogwheel, cogwheel.getTotalTime() - 30));
                }
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
                require(!captures.isEmpty(), "No scenes matched the requested frame audit: " + FRAME_AUDIT);
                frames = 0;
            }
            if (previewIndex < 0) {
                previewIndex = 0;
                mc.setScreen(new Preview(captures.getFirst().scene()));
                frames = 0;
            }
            boolean continuingScene = !FRAME_AUDIT.isBlank() && previewIndex > 0 && previewIndex < captures.size()
                && captures.get(previewIndex - 1).scene() == captures.get(previewIndex).scene();
            if (++frames == (continuingScene ? 1 : 4) && previewIndex < captures.size()) {
                var capture = captures.get(previewIndex);
                var scene = capture.scene();
                if (!continuingScene) scene.begin();
                scene.seekToTime(capture.time());
                if (!FRAME_AUDIT.isBlank() && !ALL_FRAMES && FRAME_SCENES.size() == 1) {
                    var scale = PonderScene.class.getDeclaredField("scaleFactor");
                    scale.setAccessible(true);
                    scale.setFloat(scene, 1.8f);
                }
            }
            if (frames < (continuingScene ? 3 : 28)) return;
            Path directory = Path.of(System.getProperty("differentialcreate.ponderPreviewPath", "ponder-previews"));
            if (AUDIT) directory = directory.resolve("audit");
            if (!FRAME_AUDIT.isBlank()) directory = directory.resolve("frames")
                .resolve(FRAME_SCENES.size() > 1 ? "selected" : FRAME_AUDIT);
            Files.createDirectories(directory);
            if (AUDIT && previewIndex == 0)
                Files.writeString(directory.resolve("manifest.txt"), String.join("\n", captures.stream()
                    .map(capture -> capture.name() + "\t" + capture.time()).toList()) + "\n");
            String name = previewIndex < captures.size() ? captures.get(previewIndex).name() : "inventory-tooltips";
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(directory.resolve(name + ".png"));
            }
            if (AUDIT && previewIndex < captures.size()) {
                var capture = captures.get(previewIndex);
                var scene = capture.scene();
                require(scene.getCurrentTime() == capture.time(), "Preview advanced past the requested capture tick");
                var states = new StringBuilder("Scene: " + scene.getId() + "\nTick: " + scene.getCurrentTime()
                    + "\nRotors (RPM, angle, mounted angle): " + snapshot(scene)
                    + "\nSections (offset, rotation): " + sectionSnapshot(scene) + "\n");
                for (var pos : BlockPos.betweenClosed(-2, 0, -2, 12, 8, 12)) {
                    var state = scene.getWorld().getBlockState(pos);
                    if (!state.isAir()) states.append(pos.toShortString()).append(": ").append(state).append('\n');
                }
                var entities = scene.getWorld().getEntityList();
                states.append("Entities:\n");
                for (var entity : entities) {
                    states.append(entity.getType().toShortString()).append(" at ")
                        .append(String.format("%.3f, %.3f, %.3f", entity.getX(), entity.getY(), entity.getZ()));
                    if (entity instanceof net.minecraft.world.entity.item.ItemEntity item)
                        states.append(' ').append(item.getItem());
                    states.append('\n');
                }
                Files.writeString(directory.resolve(name + ".txt"), states);
            }
            frames = 0;
            if (++previewIndex < captures.size()) {
                if (FRAME_AUDIT.isBlank() || captures.get(previewIndex - 1).scene() != captures.get(previewIndex).scene())
                    mc.setScreen(new Preview(captures.get(previewIndex).scene()));
            }
            else if (previewIndex == captures.size()) mc.setScreen(new TooltipPreview());
            else {
                require(playbackFailures.isEmpty(), "Storyboard failures: " + String.join("; ", playbackFailures));
                DifferentialCreate.LOGGER.info("DC_PONDER_CLIENT_PASS: {} registrations played to completion; {} lessons checked; {} scene frames captured; unique visible section ownership, torque-driven acceleration/coast/reversal, continuous angles, bilingual resources, native tooltip layout and index reload checked",
                    checked, previews.size(), captures.size());
                done = true;
                mc.stop();
            }
        } catch (Throwable failure) {
            if (PonderVideoCapture.enabled()) PonderVideoCapture.abort();
            DifferentialCreate.LOGGER.error("DC_PONDER_CLIENT_FAIL at entry {} / preview {}", checked, previewIndex, failure);
            done = true;
            mc.stop();
        }
    }

    private static JsonObject language(String code) throws Exception {
        var manager = Minecraft.getInstance().getResourceManager();
        try (var reader = manager.getResource(ResourceLocation.fromNamespaceAndPath("differentialcreate", "lang/" + code + ".json"))
            .orElseThrow().openAsReader()) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }

    private static String overlappingSections(PonderScene scene) {
        var owners = new HashSet<BlockPos>();
        var overlap = new String[1];
        try {
            // Ponder exposes section transforms, but not the selection that owns each rendered block.
            var field = net.createmod.ponder.foundation.element.WorldSectionElementImpl.class.getDeclaredField("section");
            field.setAccessible(true);
            scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
                try {
                    var selection = (net.createmod.ponder.api.scene.Selection) field.get(section);
                    if (selection == null) return;
                    for (var pos : selection) {
                        if (scene.getWorld().getBlockState(pos).isAir()) continue;
                        if (!owners.add(pos.immutable()) && overlap[0] == null)
                            overlap[0] = "Overlapping visible sections in " + scene.getId() + " at tick "
                                + scene.getCurrentTime() + ", " + pos.toShortString() + ": " + scene.getWorld().getBlockState(pos);
                    }
                } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            });
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        return overlap[0];
    }

    /** Inspect demonstrated states throughout playback without prescribing the storyboard layout. */
    private static final class PlaybackObservation {
        private final PonderSceneStateAudit stateAudit = new PonderSceneStateAudit();
        private String overlappingSections;
        private boolean loadMidpoint, ratioGeometry, ratioMotion, processor, steamLink, mountedRotor, crusherFeed;
        private boolean comparatorMidpoint, speedComparator, sequenceMotion, sequenceReverse, ejectorLaunch;
        private int sequenceHeldTicks, longestSequenceHold;
        private int previousSequence = -1, sequenceStarts;
        private final java.util.Set<String> ejectorStates = new java.util.LinkedHashSet<>();
        private int frozenLinearTicks;
        private final Map<BlockPos, Double> mountedAngles = new LinkedHashMap<>();
        private final Map<BlockPos, Integer> sheetCounts = new LinkedHashMap<>();
        private final Map<net.createmod.ponder.api.element.WorldSectionElement, net.minecraft.world.phys.Vec3> offsets = new LinkedHashMap<>();

        void sample(PonderScene scene) {
            stateAudit.sample(scene);
            if (overlappingSections == null) overlappingSections = overlappingSections(scene);
            var wheels = new ArrayList<com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity>();
            double mountedChange = 0;
            for (var be : scene.getWorld().getBlockEntities()) {
                if ((scene.getId().getPath().equals("dynamics") || scene.getId().getPath().equals("continuous_work"))
                    && be instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity tray
                    && scene.getWorld().getBlockEntity(be.getBlockPos().above(2))
                        instanceof com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity press) {
                    var depot = tray.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE);
                    var items = depot.itemHandler;
                    int sheets = 0;
                    for (int slot = 0; slot < items.getSlots(); slot++)
                        if (items.getStackInSlot(slot).is(AllItems.COPPER_SHEET.get()))
                            sheets += items.getStackInSlot(slot).getCount();
                    var held = depot.getHeldItemStack();
                    if (held.is(AllItems.COPPER_SHEET.get())) sheets += held.getCount();
                    Integer before = sheetCounts.put(be.getBlockPos().immutable(), sheets);
                    var pressing = press.getPressingBehaviour();
                    if (before != null && sheets > before)
                        require(pressing.running
                                && pressing.runningTicks >= com.simibubi.create.content.kinetics.press.PressingBehaviour.CYCLE / 2
                                && pressing.runningTicks < com.simibubi.create.content.kinetics.press.PressingBehaviour.CYCLE * 2 / 3,
                            "Sheet appeared while the press head was off its depot in " + scene.getId()
                                + " at tick " + scene.getCurrentTime());
                    if (scene.getId().getPath().equals("continuous_work")
                        && scene.getCurrentTime() >= scene.getTotalTime() - 5)
                        require(held.is(AllItems.COPPER_SHEET.get()),
                            "Press scene must end with the processed sheet on its depot: " + scene.getId());
                }
                if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic) {
                    require(Float.isFinite(kinetic.getSpeed()), "Non-finite shaft speed in " + scene.getId());
                    Double angle = PonderKinetics.of(scene.getWorld()).angle(kinetic, 1);
                    require(angle == null || Double.isFinite(angle), "Non-finite shaft angle in " + scene.getId());
                    processor |= com.diliupengyun.differential_create.kinetics.MachineActivity.manages(kinetic)
                        && !(kinetic instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)
                        && Math.abs(kinetic.getSpeed()) > 1
                        && com.diliupengyun.differential_create.kinetics.MachineActivity.working(kinetic);
                    Double mountedAngle = PonderKinetics.of(scene.getWorld()).outputAngle(kinetic, 1);
                    mountedRotor |= mountedAngle != null && Double.isFinite(mountedAngle) && Math.abs(mountedAngle) > .05;
                    if (mountedAngle != null) {
                        Double previous = mountedAngles.put(kinetic.getBlockPos(), mountedAngle);
                        if (previous != null && !com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).linearBlocked(kinetic))
                            mountedChange = Math.max(mountedChange, Math.abs(mountedAngle - previous));
                    }
                }
                if (be instanceof StressGaugeBlockEntity gauge) {
                    loadMidpoint |= Math.abs(gauge.dialTarget - .5f) < 1e-5;
                    if (Math.abs(gauge.dialTarget - .5f) < 1e-5)
                        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
                            if (scene.getWorld().getBlockEntity(gauge.getBlockPos().relative(direction))
                                instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator)
                                comparatorMidpoint |= comparator.getOutputSignal() == 8;
                }
                if (be instanceof com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity gauge && Math.abs(gauge.getSpeed()) > 16)
                    for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
                        if (scene.getWorld().getBlockEntity(gauge.getBlockPos().relative(direction))
                            instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator)
                            speedComparator |= comparator.getOutputSignal() > 0;
                if (be instanceof com.diliupengyun.differential_create.kinetics.ISequencerDynamics servo) {
                    var motion = servo.differentialcreate$motion();
                    sequenceMotion |= Math.abs(motion.speed) > 1;
                    sequenceReverse |= motion.speed < -1;
                    boolean holding = motion.initialized && motion.index < 0 && motion.parked()
                        && be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic
                        && Math.abs(kinetic.getSpeed()) > 1;
                    sequenceHeldTicks = holding ? sequenceHeldTicks + 1 : 0;
                    longestSequenceHold = Math.max(longestSequenceHold, sequenceHeldTicks);
                    if (motion.index >= 0 && previousSequence < 0) sequenceStarts++;
                    previousSequence = motion.index;
                }
                if (be instanceof com.simibubi.create.content.logistics.depot.EjectorBlockEntity ejector) {
                    ejectorLaunch |= ejector.getState() == com.simibubi.create.content.logistics.depot.EjectorBlockEntity.State.LAUNCHING;
                    ejectorStates.add(ejector.getState() + ", held="
                        + ejector.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).getHeldItemStack()
                        + ", target=" + ejector.getTargetPosition());
                }
                if (be instanceof com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity controller) {
                    ratioGeometry |= AllBlocks.LARGE_COGWHEEL.has(scene.getWorld().getBlockState(controller.getBlockPos().above()));
                    ratioMotion |= Math.abs(controller.getSpeed()) > 1;
                }
                if (be instanceof com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity engine)
                    steamLink |= engine.getShaft() != null && engine.getTargetAngle() != null;
                if (be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity wheel)
                    wheels.add(wheel);
            }
            for (int i = 0; i < wheels.size(); i++) for (int j = i + 1; j < wheels.size(); j++) {
                var a = wheels.get(i); var b = wheels.get(j);
                if (a.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
                    != b.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) continue;
                var pair = new com.diliupengyun.differential_create.kinetics.CrushingKinetics.Pair(null, a, b);
                crusherFeed |= Math.abs(pair.speed()) > 1 && pair.facing() == net.minecraft.core.Direction.DOWN;
            }
            if (scene.getId().getPath().equals("structure_load")) {
                double[] movement = {0};
                scene.forEach(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
                    var offset = section.getAnimatedOffset(); var previous = offsets.put(section, offset);
                    if (previous != null) movement[0] = Math.max(movement[0],
                        Math.hypot(offset.x - previous.x, offset.z - previous.z));
                });
                if (mountedChange > 1e-5 && movement[0] < 1e-10) frozenLinearTicks++;
            }
        }

        void verify(PonderScene scene) {
            stateAudit.verify(scene);
            require(overlappingSections == null, overlappingSections);
            switch (scene.getId().getPath()) {
                case "load_gauge" -> require(loadMidpoint && comparatorMidpoint, "The 100% load sample needs a centered dial and comparator output 8");
                case "speed_gauge" -> require(speedComparator, "Speedometer comparator never reflects a running shaft");
                case "ratio" -> require(ratioGeometry && ratioMotion, "Ratio controller needs a visible upper cogwheel and physical shaft motion");
                case "dynamics", "continuous_work" -> require(processor, "Working machine never turns under physical drive");
                case "steam_power" -> require(steamLink, "Virtual steam engine must retain its shaft and linkage");
                case "bearing", "structure_load" -> require(mountedRotor, "Mounted output never moves under physical drive");
                case "crusher_work" -> require(crusherFeed, "Crushing illustration never feeds down into the gap");
                case "ejector_work" -> require(ejectorLaunch, "Ejector never launches an item from its own tray: " + ejectorStates);
                case "sequencer" -> require(sequenceMotion && sequenceReverse && longestSequenceHold >= 5 && sequenceStarts >= 2,
                    "Sequencer must drive, reverse, hold with live input and restart only on another pulse: "
                        + sequenceMotion + "/" + sequenceReverse + "/" + longestSequenceHold + "/" + sequenceStarts);
                default -> { }
            }
            require(frozenLinearTicks <= 2, "Mounted output turns while its translated section is frozen for " + frozenLinearTicks + " ticks");
            geometry(scene);
        }
    }

    /** Check physical connections, rather than accepting a successfully played animation as correct construction. */
    private static void geometry(PonderScene scene) {
        var world = scene.getWorld(); var bounds = scene.getBounds();
        for (var be : world.getBlockEntities())
            if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank && tank.isController())
                for (int x = 0; x < tank.getWidth(); x++) for (int z = 0; z < tank.getWidth(); z++) for (int y = 0; y < tank.getHeight(); y++)
                    require(AllBlocks.FLUID_TANK.has(world.getBlockState(tank.getBlockPos().offset(x, y, z))),
                        "Tank metadata extends beyond its actual blocks at " + tank.getBlockPos());
        for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            var state = world.getBlockState(pos);
            if (state.is(net.minecraft.world.level.block.Blocks.COMPARATOR)) {
                var input = pos.relative(state.getValue(net.minecraft.world.level.block.ComparatorBlock.FACING));
                require(world.getBlockState(input).getBlock() instanceof com.simibubi.create.content.kinetics.gauge.GaugeBlock
                    || world.getBlockEntity(input) instanceof com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint,
                    "Comparator input faces away from the demonstrated gauge or portable interface at " + pos);
            }
            if (state.getBlock() instanceof com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock) {
                var support = world.getBlockState(pos.relative(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getOpposite()));
                require(AllBlocks.GANTRY_SHAFT.has(support)
                    && com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.isValidGantryShaftAxis(state, support),
                    "Gantry carriage pinion does not match its shaft at " + pos);
            }
            if (state.getBlock() instanceof com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock
                || state.getBlock() instanceof com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock) {
                var axis = com.simibubi.create.content.fluids.pipes.IAxisPipe.getAxisOf(state);
                for (var direction : net.minecraft.core.Direction.values()) if (direction.getAxis() == axis)
                    require(!world.getBlockState(pos.relative(direction)).isAir(), "Pipe port opens into air at " + pos + " / " + direction);
            }
            if (AllBlocks.MECHANICAL_PRESS.has(state))
                require(world.getBlockState(pos.below()).isAir() && AllBlocks.DEPOT.has(world.getBlockState(pos.below(2))),
                    "Press needs headroom and a workpiece depot two blocks below at " + pos);
            if (state.is(net.minecraft.world.level.block.Blocks.WHEAT))
                require(world.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND), "Crop has no farmland at " + pos);
            if (scene.getId().getPath().equals("minecart_dynamics") && AllBlocks.CONTROLLER_RAIL.has(state)) {
                var shape = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE_STRAIGHT);
                for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    var neighbour = world.getBlockState(pos.relative(direction));
                    if (AllBlocks.CONTROLLER_RAIL.has(neighbour) || AllBlocks.CART_ASSEMBLER.has(neighbour))
                        require((shape == net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST
                            ? net.minecraft.core.Direction.Axis.X : net.minecraft.core.Direction.Axis.Z) == direction.getAxis(),
                            "Rail is perpendicular to its continuation at " + pos);
                }
            }
        }
    }

    private static void resources() throws Exception {
        var en = language("en_us"); var zh = language("zh_cn");
        require(en.keySet().equals(zh.keySet()), "English and Chinese keys differ");
        require(en.keySet().stream().noneMatch(key -> key.startsWith("create.ponder.cogwheel.")),
            "The compatible native cogwheel lesson must retain Create's wording");
        for (var lesson : PonderLesson.values()) {
            String prefix = "differentialcreate.ponder." + lesson.id;
            require(en.has(prefix + ".header"), "Missing lesson title " + prefix);
            for (int i = 1; i <= lesson.steps(); i++)
                require(en.has(prefix + ".text_" + i), "Missing lesson text " + prefix + ".text_" + i);
        }
        for (var key : en.keySet()) {
            require(!en.get(key).getAsString().isBlank() && !zh.get(key).getAsString().isBlank(), "Empty translation " + key);
            require(placeholders(en.get(key).getAsString()).equals(placeholders(zh.get(key).getAsString())), "Placeholder mismatch " + key);
            Object[] args = new Object[placeholders(en.get(key).getAsString()).size()];
            java.util.Arrays.fill(args, 1);
            for (var data : List.of(en, zh))
                String.format(java.util.Locale.ROOT, data.get(key).getAsString(), args);
        }
    }

    private static List<String> placeholders(String value) {
        return java.util.regex.Pattern.compile("%(?:[0-9]+\\$)?[sd]").matcher(value).results().map(java.util.regex.MatchResult::group).toList();
    }

    private static void tooltips() throws Exception {
        for (var block : List.of(AllBlocks.FLYWHEEL.get(), AllBlocks.WATER_WHEEL.get(), AllBlocks.MECHANICAL_PRESS.get(),
            AllBlocks.STEAM_ENGINE.get(), AllBlocks.MECHANICAL_PUMP.get(), ModContent.BRASS_CLUTCH.get())) {
            var stats = KineticStats.getKineticStats(block, null);
            require(stats.stream().noneMatch(line -> line.getString().contains("x RPM")), "Old nominal stress statistic survived: " + block);
            var expanded = KineticItemTooltip.properties(block);
            require(!expanded.isEmpty(), "Missing physical inventory stats for " + block);
            require(expanded.getFirst().getContents() instanceof TranslatableContents title
                && title.getKey().equals("tooltip.differentialcreate.mechanical_properties"), "Missing Shift properties group");
            var collapsed = KineticItemTooltip.stats(block, null);
            require(collapsed.stream().noneMatch(line -> line.getString().contains("su/RPM³")
                || line.getString().contains("结构强度")), "Bare properties leaked outside Shift details");
        }
        var press = KineticItemTooltip.stats(AllBlocks.MECHANICAL_PRESS.get(), null);
        require(press.size() == 2 && press.getFirst().getStyle().getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GRAY))
            && press.getLast().getString().contains("█"), "Torque must retain Create's grey header and coloured rating bar");
        for (var clutch : List.of(AllBlocks.CLUTCH.get(), ModContent.BRASS_CLUTCH.get())) {
            require(KineticItemTooltip.stats(clutch, null).isEmpty(),
                "Clutches must not acquire a native-looking capacity rating outside Shift");
            var expanded = KineticItemTooltip.properties(clutch);
            require(expanded.getLast().getContents() instanceof TranslatableContents text
                && text.getKey().equals("tooltip.differentialcreate.torque_limit_value")
                && expanded.getLast().getString().contains("su/RPM")
                && expanded.stream().noneMatch(line -> line.getString().contains("█")),
                "The mod's clutch limit must be an exact Shift property, not an arbitrary capacity tier");
        }
        var player = Minecraft.getInstance().player;
        var helmet = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        try {
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
            var precise = KineticStats.getKineticStats(AllBlocks.MECHANICAL_PRESS.get(), player);
            require(precise.getLast().getString().contains("su/RPM") && precise.getLast().getString().contains("█"),
                "Goggles must reveal torque units within the original rating row");
        } finally { player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, helmet); }
        for (var item : List.of(AllItems.GOGGLES.asItem(), AllItems.COPPER_BACKTANK.asItem(), AllItems.NETHERITE_BACKTANK.asItem(),
            AllBlocks.FLYWHEEL.asItem(), AllBlocks.ROTATION_SPEED_CONTROLLER.asItem(), AllBlocks.MECHANICAL_PUMP.asItem(),
            ModContent.BRASS_CLUTCH.asItem(), ModContent.GEAR_LEVER.asItem())) {
            var description = ItemDescription.create(item, Palette.STANDARD_CREATE);
            require(description != null && !description.linesOnShift().isEmpty(), "Missing discoverable inventory summary: " + item);
            require(description.linesOnShift().stream().noneMatch(line -> line.getString().contains("Format error")),
                "Inventory summary has a formatting error: " + item);
            require(TooltipModifier.REGISTRY.get(item) != null, "Summary has no installed tooltip modifier: " + item);
        }
        var english = language("en_us");
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!english.has(ItemDescription.getTooltipTranslationKey(item) + ".summary")) continue;
            var description = ItemDescription.create(item, Palette.STANDARD_CREATE);
            require(description != null && description.linesOnShift().stream()
                .noneMatch(line -> line.getString().contains("Format error")), "Broken inventory summary: " + item);
            require(TooltipModifier.REGISTRY.get(item) != null && TooltipModifier.REGISTRY.get(item) != TooltipModifier.EMPTY,
                "New summary has no active tooltip modifier: " + item);
        }
        for (var item : List.of(ModContent.COMPOSITE_FRICTION_PLATE.asItem(), ModContent.STEAM_HOSE.asItem(),
            ModContent.CONDENSER_PIPE.asItem(), AllBlocks.FLUID_PIPE.asItem()))
            require(ItemDescription.create(item, Palette.STANDARD_CREATE) == null,
                "Redundant Shift description survived cleanup: " + item);
        // This checks composition of the loaded translations, not their factual accuracy.
        int nativeDescriptions = 0;
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("create")) continue;
            var original = ItemDescription.create(ItemDescription.getTooltipTranslationKey(item), Palette.STANDARD_CREATE);
            if (original == null) continue;
            var description = ItemDescription.create(item, Palette.STANDARD_CREATE);
            require(description != null && description.lines().equals(original.lines())
                && description.linesOnCtrl().equals(original.linesOnCtrl())
                && description.linesOnShift().size() >= original.linesOnShift().size()
                && description.linesOnShift().subList(0, original.linesOnShift().size()).equals(original.linesOnShift()),
                "Native Shift descriptions and controls must be preserved before adding properties: " + item);
            nativeDescriptions++;
        }
        require(nativeDescriptions > 0, "No native descriptions were checked");
        DifferentialCreate.LOGGER.info("DC_TOOLTIP_NATIVE_PASS: {} Create item descriptions retain loaded prose and controls when properties are added; wording needs manual review", nativeDescriptions);
        for (var item : List.of(AllBlocks.SHAFT.asItem(), AllBlocks.MECHANICAL_PRESS.asItem(),
            AllBlocks.ROTATION_SPEED_CONTROLLER.asItem(), AllBlocks.SEQUENCED_GEARSHIFT.asItem(), ModContent.BRASS_CLUTCH.asItem())) {
            var description = ItemDescription.create(item, Palette.STANDARD_CREATE);
            require(description != null && description.lines().size() == 1
                && translationIndex(description.lines(), "tooltip.differentialcreate.hold_for_properties") == 0,
                "Properties need their own discoverable Shift hint without a filler summary: " + item);
            var properties = KineticItemTooltip.properties(((net.minecraft.world.item.BlockItem) item).getBlock());
            require(description.linesOnShift().size() == properties.size() + 2,
                "A retired prose summary or duplicate properties survived: " + item);
            for (boolean ponderFirst : List.of(false, true)) {
                var lines = inventoryTooltip(item, true, ponderFirst);
                int header = translationIndex(lines, "tooltip.differentialcreate.mechanical_properties");
                int ponder = translationIndex(lines, "ponder." + PonderTooltipHandler.HOLD_TO_PONDER);
                require(header >= 0 && ponder == header + properties.size(),
                    "Mechanical properties must immediately precede W regardless of event order: " + item + " / " + lines);
                require(translationIndex(inventoryTooltip(item, false, ponderFirst), "tooltip.differentialcreate.mechanical_properties") == -1,
                    "Properties must remain collapsed without Shift: " + item);
            }
        }
        var ordering = inventoryTooltip(AllBlocks.MECHANICAL_PRESS.asItem(), true, false);
        require(translationIndex(ordering, "tooltip.differentialcreate.torque_impact")
            > translationIndex(ordering, "ponder." + PonderTooltipHandler.HOLD_TO_PONDER), "Torque rating moved above W");
        inventoryTooltip(AllBlocks.SHAFT.asItem(), true, false);
        var progressField = PonderTooltipHandler.class.getDeclaredField("holdKeyProgress");
        progressField.setAccessible(true);
        var progress = (net.createmod.catnip.animation.LerpedFloat) progressField.get(null);
        try {
            progress.startWithValue(.5);
            var lines = inventoryTooltip(AllBlocks.SHAFT.asItem(), true, false);
            int end = translationIndex(lines, "tooltip.differentialcreate.strength");
            require(end >= 0 && lines.get(end + 1).getString().replaceAll("§.", "").matches("\\|+"),
                "Holding W must not move its progress bar above mechanical properties");
        } finally { progress.startWithValue(0); }
        var shaft = AllBlocks.SHAFT.get();
        var cached = new ArrayList<>(ItemDescription.create(shaft.asItem(), Palette.STANDARD_CREATE).linesOnShift());
        float originalInertia = com.diliupengyun.differential_create.kinetics.InertiaRegistry.getBaseInertia(shaft);
        try {
            com.diliupengyun.differential_create.kinetics.InertiaRegistry.register(shaft, .25f);
            KineticStats.create(shaft.asItem()).modify(new net.neoforged.neoforge.event.entity.player.ItemTooltipEvent(
                shaft.asItem().getDefaultInstance(), player, cached, net.minecraft.world.item.TooltipFlag.NORMAL,
                Item.TooltipContext.of(Minecraft.getInstance().level)));
            require(cached.get(translationIndex(cached, "tooltip.differentialcreate.inertia")).getString().contains("0.25"),
                "Cached descriptions must refresh mechanical values without a language change: " + cached);
        } finally { com.diliupengyun.differential_create.kinetics.InertiaRegistry.register(shaft, originalInertia); }
        DifferentialCreate.LOGGER.info("DC_TOOLTIP_PASS: concise descriptions, independent Shift properties, both Ponder event orders, W progress and live values checked");
    }

    private static int translationIndex(List<Component> lines, String key) {
        for (int i = 0; i < lines.size(); i++)
            if (hasTranslation(lines.get(i), key)) return i;
        return -1;
    }

    private static boolean hasTranslation(Component component, String key) {
        return component.getContents() instanceof TranslatableContents text && text.getKey().equals(key)
            || component.getSiblings().stream().anyMatch(sibling -> hasTranslation(sibling, key));
    }

    private static List<Component> inventoryTooltip(Item item, boolean expanded, boolean ponderFirst) {
        var stack = item.getDefaultInstance();
        var lines = new ArrayList<Component>();
        lines.add(item.getDescription());
        if (ponderFirst) PonderTooltipHandler.addToTooltip(lines, stack);
        var description = ItemDescription.create(item, Palette.STANDARD_CREATE);
        if (description != null) lines.addAll(1, expanded ? description.linesOnShift() : description.lines());
        var kinetic = KineticStats.create(item);
        if (kinetic != null) kinetic.modify(new net.neoforged.neoforge.event.entity.player.ItemTooltipEvent(
            stack, Minecraft.getInstance().player, lines, net.minecraft.world.item.TooltipFlag.NORMAL,
            Item.TooltipContext.of(Minecraft.getInstance().level)));
        if (!ponderFirst) PonderTooltipHandler.addToTooltip(lines, stack);
        return lines;
    }

    /** These assertions failed with prescribed RPM and time-times-speed rendering. */
    private static void motion() {
        var level = new net.createmod.ponder.api.level.PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        var physics = PonderKinetics.of(level);
        physics.useExplicitSources();
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        var axis = net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;
        // Omitted-source lessons must retain finite torque and passive disconnected outputs.
        level.setBlock(new BlockPos(0, 1, 2), AllBlocks.SHAFT.getDefaultState().setValue(axis, net.minecraft.core.Direction.Axis.X), 3);
        for (int x = 1; x <= 4; x++) level.setBlock(new BlockPos(x, 1, 2),
            AllBlocks.SHAFT.getDefaultState().setValue(axis, net.minecraft.core.Direction.Axis.X), 3);
        var clutchPos = new BlockPos(2, 1, 2); var wheelPos = new BlockPos(4, 1, 2);
        level.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(axis, net.minecraft.core.Direction.Axis.X), 3);
        level.setBlock(wheelPos, AllBlocks.FLYWHEEL.getDefaultState().setValue(axis, net.minecraft.core.Direction.Axis.X), 3);
        level.setBlock(wheelPos.east(), AllBlocks.ENCASED_FAN.getDefaultState().setValue(facing, net.minecraft.core.Direction.EAST), 3);
        physics.boundary(level, new BlockPos(0, 1, 2), 64);
        var wheel = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) level.getBlockEntity(wheelPos);
        level.tick();
        require(wheel.getSpeed() > 0 && wheel.getSpeed() < 16, "Flywheel must start with finite clutch torque: " + wheel.getSpeed());
        for (int i = 0; i < 180; i++) level.tick();
        double running = wheel.getSpeed(), angle = physics.angle(wheel, 1);
        require(running > 60 && running < 65, "Connected flywheel failed to accelerate: " + running);
        var edited = wheel.saveWithFullMetadata(level.registryAccess());
        edited.remove("DCLocalSpeed");
        wheel.loadWithComponents(edited, level.registryAccess());
        level.tick();
        require(Float.isFinite(wheel.getSpeed()) && Math.abs(wheel.getSpeed() - running) < .1
            && physics.angle(wheel, 1) > angle, "Native scene NBT edits must preserve the preview's physical speed and angle");
        level.setBlock(clutchPos, level.getBlockState(clutchPos)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, true), 3);
        for (int i = 0; i < 20; i++) level.tick();
        require(wheel.getSpeed() > running * .98 && wheel.getSpeed() < running,
            "Released flywheel must coast with fan drag, without a new fictitious source: " + wheel.getSpeed());
        require(physics.angle(wheel, 1) > angle, "Coasting angle stopped instead of integrating travel");
        double before = physics.angle(wheel, 1);
        physics.boundary(level, new BlockPos(0, 1, 2), -64);
        require(physics.angle(wheel, 1) == before, "Changing a drive command jumped the render angle");
        for (int i = 0; i < 30; i++) level.tick();
        require(wheel.getSpeed() > 0, "An open clutch transmitted the input's reversal to its free output");
        level.setBlock(clutchPos, level.getBlockState(clutchPos)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, false), 3);
        double initial = wheel.getSpeed(); before = physics.angle(wheel, 1);
        level.tick();
        require(wheel.getSpeed() > 0 && wheel.getSpeed() < initial, "Re-engagement must brake the flywheel before reversing it");
        require(Math.abs(physics.angle(wheel, 1) - before) < 30, "Re-engagement reset the accumulated angle");
        var pressLevel = new net.createmod.ponder.api.level.PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        pressLevel.setBlock(BlockPos.ZERO, AllBlocks.MECHANICAL_PRESS.getDefaultState(), 3);
        var press = (com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity) pressLevel.getBlockEntity(BlockPos.ZERO);
        press.getPressingBehaviour().start(com.simibubi.create.content.kinetics.press.PressingBehaviour.Mode.BELT);
        var offstage = PonderKinetics.of(pressLevel);
        offstage.boundary(pressLevel, BlockPos.ZERO, 64);
        for (int i = 0; i < 30; i++) pressLevel.tick();
        require(press.getSpeed() > 60 && press.getSpeed() < 64,
            "A native diagram's off-stage drive must power its working machine with finite torque");
        var bearingLevel = new net.createmod.ponder.api.level.PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        bearingLevel.setBlock(BlockPos.ZERO, AllBlocks.MECHANICAL_BEARING.getDefaultState(), 3);
        var passive = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) bearingLevel.getBlockEntity(BlockPos.ZERO);
        passive.setSpeed(64);
        PonderKinetics.of(bearingLevel).useExplicitSources();
        for (int i = 0; i < 10; i++) bearingLevel.tick();
        require(passive.getSpeed() == 0, "A passive bearing must not become a torque source through its generating base class");
        for (var block : List.of(AllBlocks.SPEEDOMETER.get(), AllBlocks.STRESSOMETER.get())) {
            var readoutLevel = new net.createmod.ponder.api.level.PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
            readoutLevel.setBlock(BlockPos.ZERO, block.defaultBlockState(), 3);
            var gauge = (com.simibubi.create.content.kinetics.gauge.GaugeBlockEntity) readoutLevel.getBlockEntity(BlockPos.ZERO);
            gauge.setSpeed(64);
            readoutLevel.tick();
            require(gauge.getSpeed() == 0, "A template gauge reading became an off-stage torque source");
            var selection = net.createmod.ponder.foundation.SelectionImpl.of(new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 0, 0, 0, 0, 0));
            PonderKinetics.of(readoutLevel).command(readoutLevel, selection, ignored -> -64f);
            readoutLevel.tick();
            require(gauge.getSpeed() == 0, "A scripted gauge reading became an off-stage torque source");
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_MOTION_PASS: finite acceleration, load drag, open-clutch coast, source reversal and continuous phase");
    }

    private static Map<BlockPos, List<Double>> snapshot(PonderScene scene) {
        Map<BlockPos, List<Double>> state = new LinkedHashMap<>();
        for (var be : scene.getWorld().getBlockEntities())
            if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic) {
                Double angle = PonderKinetics.of(scene.getWorld()).angle(kinetic, 1);
                Double mounted = PonderKinetics.of(scene.getWorld()).outputAngle(kinetic, 1);
                Double output = kinetic instanceof com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity clutch
                    ? PonderKinetics.of(scene.getWorld()).clutchOutputAngle(clutch, 1)
                    : kinetic instanceof com.diliupengyun.differential_create.kinetics.ISequencerDynamics
                        ? PonderKinetics.of(scene.getWorld()).sequencerOutputAngle(kinetic, 1) : null;
                if (angle != null) state.put(be.getBlockPos(), List.of((double) kinetic.getSpeed(), angle,
                    mounted == null ? 0 : mounted, output == null ? 0 : output));
            }
        return state;
    }

    private static void replay() {
        for (var scene : (AUDIT ? auditScenes : previews).values()) {
            try {
            var first = replayFrames(scene);
            var second = replayFrames(scene);
            if (!first.equals(second)) {
                int at = 0; while (at < Math.min(first.size(), second.size()) && first.get(at).equals(second.get(at))) at++;
                require(false, "Replaying must reproduce every intermediate speed, shaft angle and section pose: " + scene.getId()
                    + "; first difference at " + at + ": " + (at < first.size() ? first.get(at) : "end")
                    + " vs " + (at < second.size() ? second.get(at) : "end"));
            }
            } catch (Throwable failure) {
                if (!AUDIT) throw failure;
                playbackFailures.add("Replay " + scene.getId() + ": " + failure);
                DifferentialCreate.LOGGER.error("DC_PONDER_REPLAY_FAIL: {}", scene.getId(), failure);
            }
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_REPLAY_PASS: all custom scene variants reproduce every intermediate speed, shaft angle and section pose after rewind");
    }

    private record PlaybackFrame(Map<BlockPos, List<Double>> rotors, List<List<Double>> sections,
                                 Map<BlockPos, List<String>> depotItems) {}

    private static Map<BlockPos, List<String>> depotSnapshot(PonderScene scene) {
        if (!scene.getId().getPath().equals("dynamics")) return Map.of();
        var state = new java.util.HashMap<BlockPos, List<String>>();
        for (var be : scene.getWorld().getBlockEntities())
            if (be instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity depot) {
                var inventory = depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).itemHandler;
                var slots = new ArrayList<String>();
                for (int slot = 0; slot < inventory.getSlots(); slot++) slots.add(inventory.getStackInSlot(slot).toString());
                state.put(depot.getBlockPos().immutable(), List.copyOf(slots));
            }
        return state;
    }

    private static List<PlaybackFrame> replayFrames(PonderScene scene) {
        var result = new ArrayList<PlaybackFrame>();
        var materials = new PonderMaterialAudit();
        scene.begin();
        for (int tick = 0; tick < 10000 && pendingInstructions(scene); tick++) {
            scene.forEach(element -> element.whileSkipping(scene));
            scene.tick();
            materials.sample(scene);
            result.add(new PlaybackFrame(snapshot(scene), sectionSnapshot(scene), depotSnapshot(scene)));
        }
        require(!pendingInstructions(scene), "Replay did not finish: " + scene.getId());
        materials.verify(scene);
        if (scene.getId().getPath().equals("crusher_work")) materialPlayback(scene, false);
        return result;
    }

    /** Playback and rewinding must conserve materials just like the fast audit path. */
    private static void materialPlayback(PonderScene scene, boolean skipping) {
        var materials = new PonderMaterialAudit();
        scene.begin();
        for (int tick = 0; tick < 10000 && pendingInstructions(scene); tick++) {
            if (skipping) scene.forEach(element -> element.whileSkipping(scene));
            scene.tick();
            if (!skipping) PonderMaterialAudit.renderSections(scene);
            materials.sample(scene);
        }
        require(!pendingInstructions(scene), "Material playback did not finish: " + scene.getId());
        materials.verify(scene);
        DifferentialCreate.LOGGER.info("DC_PONDER_MATERIAL_PASS: {}, skipping={}; inputs, contact and outputs verified after rewind",
            scene.getId(), skipping);
    }

    private static List<List<Double>> sectionSnapshot(PonderScene scene) {
        var sections = new ArrayList<List<Double>>();
        scene.forEach(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
            var offset = section.getAnimatedOffset(); var angle = section.getAnimatedRotation();
            sections.add(List.of(offset.x, offset.y, offset.z, angle.x, angle.y, angle.z));
        });
        sections.sort(java.util.Comparator.comparing(Object::toString));
        return sections;
    }

    /** Compare the real index against Create registering itself, including templates, scripts and ordering. */
    private static void nativeRegistrations() {
        var expected = new net.createmod.ponder.foundation.registration.PonderSceneRegistry(
            new net.createmod.ponder.foundation.registration.PonderLocalization());
        new com.simibubi.create.foundation.ponder.CreatePonderPlugin().registerScenes(
            new net.createmod.ponder.foundation.registration.DefaultPonderSceneRegistrationHelper("create", expected));
        var baseline = expected.getRegisteredEntries().stream().map(Map.Entry::getValue).toList();
        var actual = PonderIndex.getSceneAccess().getRegisteredEntries().stream().map(Map.Entry::getValue)
            .filter(entry -> entry.getNamespace().equals("create")).toList();
        require(actual.size() == baseline.size(), "Native storyboard registrations were removed or replaced");
        for (int i = 0; i < baseline.size(); i++) {
            var a = actual.get(i); var b = baseline.get(i);
            require(a.getComponent().equals(b.getComponent())
                && a.getSchematicLocation().equals(b.getSchematicLocation())
                && a.getBoard().getClass().equals(b.getBoard().getClass())
                && a.getTags().equals(b.getTags()) && a.getOrderingEntries().equals(b.getOrderingEntries()),
                "Native storyboard changed: " + b.getComponent() + " / " + b.getSchematicLocation());
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_NATIVE_REGISTRY_PASS: {} original registrations, templates, scripts, tags and ordering retained", baseline.size());
    }

    private static void fluidTutorialOwnership() {
        var filling = ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "pump_network");
        for (var component : List.of(AllBlocks.FLUID_PIPE.getId(), AllBlocks.FLUID_VALVE.getId(),
            AllBlocks.SMART_FLUID_PIPE.getId(), AllBlocks.MECHANICAL_PUMP.getId(),
            AllBlocks.FLUID_TANK.getId(), AllBlocks.HOSE_PULLEY.getId())) {
            boolean present = PonderIndex.getSceneAccess().compile(component).stream()
                .anyMatch(scene -> scene.getId().equals(filling));
            require(present == component.equals(AllBlocks.FLUID_PIPE.getId()),
                "Pipe air displacement supplement belongs only to ordinary Fluid Pipes: " + component);
        }
    }

    /** A readout at the end of the native construction must not command the gears that drive it. */
    private static void nativeCogwheelReadout() {
        for (boolean skipping : List.of(false, true)) {
            var scene = PonderIndex.getSceneAccess().compile(AllBlocks.COGWHEEL.getId()).stream()
                .filter(candidate -> candidate.getId().equals(ResourceLocation.fromNamespaceAndPath("create", "cogwheel")))
                .findFirst().orElseThrow();
            var world = scene.getWorld();
            var physics = PonderKinetics.of(world);
            var drive = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(new BlockPos(2, 0, 5));
            var gauge = (com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity) world.getBlockEntity(new BlockPos(4, 1, 1));
            var output = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(new BlockPos(4, 1, 2));
            float target = drive.getTheoreticalSpeed();
            require(target != 0, "Native cogwheel template has no off-stage drive");
            boolean runningBeforeConnection = false, connected = false;
            int connectedTicks = 0;
            for (int tick = 0; tick < 3000 && !scene.isFinished(); tick++) {
                if (skipping) scene.forEach(element -> element.whileSkipping(scene));
                scene.tick();
                boolean joined = physics.sameComponent(drive, gauge);
                runningBeforeConnection |= !joined && Math.abs(drive.getSpeed()) > 1;
                connected |= joined;
                if (Math.abs(drive.getSpeed()) > .1)
                    require(Math.signum(drive.getSpeed()) == Math.signum(target),
                        "Gauge readout reversed the native cogwheel drive at tick " + scene.getCurrentTime()
                            + " (skipping=" + skipping + "): expected direction " + target + ", actual " + drive.getSpeed());
                if (!joined) {
                    require(Math.abs(gauge.getSpeed()) < .01, "An unconnected readout became a drive source");
                    continue;
                }
                if (++connectedTicks > 30) {
                    require(Math.abs(drive.getSpeed() - target) < .1,
                        "Gauge readout changed the native cogwheel drive speed: " + drive.getSpeed() + " vs " + target);
                    require(Math.abs(gauge.getSpeed() - output.getSpeed()) < .1,
                        "Native gauge shaft does not follow its connected cogwheel");
                }
            }
            require(scene.isFinished() && runningBeforeConnection && connected && connectedTicks > 30,
                "Native cogwheel check did not cover the gauge joining an already running assembly");
            DifferentialCreate.LOGGER.info("DC_PONDER_COGWHEEL_GAUGE_PASS: skipping={}, drive={} RPM, gauge={} RPM; direction and source speed retained across connection",
                skipping, drive.getSpeed(), gauge.getSpeed());
        }
    }

    private static void nativePlayback() throws Exception {
        int failedBefore = playbackFailures.size();
        var ids = new HashSet<String>();
        var originals = new HashSet<ResourceLocation>();
        for (var item : List.of(AllBlocks.SHAFT, AllBlocks.COGWHEEL, AllBlocks.WATER_WHEEL,
            AllBlocks.WINDMILL_BEARING, AllBlocks.MECHANICAL_PRESS, AllBlocks.MECHANICAL_PUMP, AllBlocks.GEARBOX,
            AllBlocks.DEPLOYER, AllBlocks.DEPOT, AllBlocks.CRUSHING_WHEEL,
            AllBlocks.ENCASED_FAN, AllBlocks.CLUTCH, AllBlocks.ROTATION_SPEED_CONTROLLER,
            AllBlocks.SEQUENCED_GEARSHIFT, AllBlocks.SPEEDOMETER, AllBlocks.STRESSOMETER,
            AllBlocks.MECHANICAL_BEARING, AllBlocks.PORTABLE_STORAGE_INTERFACE,
            AllBlocks.PORTABLE_FLUID_INTERFACE, AllBlocks.TRAIN_CONTROLS)) originals.add(item.getId());
        var components = new java.util.LinkedHashSet<>(originals);
        if (AUDIT) {
            PonderIndex.getSceneAccess().getRegisteredEntries().stream().map(Map.Entry::getValue)
                .filter(entry -> entry.getNamespace().equals("create"))
                .forEach(entry -> components.add(entry.getComponent()));
        }
        for (var component : components) {
            for (var scene : PonderIndex.getSceneAccess().compile(component)) {
                boolean redstoneInterface = scene.getId().getPath().equals("portable_storage_interface_redstone");
                String variant = redstoneInterface && component.equals(AllBlocks.PORTABLE_FLUID_INTERFACE.getId()) ? "-fluid" : "";
                if (!scene.getId().getNamespace().equals("create") || !ids.add(scene.getId() + variant)) continue;
                try {
                    DifferentialCreate.LOGGER.info("DC_PONDER_NATIVE_PLAY: {}", scene.getId());
                    var animation = new PonderAnimationAudit();
                    double maximum = 0;
                    for (int tick = 0; tick < 10000 && pendingInstructions(scene); tick++) {
                        scene.forEach(element -> element.whileSkipping(scene));
                        scene.tick();
                        animation.sample(scene);
                        for (var sample : snapshot(scene).values()) {
                            require(sample.stream().allMatch(Double::isFinite), "Non-finite native preview motion: " + scene.getId());
                            maximum = Math.max(maximum, Math.abs(sample.getFirst()));
                        }
                    }
                    require(!pendingInstructions(scene), "Retained native tutorial failed to finish: " + scene.getId());
                    if (scene.getId().getPath().equals("windmill_source"))
                        require(maximum > 1, "Native driven-gear instructions failed to reach the windmill's source boundary");
                    if (AUDIT || scene.getId().getPath().equals("cogwheel"))
                        auditScenes.putIfAbsent("native-" + scene.getId().getPath() + variant, scene);
                    if (AUDIT) animation.save(scene, "native" + variant);
                    animation.verify(scene);
                    if (scene.getId().getPath().equals("mechanical_press") || scene.getId().getPath().equals("deployer_processing")) {
                        materialPlayback(scene, false);
                        materialPlayback(scene, true);
                    }
                    if (scene.getId().getPath().equals("portable_fluid_interface") || redstoneInterface
                        || java.util.Set.of("piston_pole", "mechanical_bearing", "stabilized_bearings", "bearing_modes",
                            "rope_pulley", "rope_pulley_modes", "rope_pulley_multi_rope", "elevator_pulley", "elevator_pulley_multi_rope",
                            "clockwork_bearing", "gantry_redstone", "gantry_cascaded").contains(scene.getId().getPath())) attachmentReplay(scene);
                } catch (Throwable failure) {
                    playbackFailures.add(scene.getId() + ": " + failure);
                    DifferentialCreate.LOGGER.error("DC_PONDER_NATIVE_FAIL: {}", scene.getId(), failure);
                }
            }
        }
        int failures = playbackFailures.size() - failedBefore;
        DifferentialCreate.LOGGER.info("DC_PONDER_NATIVE_{}: {} retained native scene variants checked through their final instruction; {} failed",
            failures == 0 ? "PASS" : "FAILURES", ids.size(), failures);
    }

    private static void attachmentReplay(PonderScene scene) {
        record Frame(PlaybackFrame motion, Map<String, List<Double>> mechanisms, List<net.minecraft.world.phys.Vec3> passengers) {}
        List<Frame> reference = null;
        for (boolean skipping : List.of(false, true, false)) {
            scene.begin();
            var animation = new PonderAnimationAudit();
            var frames = new ArrayList<Frame>();
            for (int tick = 0; tick < 3000 && pendingInstructions(scene); tick++) {
                if (skipping) scene.forEach(element -> element.whileSkipping(scene));
                scene.tick();
                animation.sample(scene);
                var passengers = new ArrayList<net.minecraft.world.phys.Vec3>();
                scene.forEachVisible(net.createmod.ponder.api.element.ParrotElement.class,
                    bird -> passengers.add(bird.getPositionOffset()));
                frames.add(new Frame(new PlaybackFrame(snapshot(scene), sectionSnapshot(scene), Map.of()),
                    Map.copyOf(PonderSimulation.of(scene.getWorld()).readings()), List.copyOf(passengers)));
            }
            require(!pendingInstructions(scene), "Mounted lesson did not complete after rewind: " + scene.getId());
            animation.verify(scene);
            if (reference != null) require(reference.equals(frames),
                "Mounted motion, passengers and docking must reproduce through normal/skip/normal replay: " + scene.getId());
            reference = frames;
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_ATTACHMENT_REPLAY_PASS: {} normal/skip/normal motion, passengers, docking, captions and stock", scene.getId());
    }

    /** Next can unlock before the storyboard's final speed change or caption has run. */
    private static boolean pendingInstructions(PonderScene scene) {
        try {
            var field = PonderScene.class.getDeclaredField("activeSchedule");
            field.setAccessible(true);
            return !((java.util.Collection<?>) field.get(scene)).isEmpty();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Preview extends PonderUI {
        Preview(PonderScene scene) { super(new ArrayList<>(List.of(scene))); }
        @Override public void tick() {
            // Advance UI fades while preserving the exact scene tick requested by the capture.
            try {
                var cooldown = PonderUI.class.getDeclaredField("skipCooling");
                cooldown.setAccessible(true);
                cooldown.setInt(this, 2);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            super.tick();
        }
    }

    private static final class TooltipPreview extends Screen {
        private final List<Item> items = List.of(AllBlocks.SHAFT.asItem(), AllBlocks.SHAFT.asItem(),
            AllBlocks.MECHANICAL_PRESS.asItem(), ModContent.BRASS_CLUTCH.asItem(),
            ModContent.COMPOSITE_FRICTION_PLATE.asItem(), AllBlocks.FLUID_PIPE.asItem());
        TooltipPreview() { super(Component.literal("Inventory tooltips")); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF252A2C);
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i); int x = 25 + i % 2 * (width / 2), y = 15 + i / 2 * (height / 3);
                graphics.renderItem(item.getDefaultInstance(), x, y);
                graphics.drawString(font, item.getDescription(), x + 25, y + 4, 0xFFFFFF);
                var lines = inventoryTooltip(item, i > 0, false);
                graphics.renderComponentTooltip(font, lines, x, y + 30);
            }
        }
    }
}
