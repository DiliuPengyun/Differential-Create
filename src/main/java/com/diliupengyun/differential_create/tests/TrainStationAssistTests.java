package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual cab input, native directional station lookup and station-assist state transitions. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainStationAssistTests {
    private TrainStationAssistTests() {}
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 180, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (boolean inverted : new boolean[]{false, true}) for (int gear : new int[]{0, 1, TrainMechanics.REVERSE_GEAR})
            for (int momentum : new int[]{-1, 0, 1}) for (boolean drive : new boolean[]{false, true})
                add(tests, "station_assist_lookup_" + inverted + "_" + gear + "_" + momentum + "_" + drive,
                    h -> lookup(h, inverted, gear, momentum, drive));
        for (boolean inverted : new boolean[]{false, true}) for (int gear : new int[]{1, TrainMechanics.REVERSE_GEAR})
            for (String action : new String[]{"hold", "release", "brake", "exit", "stop_controls", "unload", "reload", "shift_neutral", "shift_opposite", "red_signal"})
                add(tests, "station_assist_" + action + "_" + inverted + "_" + gear,
                    h -> operation(h, inverted, gear, action));
        for (boolean inverted : new boolean[]{false, true})
            for (String resource : new String[]{"no_engine", "no_water", "no_heat", "closed_valve", "neutral", "neutral_rev"})
                add(tests, "station_assist_supply_" + resource + "_" + inverted, h -> supply(h, inverted, resource));
        for (boolean inverted : new boolean[]{false, true}) for (int gear : new int[]{1, TrainMechanics.REVERSE_GEAR})
            for (String condition : new String[]{"occupied", "wrong_face", "out_of_range", "unsafe_window", "reachable"})
                add(tests, "station_assist_visibility_" + condition + "_" + inverted + "_" + gear,
                    h -> visibility(h, inverted, gear, condition));
        return tests;
    }
    static GlobalStation station(TrainTestFixture f, int direction, double distance) {
        var point = direction > 0 ? f.train.carriages.getFirst().getLeadingPoint() : f.train.carriages.getLast().getTrailingPoint();
        var station = new GlobalStation(); station.setType(EdgePointType.STATION); station.setId(UUID.randomUUID());
        station.name = "Assist " + direction; station.blockEntityPos = f.h.absolutePos(BlockPos.ZERO);
        station.blockEntityDimension = f.h.getLevel().dimension();
        double position = point.position + direction * distance;
        station.setLocation(direction > 0 ? Couple.create(point.node1.getLocation(), point.node2.getLocation())
            : Couple.create(point.node2.getLocation(), point.node1.getLocation()), direction > 0 ? position : point.edge.getLength() - position);
        f.train.graph.addPoint(EdgePointType.STATION, station); return station;
    }
    private static void lookup(GameTestHelper h, boolean inverted, int gear, int momentum, boolean drive) {
        TrainDrivingTests.create(h, gear, inverted, f -> {
            var player = TrainDrivingTests.player(f);
            var front = station(f, 1, 10); var back = station(f, -1, 10);
            int direction = momentum != 0 ? momentum : (inverted ? -1 : 1) * (gear == TrainMechanics.REVERSE_GEAR ? -1 : 1);
            f.train.speed = momentum * .02;
            TrainDrivingTests.control(f, player, drive ? List.of(0, 4) : List.of(4));
            h.assertTrue(f.train.navigation.destination == (direction > 0 ? front : back),
                "Space targets the next station in actual travel direction, or selected gear/cab direction at rest: cab="
                    + inverted + " gear=" + gear + " momentum=" + momentum + " destination=" + f.train.navigation.destination);
            h.assertTrue(f.train.navigation.destinationBehindTrain == (direction < 0) && TrainPhysics.state(f.train).gear == gear,
                "Station assist respects direction without changing the player's gear");
            f.finish();
        });
    }
    private static void operation(GameTestHelper h, boolean inverted, int gear, String action) {
        TrainDrivingTests.create(h, gear, inverted, f -> {
            var player = TrainDrivingTests.player(f);
            int direction = (inverted ? -1 : 1) * (gear == TrainMechanics.REVERSE_GEAR ? -1 : 1);
            var target = station(f, direction, 10); f.train.speed = direction * .02;
            TrainDrivingTests.control(f, player, List.of(4));
            h.assertTrue(f.train.navigation.destination == target, "Actual space press starts native station navigation");
            if (action.equals("unload") || action.equals("reload")) {
                int water = f.water();
                if (action.equals("reload")) f.reload();
                else {
                    f.entity().setControllingPlayer(player.getUUID());
                    f.entity().remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
                }
                h.assertTrue(f.train.navigation.destination == null && !TrainPhysics.state(f.train).stationAssist,
                    "A held space command cannot outlive the driver entity or replay from a current save");
                for (int tick = 0; tick < 20; tick++) f.train.tick(h.getLevel());
                h.assertTrue(f.water() == water && f.train.currentStation == null,
                    "Driver interruption cannot keep feeding a remote station approach");
                f.finish(); return;
            }
            if (action.equals("stop_controls")) {
                f.entity().stopControlling(new BlockPos(10, 4, 5).subtract(TrainTestFixture.WHEEL));
                h.assertTrue(f.train.navigation.destination == null && !TrainPhysics.state(f.train).stationAssist,
                    "Native control release, including a lost driver, cancels assistance");
                f.finish(); return;
            }
            if (action.startsWith("shift_")) {
                double speed = f.train.speed;
                TrainPhysics.shift(f.train, action.equals("shift_neutral") ? 0 : gear == 1 ? TrainMechanics.REVERSE_GEAR : 1);
                int water = f.water();
                for (int tick = 0; tick < 80; tick++) { TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel()); }
                h.assertTrue(f.water() == water && Math.abs(f.train.speed) <= Math.abs(speed),
                    "Station assistance cannot open admission through neutral or the opposite gear");
                TrainPhysics.shift(f.train, gear);
                for (int tick = 0; tick < 3000 && f.train.currentStation == null; tick++) {
                    TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
                }
                h.assertTrue(f.train.getCurrentStation() == target, "Selecting the matching gear resumes the held approach");
                f.finish(); return;
            }
            if (action.equals("red_signal")) { signal(h, f, player, target, direction); return; }
            if (action.equals("exit")) {
                h.assertTrue(!f.entity().control(new BlockPos(10, 4, 5).subtract(TrainTestFixture.WHEEL), List.of(4, 5), player),
                    "Exit input releases native controls");
                h.assertTrue(f.train.navigation.destination == null, "Leaving controls cancels player station assistance");
                f.finish(); return;
            }
            if (!action.equals("hold")) {
                TrainDrivingTests.control(f, player, action.equals("brake") ? List.of(1, 4) : List.of());
                h.assertTrue(f.train.navigation.destination == null && !TrainPhysics.state(f.train).stationAssist
                    && !TrainPhysics.state(f.train).parkingBrake && TrainPhysics.state(f.train).brake == action.equals("brake"),
                    "Releasing space cancels assistance; S plus space immediately returns to service braking");
                f.finish(); return;
            }
            for (int tick = 0; tick < 3000 && f.train.currentStation == null; tick++) {
                TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
                h.assertTrue(TrainPhysics.state(f.train).gear == gear, "Holding station assist never automatically shifts player gears");
            }
            h.assertTrue(f.train.getCurrentStation() == target && f.train.speed == 0 && TrainPhysics.state(f.train).parkingBrake,
                "Holding space reaches and parks at the native station with finite steam/braking");
            int water = f.water();
            for (int tick = 0; tick < 5; tick++) { TrainDrivingTests.control(f, player, List.of(0, 4)); f.train.tick(h.getLevel()); }
            h.assertTrue(f.train.getCurrentStation() == target && f.train.speed == 0 && f.water() == water,
                "Holding space after arrival prevents W from departing or consuming water");
            for (int tick = 0; tick < 10; tick++) { TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel()); }
            h.assertTrue(f.train.getCurrentStation() == target && f.train.speed == 0 && f.water() == water
                && TrainPhysics.state(f.train).parkingBrake && TrainPhysics.state(f.train).controlsBraking,
                "Releasing Space after assisted arrival retains the parked train and brake animation");
            for (int tick = 0; tick < 20; tick++) { TrainDrivingTests.control(f, player, List.of(0)); f.train.tick(h.getLevel()); }
            h.assertTrue(!TrainPhysics.state(f.train).parkingBrake && f.train.currentStation == null && f.train.speed * direction > 0,
                "W unlocks the assisted station stop; actual rail travel releases its station registration");
            f.finish();
        });
    }
    private static void supply(GameTestHelper h, boolean inverted, String resource) {
        TrainDrivingTests.create(h, resource.startsWith("neutral") ? 0 : 1, inverted, setup -> {
            if (resource.equals("no_engine")) ((com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity)
                setup.getBlockEntity(TrainTestFixture.WHEEL)).setBogeyData(new net.minecraft.nbt.CompoundTag());
            if (resource.equals("no_heat")) setup.setBlock(TrainTestFixture.BOILER.below(), net.minecraft.world.level.block.Blocks.AIR);
        }, f -> {
            var player = TrainDrivingTests.player(f); var target = station(f, inverted ? -1 : 1, 10);
            if (resource.equals("no_water")) f.train.carriages.getFirst().storage.getFluids().drain(Integer.MAX_VALUE,
                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (resource.equals("no_heat")) {
                var inventory = f.train.carriages.getFirst().storage.getFuelItems();
                for (int slot = 0; slot < inventory.getSlots(); slot++) inventory.extractItem(slot, 64, false);
            }
            if (resource.equals("closed_valve")) f.train.throttle = 0;
            int water = f.water(); double start = f.train.carriages.getFirst().getLeadingPoint().position;
            for (int tick = 0; tick < 60; tick++) {
                TrainDrivingTests.control(f, player, resource.equals("neutral_rev") ? List.of(0, 4) : List.of(4)); f.train.tick(h.getLevel());
                h.assertTrue(f.train.speed == 0 && f.train.carriages.getFirst().getLeadingPoint().position == start,
                    "Unpowered or neutral station assistance creates no wheel motion: " + resource);
            }
            h.assertTrue(f.train.navigation.destination == target && f.train.currentStation == null,
                "An immobile train cannot claim arrival at a remote station");
            if (resource.equals("neutral_rev")) h.assertTrue(f.water() < water && TrainPhysics.state(f.train).engineRpm > 0,
                "W plus space still opens the disconnected neutral engine without moving the wheels");
            else h.assertTrue(f.water() == water, "Closed or unavailable admission does not take water: " + resource);
            f.finish();
        });
    }
    private static void visibility(GameTestHelper h, boolean inverted, int gear, String condition) {
        TrainDrivingTests.create(h, gear, inverted, f -> {
            var player = TrainDrivingTests.player(f);
            int direction = (inverted ? -1 : 1) * (gear == TrainMechanics.REVERSE_GEAR ? -1 : 1);
            double distance = condition.equals("unsafe_window") ? .85 : condition.equals("out_of_range") ? 40 : 3;
            var target = station(f, direction, distance);
            if (condition.equals("wrong_face")) target.setLocation(target.edgeLocation.swap(),
                f.train.carriages.getFirst().getLeadingPoint().edge.getLength() - target.position);
            var parked = new com.simibubi.create.content.trains.entity.Train(UUID.randomUUID(), null, f.train.graph, List.of(), List.of(), false, 0);
            if (condition.equals("occupied")) { parked.currentStation = target.id; target.reserveFor(parked); }
            f.train.speed = direction * .2;
            TrainDrivingTests.control(f, player, List.of(4));
            boolean reachable = condition.equals("reachable");
            h.assertTrue((f.train.navigation.destination == target) == reachable,
                "Space only selects an available, correctly facing station within range and full stopping distance: " + condition);
            if (!reachable) {
                h.assertTrue(TrainPhysics.state(f.train).brake, "An unavailable station falls back to service braking");
                f.train.tick(h.getLevel());
                h.assertTrue(f.train.speed * direction >= 0 && Math.abs(f.train.speed) < .2,
                    "Fallback braking remains finite and cannot reverse momentum");
            }
            f.finish();
        });
    }
    private static void signal(GameTestHelper h, TrainTestFixture f, net.minecraft.world.entity.player.Player player,
        GlobalStation target, int direction) {
        var point = direction > 0 ? f.train.carriages.getFirst().getLeadingPoint() : f.train.carriages.getLast().getTrailingPoint();
        var signal = new SignalBoundary(); signal.setType(EdgePointType.SIGNAL); signal.setId(UUID.randomUUID());
        double position = point.position + direction * 4;
        signal.setLocation(direction > 0 ? Couple.create(point.node1.getLocation(), point.node2.getLocation())
            : Couple.create(point.node2.getLocation(), point.node1.getLocation()), direction > 0 ? position : point.edge.getLength() - position);
        signal.blockEntities.getFirst().put(h.absolutePos(BlockPos.ZERO), true);
        var occupied = new SignalEdgeGroup(UUID.randomUUID()); occupied.reserved = signal;
        var opposite = new SignalEdgeGroup(UUID.randomUUID());
        Create.RAILWAYS.signalEdgeGroups.put(occupied.id, occupied); Create.RAILWAYS.signalEdgeGroups.put(opposite.id, opposite);
        signal.setGroup(true, occupied.id); signal.setGroup(false, opposite.id); f.train.graph.addPoint(EdgePointType.SIGNAL, signal);
        try {
            for (int tick = 0; tick < 700; tick++) { TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel()); }
            h.assertTrue(f.train.navigation.waitingForSignal != null && f.train.currentStation == null
                && !TrainPhysics.state(f.train).parkingBrake && (point.position - position) * direction <= 1e-6 && Math.abs(f.train.speed) < .01,
                "Held station assistance waits before an occupied signal in either direction");
            occupied.reserved = null;
            signal.blockEntities.getFirst().replaceAll((pos, powered) -> false);
            for (int tick = 0; tick < 3000 && f.train.currentStation == null; tick++) {
                TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
            }
            h.assertTrue(f.train.getCurrentStation() == target && f.train.speed == 0, "Cleared signal resumes finite-force station approach");
            f.finish();
        } finally { Create.RAILWAYS.signalEdgeGroups.remove(occupied.id); Create.RAILWAYS.signalEdgeGroups.remove(opposite.id); }
    }
}
