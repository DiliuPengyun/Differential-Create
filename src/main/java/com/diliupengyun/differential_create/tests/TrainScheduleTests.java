package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.condition.StationPoweredCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native conductor assembly, schedule conditions and physical carriage travel. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleTests {
    private TrainScheduleTests() {}

    private static void add(List<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        name = "train_schedule_" + name;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 250, 0, true, run));
    }

    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int cars : new int[]{1, 2}) {
            for (String track : new String[]{"flat", "up", "down"}) for (int direction : new int[]{1, -1})
                add(cases, "arrival_" + track + "_" + cars + "_" + direction,
                    h -> create(h, track, cars, f -> arrival(f, direction)));
            for (int direction : new int[]{1, -1})
                add(cases, "departure_" + cars + "_" + direction,
                    h -> create(h, "flat", cars, f -> departure(f, direction)));
            add(cases, "cyclic_" + cars, h -> create(h, "flat", cars, TrainScheduleTests::roundTrip));
        }
        for (int direction : new int[]{1, -1}) {
            for (String track : new String[]{"flat", "up", "down"}) {
                add(cases, "reverse_braking_" + track + "_" + direction,
                    h -> create(h, track, 1, f -> reverseBraking(f, direction)));
                add(cases, "red_signal_" + track + "_" + direction,
                    h -> create(h, track, 1, f -> redSignal(f, direction)));
            }
            for (String mode : new String[]{"service", "derailed", "emergency"})
                add(cases, "mandatory_brake_" + mode + "_" + direction,
                    h -> create(h, "flat", 1, f -> mandatoryBrake(f, direction, mode)));
            add(cases, "parked_coupling_" + direction,
                h -> create(h, "flat", 2, f -> parkedCoupling(f, direction)));
        }
        return cases;
    }

    private static void setup(GameTestHelper h) {
        var chunk = new ChunkPos(h.absolutePos(TrainTestFixture.WHEEL));
        for (int x = -1; x <= 1; x++) for (int z = -2; z <= 12; z++)
            h.getLevel().setChunkForced(chunk.x + x, chunk.z + z, true);
        var conductor = new BlockPos(10, 4, 6);
        h.setBlock(conductor, AllBlocks.BLAZE_BURNER.getDefaultState()
            .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        h.setBlock(conductor.north(), AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.SOUTH));
        h.setBlock(conductor.south(), AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.NORTH));
    }

    private static void create(GameTestHelper h, String track, int cars, Consumer<TrainTestFixture> run) {
        TrainTestFixture.create(h, track, false, false, TrainScheduleTests::setup, first -> {
            if (cars == 1) { prepare(first, run); return; }
            suspend(first); first.close();
            TrainTestFixture.create(h, track, false, false, TrainScheduleTests::setup, second -> {
                suspend(second); prepare(TrainTestFixture.couple(first, second), run);
            });
        });
    }

    private static void suspend(TrainTestFixture f) {
        // Save living entities through Create's normal unload path before the
        // fixture retires their old trains and reattaches both carriages.
        for (var c : f.train.carriages) {
            c.updateConductors(); c.forEachPresentEntity(e -> e.leftTickingChunks = true);
            c.manageEntities(f.h.getLevel());
        }
    }

    private static void prepare(TrainTestFixture f, Consumer<TrainTestFixture> run) {
        try {
            var train = f.train;
            train.doubleEnded = true;
            // Leave room for both travel directions on the same native edge.
            train.forEachTravellingPoint(p -> p.position += 100);
            for (var c : train.carriages) {
                c.updateContraptionAnchors(); c.manageEntities(f.h.getLevel()); c.updateConductors();
                f.h.assertTrue(c.anyAvailableEntity() != null && c.anyAvailableEntity().isAlive(), "Native carriage entity is loaded");
            }
            f.h.assertTrue(train.hasForwardConductor() && train.hasBackwardConductor(),
                "Actual captured blaze burners and controls qualify both schedule directions");
            run.accept(f);
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
    }

    private static ScheduleEntry entry(GlobalStation station, boolean waitForPower) {
        var instruction = new DestinationInstruction(); instruction.getData().putString("Text", station.name);
        var entry = new ScheduleEntry(); entry.instruction = instruction;
        entry.conditions.add(waitForPower ? List.of(new StationPoweredCondition()) : List.of());
        return entry;
    }

    private static void schedule(TrainTestFixture f, GlobalStation station) {
        var schedule = new Schedule(); schedule.cyclic = false; schedule.entries.add(entry(station, true));
        f.train.runtime.setSchedule(schedule, false);
    }

    private static void reach(TrainTestFixture f, GlobalStation station) {
        var train = f.train;
        for (int tick = 0; tick < 1000 && train.runtime.state != ScheduleRuntime.State.POST_TRANSIT; tick++)
            train.tick(f.h.getLevel());
        f.h.assertTrue(train.runtime.state == ScheduleRuntime.State.POST_TRANSIT && train.getCurrentStation() == station
            && station.getPresentTrain() == train && train.speed == 0,
            "Native schedule retains its parked station after every carriage travels: current=" + train.currentStation
                + " remaining=" + train.navigation.distanceToDestination + " speed=" + train.speed + " fault=" + TrainPhysics.state(train).failure);
    }

    private static void arrival(TrainTestFixture f, int direction) {
        var train = f.train;
        var station = TrainStationAssistTests.station(f, direction, 12);
        schedule(f, station); reach(f, station);
        for (int tick = 0; tick < 10; tick++) train.tick(f.h.getLevel());
        f.h.assertTrue(train.getCurrentStation() == station && train.runtime.state == ScheduleRuntime.State.POST_TRANSIT,
            "A stationary train stays registered while its station condition is pending");
        var powerPos = BlockPos.ZERO.below(); var previous = f.h.getBlockState(powerPos);
        try {
            f.h.setBlock(powerPos, Blocks.REDSTONE_BLOCK);
            for (int tick = 0; tick < 3; tick++) train.tick(f.h.getLevel());
            f.h.assertTrue(train.runtime.completed && train.getCurrentStation() == station,
                "Native station-powered condition completes after arrival without losing the parked train");
        } finally { f.h.setBlock(powerPos, previous); }
        f.finish();
    }

    private static void departure(TrainTestFixture f, int direction) {
        var train = f.train;
        var origin = TrainStationAssistTests.station(f, direction, 0); origin.name = "Schedule origin";
        train.setCurrentStation(origin); origin.reserveFor(train);
        var target = TrainStationAssistTests.station(f, direction, 12); target.name = "Schedule target";
        train.throttle = 0; schedule(f, target);
        double start = train.carriages.getFirst().getLeadingPoint().position;
        for (int tick = 0; tick < 40; tick++) train.tick(f.h.getLevel());
        f.h.assertTrue(train.getCurrentStation() == origin && origin.getPresentTrain() == train
            && train.carriages.getFirst().getLeadingPoint().position == start,
            "A schedule with closed admission cannot release the departure station");
        train.throttle = 1;
        for (int tick = 0; tick < 40 && train.getCurrentStation() == origin; tick++) train.tick(f.h.getLevel());
        f.h.assertTrue(train.getCurrentStation() != origin && origin.getPresentTrain() == null
            && (train.carriages.getFirst().getLeadingPoint().position - start) * direction > 0,
            "Actual rail movement releases the departure station in either direction");
        reach(f, target); f.finish();
    }

    private static void parkedCoupling(TrainTestFixture f, int direction) {
        var train = f.train;
        var station = TrainStationAssistTests.station(f, direction, 0);
        train.setCurrentStation(station); station.reserveFor(train);
        var rear = train.carriages.getLast();
        rear.bogeys.forEach(b -> { if (b != null) { b.leading().position -= .05; b.trailing().position -= .05; } });
        rear.updateContraptionAnchors();
        for (int tick = 0; tick < 10; tick++) train.tick(f.h.getLevel());
        f.h.assertTrue(train.speed == 0 && train.getCurrentStation() == station && station.getPresentTrain() == train,
            "Native coupling corrections at rest cannot turn into a station departure");
        f.finish();
    }

    private static void reverseBraking(TrainTestFixture f, int direction) {
        var train = f.train;
        var station = TrainStationAssistTests.station(f, direction, 10);
        schedule(f, station); train.speed = -direction * .15;
        double before = Math.abs(train.speed);
        train.tick(f.h.getLevel());
        f.h.assertTrue(train.navigation.destination == station && train.navigation.destinationBehindTrain == (direction < 0),
            "Native schedule targets a station opposite to the initial momentum");
        f.h.assertTrue(TrainPhysics.state(train).controlsBraking && before - Math.abs(train.speed) > .01
            && before - Math.abs(train.speed) < .025 && train.speed * direction < 0,
            "Reversal preserves bounded braking without flipping momentum");
        reach(f, station); f.finish();
    }

    private static void mandatoryBrake(TrainTestFixture f, int direction, String mode) {
        var train = f.train;
        var station = TrainStationAssistTests.station(f, direction, 1);
        schedule(f, station); train.runtime.tick(f.h.getLevel());
        if (mode.equals("service")) TrainPhysics.manual(train, direction, true);
        else if (mode.equals("derailed")) train.derailed = true;
        else TrainPhysics.state(train).emergencyStop = true;
        int water = f.water(); train.tick(f.h.getLevel());
        f.h.assertTrue(TrainPhysics.state(train).controlsBraking && TrainPhysics.state(train).controlsAdmission == 0
            && train.speed == 0 && f.water() == water,
            "Low-speed navigation cannot release " + mode + " brakes or consume drive steam");
        f.finish();
    }

    private static void roundTrip(TrainTestFixture f) {
        var train = f.train;
        var front = TrainStationAssistTests.station(f, 1, 8); front.name = "Schedule front";
        var back = TrainStationAssistTests.station(f, -1, 8); back.name = "Schedule back";
        var schedule = new Schedule(); schedule.cyclic = true;
        schedule.entries.add(entry(front, false)); schedule.entries.add(entry(back, false));
        train.runtime.setSchedule(schedule, false);
        int visits = 0; GlobalStation previous = null;
        for (int tick = 0; tick < 2000 && visits < 3; tick++) {
            train.tick(f.h.getLevel()); var current = train.getCurrentStation();
            if (current != null && current != previous) { visits++; previous = current; }
        }
        f.h.assertTrue(visits >= 3, "Cyclic schedules can arrive, depart, reverse and return with all carriages");
        f.finish();
    }

    private static void redSignal(TrainTestFixture f, int direction) {
        var train = f.train;
        var target = TrainStationAssistTests.station(f, direction, 12);
        var point = direction > 0 ? train.carriages.getFirst().getLeadingPoint() : train.carriages.getLast().getTrailingPoint();
        var signal = new SignalBoundary(); signal.setType(EdgePointType.SIGNAL); signal.setId(UUID.randomUUID());
        double position = point.position + direction * 6;
        signal.setLocation(direction > 0 ? Couple.create(point.node1.getLocation(), point.node2.getLocation())
            : Couple.create(point.node2.getLocation(), point.node1.getLocation()), direction > 0 ? position : point.edge.getLength() - position);
        signal.blockEntities.getFirst().put(f.h.absolutePos(BlockPos.ZERO), true);
        var occupied = new SignalEdgeGroup(UUID.randomUUID()); occupied.reserved = signal;
        var opposite = new SignalEdgeGroup(UUID.randomUUID());
        Create.RAILWAYS.signalEdgeGroups.put(occupied.id, occupied); Create.RAILWAYS.signalEdgeGroups.put(opposite.id, opposite);
        signal.setGroup(true, occupied.id); signal.setGroup(false, opposite.id); train.graph.addPoint(EdgePointType.SIGNAL, signal);
        try {
            schedule(f, target);
            for (int tick = 0; tick < 700; tick++) train.tick(f.h.getLevel());
            f.h.assertTrue(train.navigation.waitingForSignal != null && train.currentStation == null
                && (point.position - position) * direction <= 1e-6 && Math.abs(train.speed) < .01,
                "Automatic schedules wait before red signals on level and sloped tracks");
            occupied.reserved = null; signal.blockEntities.getFirst().replaceAll((pos, powered) -> false);
            reach(f, target); f.finish();
        } finally { Create.RAILWAYS.signalEdgeGroups.remove(occupied.id); Create.RAILWAYS.signalEdgeGroups.remove(opposite.id); }
    }
}
