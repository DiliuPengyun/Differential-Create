package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.*;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual cab packets, signed shaft work and native rail travel on both slopes. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainRollbackTests {
    private TrainRollbackTests() {}
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction("train_rollback", name, "differentialcreate_trains:kineticphysicstests.physics_test", 180, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (String track : new String[]{"up", "down"}) for (boolean inverted : new boolean[]{false, true}) {
            String side = "_" + track + (inverted ? "_rear" : "_front");
            for (String mode : new String[]{"strong", "weak", "closed", "dry", "no_heat", "neutral", "condenser"})
                add(tests, "train_rollback_" + mode + side, h -> rollback(h, track, inverted, mode));
            for (int gear : new int[]{0, 1, TrainMechanics.REVERSE_GEAR})
                add(tests, "train_brake_release_" + GearLeverBlock.label(gear) + side,
                    h -> release(h, track, inverted, gear));
        }
        return tests;
    }
    private static void loaded(GameTestHelper h) {
        var chunk = new ChunkPos(h.absolutePos(TrainTestFixture.WHEEL));
        for (int x = -1; x <= 1; x++) for (int z = -3; z <= 3; z++)
            h.getLevel().setChunkForced(chunk.x + x, chunk.z + z, true);
    }
    private static void initialMomentum(TrainTestFixture f, int uphill, int gear) {
        TrainPhysics.shift(f.train, gear);
        f.train.speed = -uphill * .02;
        var steam = TrainPhysics.carriage(f.train.carriages.getFirst());
        for (var drive : steam.layout.drives) {
            var engine = steam.engines.get(drive.motor().pos());
            engine.ratio = drive.ratio() * TrainMechanics.GEARS[gear];
            if (engine.ratio != 0)
                engine.omega = f.train.speed * 20 / (TrainMechanics.WHEEL_RADIUS * engine.ratio);
        }
    }
    private static double water(TrainTestFixture f) {
        return f.water() + TrainPhysics.carriage(f.train.carriages.getFirst()).water.values().stream().mapToDouble(Double::doubleValue).sum();
    }
    private static void rollback(GameTestHelper h, String track, boolean inverted, String mode) {
        int uphill = track.equals("up") ? 1 : -1;
        int gear = mode.equals("neutral") ? 0 : uphill == (inverted ? -1 : 1) ? 1 : TrainMechanics.REVERSE_GEAR;
        TrainDrivingTests.create(h, track, gear, inverted, setup -> {
            loaded(setup);
            if (mode.equals("no_heat")) setup.setBlock(TrainTestFixture.BOILER.below(), Blocks.AIR);
            if (mode.equals("condenser"))
                SteamBogey.installCondenser((com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity) setup.getBlockEntity(TrainTestFixture.WHEEL));
        }, f -> {
            var player = TrainDrivingTests.player(f);
            var train = f.train; var state = TrainPhysics.state(train);
            var steam = TrainPhysics.carriage(train.carriages.getFirst());
            if (mode.equals("dry")) train.carriages.getFirst().storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            train.throttle = mode.equals("closed") ? 0 : mode.equals("weak") ? .05 : 1;
            initialMomentum(f, uphill, gear);
            double before = water(f), remaining = before;
            boolean absorbed = false, rollingAdmission = false;
            for (int tick = 0; tick < 100; tick++) {
                double speed = train.speed;
                var rotation = new HashMap<TrainMechanics.Engine, Double>();
                steam.engines.values().forEach(engine -> rotation.put(engine, engine.omega));
                TrainDrivingTests.control(f, player, List.of(0));
                train.tick(h.getLevel());
                h.assertTrue(Math.abs(state.grade) > .1 && Math.signum(state.grade) == uphill, "Grade comes from the actual sloped track");
                h.assertTrue(!state.controlsBraking && state.controlsAdmission == train.throttle,
                    "Manual rollback keeps regulator admission and never requests a service brake");
                h.assertTrue(Double.isFinite(train.speed), "Reverse work and crossing zero remain finite");
                double now = water(f);
                h.assertTrue(now <= remaining + 1e-8, "Absorbed shaft work never refunds boiler water");
                remaining = now;
                if (speed * uphill < 0 && state.controlsAdmission > 0) rollingAdmission = true;
                for (var engine : steam.engines.values())
                    if (engine.stallTorque * rotation.get(engine) < -1e-6) absorbed = true;
                // The maneuver is complete once uphill travel is established;
                // continuing to power backwards would eventually hit the track end.
                if (tick >= 19 && (mode.equals("strong") || mode.equals("condenser")) && train.speed * uphill > 0) break;
            }
            boolean supplied = !mode.equals("closed") && !mode.equals("dry") && !mode.equals("no_heat");
            h.assertTrue(supplied ? remaining < before : Math.abs(remaining - before) < 1e-8,
                "Admitted reverse work pays real steam; closed or unavailable steam pays none: " + mode);
            if (mode.equals("strong") || mode.equals("condenser")) {
                h.assertTrue(absorbed && rollingAdmission, "Opposing manual admission absorbs actual reverse shaft work");
                h.assertTrue(train.speed * uphill > 0,
                    "Sufficient finite torque starts uphill without a service brake: speed=" + train.speed);
            } else {
                h.assertTrue(train.speed * uphill < -.02, "Insufficient or disconnected traction cannot stop gravity-driven rollback: " + mode);
                if (mode.equals("weak")) h.assertTrue(absorbed && rollingAdmission, "Weak steam still admits and absorbs work while gravity wins");
                if (mode.equals("neutral")) h.assertTrue(state.engineRpm > 0, "Neutral can rev the input while the carriage rolls downhill");
            }
            f.finish();
        });
    }
    private static void release(GameTestHelper h, String track, boolean inverted, int gear) {
        int uphill = track.equals("up") ? 1 : -1;
        TrainDrivingTests.create(h, track, gear, inverted, TrainRollbackTests::loaded, f -> {
            var player = TrainDrivingTests.player(f);
            var state = TrainPhysics.state(f.train);
            initialMomentum(f, uphill, gear);
            double before = water(f);
            for (int tick = 0; tick < 20; tick++) {
                TrainDrivingTests.control(f, player, List.of(1)); f.train.tick(h.getLevel());
                h.assertTrue(state.controlsBraking && state.controlsAdmission == 0, "Holding S keeps the service brake applied with closed admission");
            }
            h.assertTrue(Math.abs(f.train.speed) < 1e-10, "The held service brake stops and holds the train on a real slope");
            for (int tick = 0; tick < 8; tick++) {
                TrainDrivingTests.control(f, player, List.of()); f.train.tick(h.getLevel());
                h.assertTrue(!state.controlsBraking && state.controlsAdmission == 0 && f.train.speed * uphill < -1e-6,
                    "Releasing S immediately releases stationary hold and permits natural downhill motion in every gear");
            }
            for (int tick = 0; tick < 20; tick++) {
                TrainDrivingTests.control(f, player, List.of(1)); f.train.tick(h.getLevel());
            }
            h.assertTrue(Math.abs(f.train.speed) < 1e-10 && Math.abs(water(f) - before) < 1e-8,
                "Reapplying the service brake stops rollback without consuming steam");
            f.finish();
        });
    }
}
