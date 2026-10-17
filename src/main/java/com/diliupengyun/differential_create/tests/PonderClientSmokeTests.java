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
                resources();
                tooltips();
                motion();
                PonderIndex.reload();
                int before = PonderIndex.getSceneAccess().getRegisteredEntries().size();
                PonderIndex.reload();
                require(before == PonderIndex.getSceneAccess().getRegisteredEntries().size(), "Index reload duplicates scenes");
                entries = PonderIndex.getSceneAccess().getRegisteredEntries().stream().map(Map.Entry::getValue)
                    .filter(e -> e.getNamespace().equals(DifferentialCreate.MODID)).toList();
                require(entries.size() >= PonderLesson.values().length, "Addon Ponder plugin was not registered");
                PonderTextAudit.verify();
                PonderFanAudit.verifyRenderer();
                started = true;
            }
            if (checked < entries.size()) {
                var entry = entries.get(checked++);
                try {
                    var scene = PonderIndex.getSceneAccess().compile(List.of(entry)).getFirst();
                    var observation = new PlaybackObservation();
                    var animation = new PonderAnimationAudit();
                    for (int tick = 0; tick < 2000 && !scene.isFinished(); tick++) {
                        scene.forEach(element -> element.whileSkipping(scene));
                        scene.tick();
                        observation.sample(scene);
                        animation.sample(scene);
                    }
                    require(scene.isFinished(), "Storyboard did not finish: " + scene.getId());
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
                require(previews.size() == PonderLesson.values().length, "A lesson has no reachable storyboard: " + previews.keySet());
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
                captures = new ArrayList<>();
                for (var entry : (AUDIT ? auditScenes : previews).entrySet()) {
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
        private boolean loadMidpoint, ratioGeometry, ratioMotion, processor, steamLink, mountedRotor, backtankMotion, crusherFeed;
        private boolean comparatorMidpoint, speedComparator, sequenceMotion, sequenceReverse, sequenceHeld, ejectorLaunch;
        private int previousSequence = -1, sequenceStarts;
        private final java.util.Set<String> ejectorStates = new java.util.LinkedHashSet<>();
        private int frozenLinearTicks;
        private final Map<BlockPos, Double> mountedAngles = new LinkedHashMap<>();
        private final Map<net.createmod.ponder.api.element.WorldSectionElement, net.minecraft.world.phys.Vec3> offsets = new LinkedHashMap<>();

        void sample(PonderScene scene) {
            stateAudit.sample(scene);
            if (overlappingSections == null) overlappingSections = overlappingSections(scene);
            var wheels = new ArrayList<com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity>();
            double mountedChange = 0;
            for (var be : scene.getWorld().getBlockEntities()) {
                if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic) {
                    require(Float.isFinite(kinetic.getSpeed()), "Non-finite shaft speed in " + scene.getId());
                    Double angle = PonderKinetics.of(scene.getWorld()).angle(kinetic, 1);
                    require(angle == null || Double.isFinite(angle), "Non-finite shaft angle in " + scene.getId());
                    processor |= com.diliupengyun.differential_create.kinetics.MachineActivity.manages(kinetic)
                        && !(kinetic instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)
                        && Math.abs(kinetic.getSpeed()) > 1
                        && com.diliupengyun.differential_create.kinetics.MachineActivity.working(kinetic);
                    if (kinetic instanceof com.simibubi.create.content.equipment.armor.BacktankBlockEntity)
                        backtankMotion |= Math.abs(kinetic.getSpeed()) > 1;
                    Double mountedAngle = PonderKinetics.of(scene.getWorld()).outputAngle(kinetic, 1);
                    mountedRotor |= mountedAngle != null && Double.isFinite(mountedAngle) && Math.abs(mountedAngle) > .05;
                    if (mountedAngle != null) {
                        Double previous = mountedAngles.put(kinetic.getBlockPos(), mountedAngle);
                        if (previous != null) mountedChange = Math.max(mountedChange, Math.abs(mountedAngle - previous));
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
                    sequenceHeld |= motion.initialized && motion.index < 0 && motion.parked()
                        && be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic && kinetic.getSpeed() > 16;
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
            if (scene.getId().getPath().equals("structure_load") || scene.getId().getPath().equals("portable_docking")) {
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
                case "backtank" -> require(backtankMotion, "Placed backtank never turns under physical drive");
                case "crusher_work" -> require(crusherFeed, "Crushing illustration never feeds down into the gap");
                case "ejector_work" -> require(ejectorLaunch, "Ejector never launches an item from its own tray: " + ejectorStates);
                case "sequencer" -> require(sequenceMotion && sequenceReverse && sequenceHeld && sequenceStarts >= 2,
                    "Sequencer must drive, reverse, hold with live input and restart only on another pulse: "
                        + sequenceMotion + "/" + sequenceReverse + "/" + sequenceHeld + "/" + sequenceStarts);
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
                require(world.getBlockState(input).getBlock() instanceof com.simibubi.create.content.kinetics.gauge.GaugeBlock,
                    "Comparator input faces away from the gauge at " + pos);
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
            var expanded = KineticItemTooltip.describe(block, null, true);
            require(!expanded.isEmpty(), "Missing physical inventory stats for " + block);
            require(expanded.getFirst().getContents() instanceof TranslatableContents title
                && title.getKey().equals("tooltip.differentialcreate.mechanical_properties"), "Missing Shift properties group");
            var collapsed = KineticItemTooltip.describe(block, null, false);
            require(collapsed.stream().noneMatch(line -> line.getString().contains("su/RPM³")
                || line.getString().contains("结构强度")), "Bare properties leaked outside Shift details");
        }
        var press = KineticItemTooltip.describe(AllBlocks.MECHANICAL_PRESS.get(), null, false);
        require(press.size() == 2 && press.getFirst().getStyle().getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GRAY))
            && press.getLast().getString().contains("█"), "Torque must retain Create's grey header and coloured rating bar");
        for (var clutch : List.of(AllBlocks.CLUTCH.get(), ModContent.BRASS_CLUTCH.get())) {
            require(KineticItemTooltip.describe(clutch, null, false).isEmpty(),
                "Clutches must not acquire a native-looking capacity rating outside Shift");
            var expanded = KineticItemTooltip.describe(clutch, null, true);
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
            ModContent.BRASS_CLUTCH.asItem(), ModContent.GEAR_LEVER.asItem(), ModContent.STEAM_HOSE.asItem(),
            ModContent.CONDENSER_PIPE.asItem(), ModContent.COMPOSITE_FRICTION_PLATE.asItem())) {
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
    }

    /** These assertions failed with prescribed RPM and time-times-speed rendering. */
    private static void motion() {
        var level = new net.createmod.ponder.api.level.PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        var physics = PonderKinetics.of(level);
        physics.useExplicitSources();
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        var axis = net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;
        level.setBlock(new BlockPos(0, 1, 2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(facing, net.minecraft.core.Direction.EAST), 3);
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
            var first = replayFrames(scene);
            var second = replayFrames(scene);
            require(first.equals(second), "Replaying must reproduce every intermediate speed, shaft angle and section pose: " + scene.getId());
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_REPLAY_PASS: all custom scene variants reproduce every intermediate speed, shaft angle and section pose after rewind");
    }

    private record PlaybackFrame(Map<BlockPos, List<Double>> rotors, List<List<Double>> sections) {}

    private static List<PlaybackFrame> replayFrames(PonderScene scene) {
        var result = new ArrayList<PlaybackFrame>();
        scene.begin();
        for (int tick = 0; tick < 2000 && !scene.isFinished(); tick++) {
            scene.forEach(element -> element.whileSkipping(scene));
            scene.tick();
            result.add(new PlaybackFrame(snapshot(scene), sectionSnapshot(scene)));
        }
        require(scene.isFinished(), "Replay did not finish: " + scene.getId());
        return result;
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

    private static void nativePlayback() throws Exception {
        int failedBefore = playbackFailures.size();
        var ids = new HashSet<ResourceLocation>();
        var originals = new HashSet<ResourceLocation>();
        for (var item : List.of(AllBlocks.SHAFT, AllBlocks.COGWHEEL, AllBlocks.WATER_WHEEL,
            AllBlocks.WINDMILL_BEARING, AllBlocks.MECHANICAL_PRESS, AllBlocks.MECHANICAL_PUMP, AllBlocks.GEARBOX,
            AllBlocks.ENCASED_FAN)) originals.add(item.getId());
        var components = new java.util.LinkedHashSet<>(originals);
        var translated = new HashSet<String>();
        if (AUDIT) {
            entries.forEach(entry -> components.add(entry.getComponent()));
            components.add(AllItems.SCHEDULE.getId());
            components.add(AllBlocks.TRACK.getId());
            for (String key : language("en_us").keySet()) if (key.startsWith("create.ponder.")) translated.add(key.split("\\.")[2]);
        }
        for (var component : components) {
            for (var scene : PonderIndex.getSceneAccess().compile(component)) {
                if (AUDIT && !originals.contains(component) && !translated.contains(scene.getId().getPath())) continue;
                if (!scene.getId().getNamespace().equals("create") || !ids.add(scene.getId())) continue;
                try {
                    DifferentialCreate.LOGGER.info("DC_PONDER_NATIVE_PLAY: {}", scene.getId());
                    var animation = new PonderAnimationAudit();
                    double maximum = 0;
                    for (int tick = 0; tick < 3000 && !scene.isFinished(); tick++) {
                        scene.forEach(element -> element.whileSkipping(scene));
                        scene.tick();
                        animation.sample(scene);
                        for (var sample : snapshot(scene).values()) {
                            require(sample.stream().allMatch(Double::isFinite), "Non-finite native preview motion: " + scene.getId());
                            maximum = Math.max(maximum, Math.abs(sample.getFirst()));
                        }
                    }
                    require(scene.isFinished(), "Retained native tutorial failed to finish: " + scene.getId());
                    if (scene.getId().getPath().equals("windmill_source"))
                        require(maximum > 1, "Native driven-gear instructions failed to reach the windmill's source boundary");
                    if (AUDIT) auditScenes.putIfAbsent("native-" + scene.getId().getPath(), scene);
                    if (AUDIT) animation.save(scene, "native");
                    animation.verify(scene);
                } catch (Throwable failure) {
                    playbackFailures.add(scene.getId() + ": " + failure);
                    DifferentialCreate.LOGGER.error("DC_PONDER_NATIVE_FAIL: {}", scene.getId(), failure);
                }
            }
        }
        int failures = playbackFailures.size() - failedBefore;
        DifferentialCreate.LOGGER.info("DC_PONDER_NATIVE_{}: {} retained native kinetic tutorials checked; {} failed",
            failures == 0 ? "PASS" : "FAILURES", ids.size(), failures);
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
        private final List<Item> items = List.of(AllBlocks.MECHANICAL_PRESS.asItem(), AllBlocks.MECHANICAL_PRESS.asItem(),
            ModContent.BRASS_CLUTCH.asItem(), AllBlocks.MECHANICAL_PUMP.asItem());
        TooltipPreview() { super(Component.literal("Inventory tooltips")); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF252A2C);
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i); int x = 25 + i % 2 * (width / 2), y = 25 + i / 2 * (height / 2);
                graphics.renderItem(item.getDefaultInstance(), x, y);
                graphics.drawString(font, item.getDescription(), x + 25, y + 4, 0xFFFFFF);
                boolean expanded = i > 0;
                var description = ItemDescription.create(item, Palette.STANDARD_CREATE);
                var lines = new ArrayList<Component>();
                lines.add(item.getDescription());
                lines.addAll(expanded ? description.linesOnShift() : description.lines());
                var kinetic = KineticStats.create(item);
                if (kinetic != null) {
                    var block = ((net.minecraft.world.item.BlockItem) item).getBlock();
                    var stats = KineticItemTooltip.describe(block, null, expanded);
                    if (!stats.isEmpty()) lines.add(net.minecraft.network.chat.CommonComponents.EMPTY);
                    lines.addAll(stats);
                }
                graphics.renderComponentTooltip(font, lines, x, y + 30);
            }
        }
    }
}
