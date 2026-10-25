package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderLocalization;
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
