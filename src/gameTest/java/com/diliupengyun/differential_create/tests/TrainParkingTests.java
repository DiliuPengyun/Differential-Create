package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native cab inputs distinguish a latched Space stop from released service brakes. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainParkingTests {
    private TrainParkingTests() {}
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction("train_parking", name, "differentialcreate_trains:kineticphysicstests.physics_test", 180, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (String track : new String[]{"up", "down"}) for (boolean inverted : new boolean[]{false, true}) {
            String side = "_" + track + (inverted ? "_rear" : "_front");
            for (int gear : new int[]{0, 1, TrainMechanics.REVERSE_GEAR})
                for (String action : new String[]{"release", "reload"})
                    add(tests, "train_parking_" + action + "_" + GearLeverBlock.label(gear) + side,
                        h -> parking(h, track, inverted, gear, action));
            for (String action : new String[]{"exit", "unload", "dry", "closed", "no_engine"})
                add(tests, "train_parking_" + action + side, h -> parking(h, track, inverted, 1, action));
            add(tests, "train_parking_early_release" + side, h -> earlyRelease(h, track, inverted));
            add(tests, "train_parking_station" + side, h -> station(h, track, inverted));
        }
        for (boolean behind : new boolean[]{false, true})
            add(tests, "train_parking_schedule_" + behind, h -> schedule(h, behind));
        return tests;
    }
    private static void loaded(GameTestHelper h) {
        var chunk = new ChunkPos(h.absolutePos(TrainTestFixture.WHEEL));
        for (int x = -1; x <= 1; x++) for (int z = -3; z <= 3; z++)
            h.getLevel().setChunkForced(chunk.x + x, chunk.z + z, true);
    }
    private static double water(TrainTestFixture f) {
        return f.water() + TrainPhysics.carriage(f.train.carriages.getFirst()).water.values().stream().mapToDouble(Double::doubleValue).sum();
    }
    private static void parking(GameTestHelper h, String track, boolean inverted, int gear, String action) {
        TrainDrivingTests.create(h, track, gear, inverted, setup -> {
            loaded(setup);
            if (action.equals("no_engine")) ((com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity)
                setup.getBlockEntity(TrainTestFixture.WHEEL)).setBogeyData(new net.minecraft.nbt.CompoundTag());
        }, f -> {
            var player = TrainDrivingTests.player(f);
            var state = TrainPhysics.state(f.train);
            int uphill = track.equals("up") ? 1 : -1;
            f.train.speed = -uphill * .05;
            if (action.equals("dry")) f.train.carriages.getFirst().storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            if (action.equals("closed")) f.train.throttle = 0;
            double before = water(f);
            for (int tick = 0; tick < 20; tick++) {
                TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
            }
            h.assertTrue(f.train.speed == 0 && state.parkingBrake, "Space stops with finite force and latches parking on a real slope");
            for (int tick = 0; tick < 5; tick++) {
                TrainDrivingTests.control(f, player, List.of(0, 4)); f.train.tick(h.getLevel());
                h.assertTrue(state.parkingBrake && state.controlsBraking && state.controlsAdmission == 0 && f.train.speed == 0,
                    "Held Space takes priority over W, including a disconnected neutral input");
            }
            if (action.equals("reload")) {
                f.reload(); state = TrainPhysics.state(f.train); player = TrainDrivingTests.player(f);
                h.assertTrue(!state.stationAssist && !state.parkingRequested && state.parkingBrake,
                    "Native train saves retain deliberate parking without replaying held input or station assistance");
            } else if (action.equals("exit") || action.equals("unload")) {
                if (action.equals("exit")) f.entity().stopControlling(new BlockPos(10, 4, 5).subtract(TrainTestFixture.WHEEL));
                else { f.entity().setControllingPlayer(player.getUUID()); f.entity().remove(RemovalReason.UNLOADED_TO_CHUNK); }
                for (int tick = 0; tick < 10; tick++) f.train.tick(h.getLevel());
                h.assertTrue(state.parkingBrake && f.train.speed == 0, "Losing the driver's entity preserves deliberate parking");
                f.reload(); state = TrainPhysics.state(f.train); player = TrainDrivingTests.player(f);
            }
            double position = f.train.carriages.getFirst().getLeadingPoint().position;
            for (int tick = 0; tick < 20; tick++) {
                TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel());
                h.assertTrue(state.parkingBrake && state.controlsBraking && state.controlsAdmission == 0 && f.train.speed == 0,
                    "Released Space keeps the parking brake and its actual cab animation applied");
            }
            TrainDrivingTests.control(f, player, List.of(1)); f.train.tick(h.getLevel());
            TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel());
            h.assertTrue(state.parkingBrake && f.train.speed == 0 && f.train.carriages.getFirst().getLeadingPoint().position == position
                && Math.abs(water(f) - before) < 1e-8, "S and its release do not unlock parking, move the wheels or spend steam");
            TrainDrivingTests.control(f, player, List.of(0, 1)); f.train.tick(h.getLevel());
            h.assertTrue(state.parkingBrake && state.controlsAdmission == 0, "W cannot unlock parking while S is held");
            TrainDrivingTests.control(f, player, List.of(0)); f.train.tick(h.getLevel());
            h.assertTrue(!state.parkingBrake && !state.controlsBraking && state.controlsAdmission == f.train.throttle,
                "W alone unlocks parking immediately in forward, reverse and neutral, even without usable steam");
            TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel());
            h.assertTrue(!state.parkingBrake && !state.controlsBraking, "Releasing W cannot silently re-latch parking");
            f.finish();
        });
    }
    private static void earlyRelease(GameTestHelper h, String track, boolean inverted) {
        TrainDrivingTests.create(h, track, 1, inverted, TrainParkingTests::loaded, f -> {
            var player = TrainDrivingTests.player(f); int uphill = track.equals("up") ? 1 : -1;
            f.train.speed = -uphill * .1;
            TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
            h.assertTrue(f.train.speed * uphill < 0 && !TrainPhysics.state(f.train).parkingBrake,
                "A Space press cannot erase momentum or claim parking while the train still moves");
            TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel());
            h.assertTrue(!TrainPhysics.state(f.train).controlsBraking && !TrainPhysics.state(f.train).parkingBrake,
                "Releasing Space before stopping cancels the brake without creating a deferred parking request");
            f.finish();
        });
    }
    private static void schedule(GameTestHelper h, boolean behind) {
        TrainDrivingTests.create(h, 1, false, f -> {
            var player = TrainDrivingTests.player(f);
            TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
            h.assertTrue(TrainPhysics.state(f.train).parkingBrake, "An explicit Space stop parks before timetable takeover");
            f.train.runtime.paused = false; f.train.navigation.destinationBehindTrain = behind;
            f.train.navigation.distanceToDestination = 100;
            for (int tick = 0; tick < 20; tick++) { TrainPhysics.navigate(f.train); f.train.tick(h.getLevel()); }
            h.assertTrue(!TrainPhysics.state(f.train).parkingBrake && f.train.speed * (behind ? -1 : 1) > 0,
                "Resuming an autonomous timetable releases parking and departs with finite traction in its requested direction");
            f.finish();
        });
    }
    private static void station(GameTestHelper h, String track, boolean inverted) {
        int uphill = track.equals("up") ? 1 : -1;
        int gear = uphill == (inverted ? -1 : 1) ? 1 : TrainMechanics.REVERSE_GEAR;
        TrainDrivingTests.create(h, track, gear, inverted, TrainParkingTests::loaded, f -> {
            var player = TrainDrivingTests.player(f);
            var target = TrainStationAssistTests.station(f, uphill, 4);
            for (int tick = 0; tick < 3000 && f.train.currentStation == null; tick++) {
                TrainDrivingTests.control(f, player, List.of(4)); f.train.tick(h.getLevel());
            }
            h.assertTrue(f.train.getCurrentStation() == target && f.train.speed == 0 && TrainPhysics.state(f.train).parkingBrake,
                "Assisted arrival latches parking during actual rail travel on both slopes, before another input packet");
            f.reload(); player = TrainDrivingTests.player(f);
            double position = f.train.carriages.getFirst().getLeadingPoint().position;
            double before = water(f);
            for (int tick = 0; tick < 20; tick++) {
                TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel());
                h.assertTrue(TrainPhysics.state(f.train).parkingBrake && f.train.speed == 0
                    && f.train.getCurrentStation() == target && f.train.carriages.getFirst().getLeadingPoint().position == position,
                    "An immediately saved assisted stop retains its station and wheels after Space release on a slope: tick=" + tick
                        + " parking=" + TrainPhysics.state(f.train).parkingBrake + " speed=" + f.train.speed
                        + " station=" + f.train.currentStation + " expected=" + target.id
                        + " position=" + f.train.carriages.getFirst().getLeadingPoint().position + " expected=" + position
                        + " distance=" + TrainPhysics.state(f.train).distance + " rpm=" + TrainPhysics.state(f.train).engineRpm);
            }
            h.assertTrue(Math.abs(water(f) - before) < 1e-8, "Slope parking after assisted arrival consumes no steam");
            for (int tick = 0; tick < 20; tick++) {
                TrainDrivingTests.control(f, player, List.of(0)); f.train.tick(h.getLevel());
            }
            h.assertTrue(!TrainPhysics.state(f.train).parkingBrake && f.train.currentStation == null && f.train.speed * uphill > 0,
                "W releases saved station parking and finite traction climbs the slope");
            f.finish();
        });
    }
}
