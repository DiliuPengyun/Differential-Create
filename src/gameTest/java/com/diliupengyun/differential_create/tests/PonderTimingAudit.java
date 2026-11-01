package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderLocalization;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** The native timeline must cover physical work without freezing or moving seek targets. */
final class PonderTimingAudit {
    private static final Field ACTIVE = field(PonderScene.class, "activeSchedule");
    private static final Field ELAPSED = field(PonderAwait.class, "elapsed");
    private record HoseFrame(int time, boolean finished, int water, float offset, float rpm, Direction pump, int storedWater) {}
    private int previousTime = -1;
    private long previousWorldTime = -1;

    void observe(PonderScene scene) {
        sample(scene);
        long worldTime = com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).time();
        if (previousTime >= 0 && worldTime > previousWorldTime && previousTime < scene.getTotalTime())
            require(scene.getCurrentTime() == Math.min(scene.getTotalTime(), previousTime + worldTime - previousWorldTime),
                scene.getId() + " pauses or jumps its cursor while the physical world advances");
        previousTime = scene.getCurrentTime();
        previousWorldTime = worldTime;
    }

    static void verify() {
        clock();
        detectMissingTime();
        playbackIntervals();
        for (var component : List.of(AllBlocks.PORTABLE_STORAGE_INTERFACE.getId(), AllBlocks.PORTABLE_FLUID_INTERFACE.getId()))
            for (var scene : PonderIndex.getSceneAccess().compile(component))
                if (scene.getId().getPath().equals("portable_storage_interface_redstone"))
                    require(scene.getTotalTime() == 80, "Portable redstone tutorial must retain Create's 80-tick completion timeline");
        for (var scene : PonderIndex.getSceneAccess().compile(AllBlocks.HOSE_PULLEY.getId())) {
            String id = scene.getId().getPath();
            if (!id.equals("hose_pulley") && !id.equals("hose_pulley_level") && !id.equals("hose_pulley_infinite")) continue;
            List<HoseFrame> reference = null;
            for (boolean skipping : new boolean[]{false, true, false}) {
                scene.begin();
                var frames = new ArrayList<HoseFrame>();
                var timing = new PonderTimingAudit();
                for (int tick = 0; tick < 3000 && !scene.isFinished(); tick++) {
                    if (skipping) scene.forEach(element -> element.whileSkipping(scene));
                    scene.tick();
                    timing.observe(scene);
                    frames.add(hoseFrame(scene));
                }
                require(scene.isFinished(), id + " did not finish");
                require(frames.getLast().water() == (id.equals("hose_pulley") ? 0 : id.equals("hose_pulley_level") ? 27 : 15),
                    id + " finished before the actual water result");
                if (id.equals("hose_pulley_infinite")) {
                    require(frames.getLast().offset() == 3 && Math.abs(frames.getLast().rpm()) < 1e-5f,
                        "ocean hose must settle inside the visible water layer");
                    require(frames.getLast().storedWater() >= 1000, "ocean tutorial finishes before a real bucket arrives");
                }
                if (reference == null) reference = frames;
                else require(reference.equals(frames), id + " timing/result changes on replay or skip");
            }
            // Seek straight to the end before any durations have been observed.
            scene.begin();
            scene.seekToTime(scene.getTotalTime());
            var end = hoseFrame(scene);
            require(end.water() == reference.getLast().water() && (!id.equals("hose_pulley_infinite") || end.storedWater() >= 1000),
                id + " end seek skipped a real result");
            // Keyframe navigation must land at the same physical state as normal playback.
            for (int key = 0; key < scene.getKeyframeCount(); key++) {
                scene.begin();
                int time = scene.getKeyframeTime(key);
                if (time <= 0) continue;
                scene.seekToTime(time);
                var expected = reference.stream().filter(frame -> frame.time() == time).findFirst().orElseThrow();
                require(expected.equals(hoseFrame(scene)), id + " keyframe seek differs at " + time);
            }
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_TIMING_PASS: continuous native clock, scheduled physical work, missing-time detection, hose stop-before-drain, ocean intake depth, first seek, keyframes and replay");
    }

    private static void clock() {
        var world = new ClockWorld();
        world.createBackup();
        var scene = new PonderScene(world, new PonderLocalization(), "differentialcreate",
            ResourceLocation.fromNamespaceAndPath("differentialcreate", "timing_audit"), List.of(), List.of());
        var builder = scene.builder();
        builder.idle(3);
        // Reserve real work just like a native animation, then verify its result.
        builder.idle(100);
        var first = new PonderAwait(s -> world.ticks >= 103, 150);
        var last = new PonderAwait(s -> world.ticks >= 300, 200);
        builder.addInstruction(first);
        builder.addKeyframe();
        builder.idle(5);
        builder.addInstruction(new PonderAwait(s -> true, 1));
        builder.idle(200);
        builder.addInstruction(last);
        builder.markAsFinished();
        for (int pass = 0; pass < 3; pass++) {
            scene.begin();
            require(scene.getTotalTime() == 311, "work time must be in the timeline from the first playback, without accumulating on replay");
            if (pass == 1) scene.seekToTime(scene.getTotalTime());
            else {
                float previous = 0;
                while (!last.isComplete() && world.ticks < 1000) {
                    scene.tick();
                    sample(scene);
                    require(scene.getSceneProgress() > previous, "cursor stopped or moved backwards");
                    previous = scene.getSceneProgress();
                    require(scene.getCurrentTime() == world.ticks, "cursor must follow elapsed simulation ticks");
                }
            }
            require(first.isComplete() && last.isComplete(), "seek failed to reach real results");
            require(world.ticks == 311, "scheduled work and physical clock diverged: " + world.ticks);
            require(scene.getSceneProgress() == 1, "cursor must reach the end after the result");
            scene.tick();
            require(scene.isFinished(), "completion cue was lost");
        }
        var plain = new PonderScene(world, new PonderLocalization(), "create",
            ResourceLocation.fromNamespaceAndPath("create", "timing_audit"), List.of(), List.of());
        plain.builder().idle(10);
        plain.begin();
        for (int tick = 1; tick <= 10; tick++) {
            plain.tick();
            require(plain.getCurrentTime() == tick, "native scenes without result gates changed timing");
        }
    }

    private static void playbackIntervals() {
        var mc = Minecraft.getInstance();
        var previousScreen = mc.screen;
        var length = field(PonderUI.class, "extendedTickLength");
        var remaining = field(PonderUI.class, "extendedTickTimer");
        var ui = PonderUI.of(AllBlocks.MECHANICAL_PISTON.getId());
        boolean previousSlowMode = ui.isComfyReadingEnabled();
        int slowExits = 0;
        try {
            for (boolean toggle : new boolean[]{false, true}) {
                ui = PonderUI.of(AllBlocks.MECHANICAL_PISTON.getId());
                mc.setScreen(ui);
                ui.setComfyReadingEnabled(true);
                double previousEnd = Double.NaN;
                int previousLength = 0;
                for (int tick = 0; tick < 1800; tick++) {
                    if (toggle && (tick == 160 || tick == 241 || tick == 420))
                        ui.setComfyReadingEnabled(!ui.isComfyReadingEnabled());
                    // Exercise the real UI clock, including caption disappearance
                    // and changes in slow-reading mode between simulation ticks.
                    ui.tick();
                    int duration = length.getInt(ui), left = remaining.getInt(ui);
                    long time = PonderSimulation.of(ui.getActiveScene().getWorld()).time();
                    double start = time + (duration - left) / (duration + 1.0);
                    double end = time + (duration - left + 1) / (duration + 1.0);
                    require(duration >= left && left >= 0,
                        "Ponder UI extrapolates outside its physical frame at client tick " + tick);
                    require(Double.isNaN(previousEnd) || Math.abs(start - previousEnd) < 1e-9,
                        "Ponder UI moves backwards or skips motion when reading speed changes at client tick " + tick);
                    if (previousLength > 0 && duration == 0) slowExits++;
                    previousEnd = end;
                    previousLength = duration;
                }
            }
            ui = PonderUI.of(AllBlocks.MECHANICAL_PISTON.getId());
            mc.setScreen(ui);
            ui.setComfyReadingEnabled(true);
            for (int countdown : new int[]{1, 2}) {
                int attempts = 0;
                do { ui.tick(); }
                while (++attempts < 400 && (remaining.getInt(ui) != countdown
                    || PonderSimulation.of(ui.getActiveScene().getWorld()).time() < 100));
                require(attempts < 400, "No slow interval available for identification test");
                float displayed = PonderUI.getPartialTicks();
                ui.keyPressed(81, 0, 0);
                require(Math.abs(PonderUI.getPartialTicks() - displayed) < 1e-6,
                    "Identification jumps from the displayed slow-motion pose");
                for (int paused = 0; paused < 5; paused++) ui.tick();
                require(Math.abs(PonderUI.getPartialTicks() - displayed) < 1e-6,
                    "Identification must hold the captured pose");
                ui.keyPressed(81, 0, 0);
                require(Math.abs(PonderUI.getPartialTicks() - displayed) < 1e-6,
                    "Resuming identification jumps to an unrelated UI timer phase");
                double previous = PonderSimulation.of(ui.getActiveScene().getWorld()).time() + displayed;
                for (int resumed = 0; resumed < 12; resumed++) {
                    ui.tick();
                    float partial = PonderUI.getPartialTicks();
                    double current = PonderSimulation.of(ui.getActiveScene().getWorld()).time() + partial;
                    require(partial >= 0 && partial <= 1 && current >= previous,
                        "Resumed playback extrapolates or reverses its displayed time");
                    previous = current;
                }
            }
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        finally {
            ui.setComfyReadingEnabled(previousSlowMode);
            mc.setScreen(previousScreen);
        }
        require(slowExits >= 4, "Real caption and slow-reading transitions were not exercised");
        DifferentialCreate.LOGGER.info("DC_PONDER_UI_TIMING_PASS: real UI intervals remain continuous across captions, slow-reading transitions and identification pause/resume");
    }

    private static void detectMissingTime() {
        var world = new ClockWorld();
        world.createBackup();
        var scene = new PonderScene(world, new PonderLocalization(), "differentialcreate",
            ResourceLocation.fromNamespaceAndPath("differentialcreate", "missing_time_audit"), List.of(), List.of());
        scene.builder().idle(5);
        scene.builder().addInstruction(new PonderAwait(s -> world.ticks >= 12, 20));
        scene.builder().idle(20);
        scene.begin();
        for (int tick = 0; tick < 6; tick++) scene.tick();
        boolean detected = false;
        try { sample(scene); }
        catch (AssertionError expected) { detected = expected.getMessage().contains("unbudgeted physical wait"); }
        require(detected, "audit failed to detect a result gate with missing scheduled work time");
    }

    static void sample(PonderScene scene) {
        try {
            for (var instruction : (List<?>) ACTIVE.get(scene)) {
                if (!(instruction instanceof PonderAwait gate) || gate.isComplete()) continue;
                require(ELAPSED.getInt(gate) == 0, scene.getId() + " has an unbudgeted physical wait at tick " + scene.getCurrentTime());
                require(scene.getSceneProgress() < 1, scene.getId() + " shows a full progress bar with a result still pending");
                require(!scene.isFinished(), scene.getId() + " unlocks completion with a result still pending");
            }
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    private static HoseFrame hoseFrame(PonderScene scene) {
        boolean intro = scene.getId().getPath().equals("hose_pulley");
        boolean ocean = scene.getId().getPath().equals("hose_pulley_infinite");
        var hose = (HosePulleyBlockEntity) scene.getWorld().getBlockEntity(new BlockPos(1, ocean ? 3 : intro ? 5 : 6, ocean ? 2 : 1));
        var pump = scene.getWorld().getBlockState(new BlockPos(3, 2, ocean ? 2 : 1)).getValue(PumpBlock.FACING);
        if (ocean) require(hose.getInterpolatedOffset(1) <= 3.001f, "ocean hose extends below the visible water layer");
        if ((intro || ocean) && pump == Direction.DOWN) {
            require(Math.abs(hose.getSpeed()) < 1e-5f, scene.getId() + " pump reverses before the hose stops at "
                + scene.getCurrentTime() + ": offset=" + hose.getInterpolatedOffset(1) + ", rpm=" + hose.getSpeed());
            require(Math.abs(hose.getInterpolatedOffset(1) - 3) < .001, "pump reverses before the hose reaches the pool bottom");
        }
        int water = 0;
        for (var pos : BlockPos.betweenClosed(0, ocean ? 0 : 1, 0, 2, ocean ? 0 : intro ? 2 : 3, ocean ? 4 : 2))
            if (!scene.getWorld().getFluidState(pos).isEmpty()) water++;
        int storedWater = ocean ? ((com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)
            scene.getWorld().getBlockEntity(new BlockPos(4, 1, 1))).getTankInventory().getFluidAmount() : 0;
        return new HoseFrame(scene.getCurrentTime(), scene.isFinished(), water, hose.getInterpolatedOffset(1), hose.getSpeed(), pump, storedWater);
    }

    private static final class ClockWorld extends PonderLevel {
        int ticks;
        ClockWorld() { super(BlockPos.ZERO, Minecraft.getInstance().level); }
        @Override public void tick() { super.tick(); ticks++; }
        @Override public void restore() { super.restore(); ticks = 0; }
    }

    private static Field field(Class<?> type, String name) {
        try { var result = type.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
