package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;

/** Asynchronous schedule scenarios: every poll observes the real railway manager.
 * No scenario advances a train or its runtime outside the normal server tick. */
final class TrainScheduleScenario {
    final GameTestHelper h;
    final TrainTestFixture f;
    final List<Runnable> cleanup = new ArrayList<>();
    private boolean closed;

    private TrainScheduleScenario(TrainTestFixture f) { this.f = f; h = f.h; cleanup.add(f::close); }
    static TrainScheduleScenario restored(GameTestHelper h, Train train) { return new TrainScheduleScenario(TrainTestFixture.restored(h, train)); }

    static void create(GameTestHelper h, String track, int cars, String crew, Consumer<TrainScheduleScenario> ready) {
        create(h, track, cars, crew, setup -> {}, ready);
    }

    static void create(GameTestHelper h, String track, int cars, String crew, Consumer<GameTestHelper> customise, Consumer<TrainScheduleScenario> ready) {
        TrainTestFixture.create(h, track, false, false, setup -> { setup(setup, crew); customise.accept(setup); }, first -> {
            if (cars == 1) { prepare(first, ready); return; }
            suspend(first); first.close();
            TrainTestFixture.create(h, track, false, false, setup -> { setup(setup, crew); customise.accept(setup); }, second -> {
                suspend(second); prepare(TrainTestFixture.couple(first, second), ready);
            });
        });
    }

    static void setup(GameTestHelper h, String crew) {
        var chunk = new ChunkPos(h.absolutePos(TrainTestFixture.WHEEL));
        for (int x = -1; x <= 1; x++) for (int z = -2; z <= 14; z++)
            h.getLevel().setChunkForced(chunk.x + x, chunk.z + z, true);
        var burner = new BlockPos(10, 4, 6);
        h.setBlock(burner, AllBlocks.BLAZE_BURNER.getDefaultState()
            .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        if (crew.equals("both") || crew.equals("front")) h.setBlock(burner.north(), AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.SOUTH));
        if (crew.equals("both") || crew.equals("back")) h.setBlock(burner.south(), AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.NORTH));
    }

    private static void prepare(TrainTestFixture f, Consumer<TrainScheduleScenario> ready) {
        var s = new TrainScheduleScenario(f);
        s.run(() -> {
            f.train.doubleEnded = true;
            f.train.forEachTravellingPoint(p -> p.position += 100);
            for (var c : f.train.carriages) {
                c.updateContraptionAnchors();
                f.h.getLevel().getChunkAt(BlockPos.containing(c.getLeadingPoint().getPosition(f.train.graph)));
                c.manageEntities(f.h.getLevel()); c.updateConductors();
            }
            // Moving to the forced corridor can cross a chunk while its entity
            // ticking status is still being promoted. Let native management
            // recreate the carriage before boarding passengers or taking inputs.
            s.after(4, () -> s.until(() -> f.train.carriages.stream().allMatch(c -> {
                var entity = c.anyAvailableEntity();
                return entity != null && entity.isAlive() && !entity.leftTickingChunks;
            }), 1200, "relocated carriages load in the forced corridor", () -> ready.accept(s)));
        });
    }

    static void suspend(TrainTestFixture f) {
        for (var c : f.train.carriages) {
            c.updateConductors(); c.forEachPresentEntity(e -> e.leftTickingChunks = true); c.manageEntities(f.h.getLevel());
        }
    }

    Train train() { return f.train; }

    GlobalStation station(int direction, double distance, String name) {
        var station = TrainStationAssistTests.station(f, direction, distance); station.name = name; return station;
    }

    GlobalStation park(int direction) {
        var station = station(direction, 0, "Origin"); train().setCurrentStation(station); station.reserveFor(train()); return station;
    }

    static ScheduleEntry entry(GlobalStation station, ScheduleWaitCondition... conditions) { return entry(station.name, conditions); }
    static ScheduleEntry entry(String name, ScheduleWaitCondition... conditions) {
        var instruction = new DestinationInstruction(); instruction.getData().putString("Text", name);
        var entry = new ScheduleEntry(); entry.instruction = instruction; entry.conditions.add(List.of(conditions)); return entry;
    }
    static ScheduledDelay delay(int ticks) {
        var delay = new ScheduledDelay(); delay.getData().putInt("Value", ticks); delay.getData().putInt("TimeUnit", 0); return delay;
    }
    void schedule(boolean cyclic, ScheduleEntry... entries) {
        var schedule = new Schedule(); schedule.cyclic = cyclic; schedule.entries.addAll(List.of(entries)); train().runtime.setSchedule(schedule, false);
    }

    void after(int ticks, Runnable next) {
        long start = h.getLevel().getGameTime();
        h.runAfterDelay(ticks, () -> run(() -> {
            h.assertTrue(h.getLevel().getGameTime() >= start + ticks, "schedule scenario advances actual world time"); next.run();
        }));
    }

    void until(BooleanSupplier condition, int timeout, String message, Runnable next) {
        long start = h.getTick();
        class Poll implements Runnable {
            @Override public void run() {
                TrainScheduleScenario.this.run(() -> {
                    if (condition.getAsBoolean()) { next.run(); return; }
                    h.assertTrue(h.getTick() - start < timeout, message + ": state=" + train().runtime.state
                        + " entry=" + train().runtime.currentEntry + " speed=" + train().speed + " remaining=" + train().navigation.distanceToDestination);
                    // GameTest removes the executed Runnable from its task map.
                    // A fresh method reference keeps the next poll's entry alive.
                    h.runAfterDelay(1, this::run);
                });
            }
        }
        new Poll().run();
    }

    void arrived(GlobalStation station, Runnable next) {
        until(() -> train().getCurrentStation() == station && train().runtime.state == State.POST_TRANSIT,
            1000, "native schedule reaches its station", () -> {
                h.assertTrue(train().speed == 0 && station.getPresentTrain() == train(),
                    "arrival retains the station's actual parked train: speed=" + train().speed
                    + " present=" + (station.getPresentTrain() == null ? null : station.getPresentTrain().id)
                    + " expected=" + train().id + " manual=" + com.diliupengyun.differential_create.trains.TrainPhysics.state(train()).manualCommand
                    + " manualTick=" + train().manualTick); next.run();
            });
    }

    void crew(boolean forward, boolean backward) {
        // Availability is a native carriage input. Initial front/back/missing
        // qualifications are separately captured from real blocks at assembly.
        for (var c : train().carriages) {
            c.forEachPresentEntity(e -> ((CarriageContraption) e.getContraption()).blockConductors = Couple.create(forward, backward));
            c.updateConductors();
        }
    }

    void reload() { suspend(f); f.reload(); }

    void second(int direction, double ahead, Consumer<TrainTestFixture> ready) {
        TrainTestFixture.create(h, "flat", false, false, setup -> setup(setup, "both"), second -> run(() -> {
            cleanup.add(second::close);
            var reference = direction > 0 ? train().carriages.getFirst().getLeadingPoint() : train().carriages.getLast().getTrailingPoint();
            var old = direction > 0 ? second.train.carriages.getFirst().getLeadingPoint() : second.train.carriages.getLast().getTrailingPoint();
            double offset = reference.position + direction * ahead - old.position;
            Create.RAILWAYS.trackNetworks.remove(second.graph.id); second.train.graph = train().graph; second.train.doubleEnded = true;
            second.train.forEachTravellingPoint(p -> { p.node1 = reference.node1; p.node2 = reference.node2; p.edge = reference.edge; p.position += offset; });
            for (var c : second.train.carriages) { c.updateContraptionAnchors(); c.manageEntities(h.getLevel()); c.updateConductors(); }
            second.train.updateSignalBlocks = true; ready.accept(second);
        }));
    }

    void run(Runnable action) {
        if (closed) return;
        try { action.run(); } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    void close() {
        if (closed) return;
        closed = true;
        for (int i = cleanup.size() - 1; i >= 0; i--) cleanup.get(i).run();
    }
    void succeed() { close(); h.succeed(); }
}
