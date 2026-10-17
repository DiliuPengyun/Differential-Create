package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Paired native assemblies and one actual railway-manager tick per sample.
 * Both trials have identical blocks, cargo, initial motion and rotor speed.
 * Only heater states or installed engine NBT change between the two trials. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainQuantitativeTests {
    private static final double DT = .05;
    private record Condition(int heat, int engines) {
        double level() { return heat == 0 ? .125 : heat; }
    }
    private record Pair(String name, Condition a, Condition b) {}
    private record Trial(double acceleration, double expectedAcceleration, double distance, double expectedDistance,
                         double mass, double capacity) {}

    private TrainQuantitativeTests() {}

    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        var pairs = new Pair[]{
            new Pair("level_1_to_2", new Condition(1, 2), new Condition(2, 2)),
            new Pair("passive_to_level_1", new Condition(0, 2), new Condition(1, 2)),
            new Pair("level_2_to_4_saturated", new Condition(2, 2), new Condition(4, 2)),
            new Pair("engines_1_to_2_sufficient", new Condition(2, 1), new Condition(2, 2)),
            new Pair("engines_1_to_2_shared", new Condition(1, 1), new Condition(1, 2))
        };
        for (boolean large : new boolean[]{false, true}) for (String track : new String[]{"flat", "up", "down"})
            for (int direction : new int[]{1, -1}) for (String mode : new String[]{"start", "power", "coast", "brake"})
                for (var pair : pairs) {
                    String name = "train_quantitative_" + pair.name + "_" + mode + "_" + track
                        + (large ? "_large_" : "_small_") + direction;
                    if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
                        cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test",
                            160, 0, true, h -> trial(h, large, track, direction, mode, pair.a, a ->
                                trial(h, large, track, direction, mode, pair.b, b -> {
                                    near(h, a.mass, b.mass, 0, "paired trials have exactly equal total mass");
                                    near(h, a.capacity, b.capacity, 0, "paired engines have equal nominal capacity");
                                    double factor = b.expectedAcceleration / a.expectedAcceleration;
                                    near(h, factor * a.acceleration, b.acceleration,
                                        4e-5 * (1 + Math.abs(factor)) + .004 * Math.abs(b.expectedAcceleration),
                                        "B acceleration must be " + factor + " times A, including resistance and rotor inertia");
                                    near(h, b.expectedDistance / a.expectedDistance * a.distance, b.distance,
                                        4e-5 + .0004 * Math.abs(b.expectedDistance), "paired travel distances have the reference ratio");
                                    h.succeed();
                                }))));
                }
        return cases;
    }

    private static void trial(GameTestHelper h, boolean large, String track, int direction, String mode,
                              Condition condition, Consumer<Trial> completed) {
        TrainTestFixture.create(h, track, large, false, true, setup -> {
            // Always use the same 16-tank boiler, four burners and two bogeys:
            // changing heat or engine NBT must not silently change mass/drag.
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                for (int y = 2; y < 4; y++) setup.setBlock(TrainTestFixture.BOILER.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
                var pos = TrainTestFixture.BOILER.offset(x, -1, z);
                setup.setBlock(pos, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL,
                    x * 2 + z < condition.heat ? HeatLevel.KINDLED : HeatLevel.SMOULDERING));
                ((BlazeBurnerBlockEntity) setup.getBlockEntity(pos)).isCreative = true;
            }
            if (condition.engines == 2) SteamBogey.install(setup.getBlockEntity(TrainTestFixture.WHEEL.south(6)));
        }, f -> sample(f, track, direction, mode, condition, completed));
    }

    private static void sample(TrainTestFixture f, String track, int direction, String mode,
                               Condition condition, Consumer<Trial> completed) {
        var h = f.h; var train = f.train; var carriage = train.carriages.getFirst();
        var steam = TrainPhysics.carriage(carriage); var blocks = f.entity().getContraption().getBlocks();
        h.assertTrue(steam.layout.drives.size() == condition.engines && carriage.isOnTwoBogeys(), "actual assembly contains the requested engine count");
        h.assertTrue(steam.layout.boilers.values().iterator().next().size() == 16, "boiler geometry supports four heat levels");
        double mass = blocks.size() * 250.0;
        var projection = new HashSet<Long>();
        blocks.keySet().forEach(p -> projection.add((long) p.getX() << 32 | p.getY() & 0xffffffffL));
        double drag = .55 * projection.size();
        double capacity = steam.layout.motors.getFirst().capacity();
        steam.layout.motors.forEach(m -> near(h, capacity, m.capacity(), 0, "every installed engine has the same capacity"));
        boolean powered = mode.equals("start") || mode.equals("power");
        int gear = direction < 0 ? 6 : mode.equals("start") ? 1 : 2;
        double radius = direction < 0 ? -.25 : mode.equals("start") ? .25 : .5;
        double initial = radius * (mode.equals("start") ? 1 : 384) * Math.PI / 30;
        TrainPhysics.shift(train, gear); train.throttle = powered ? 1 : 0; train.speed = initial / 20;
        steam.driveRotors.clear();
        steam.engines.values().forEach(e -> { e.ratio = radius / .5; e.omega = initial / radius; e.shiftTime = 0; });
        double torque = powered ? capacity * 16 : 0;
        double power = torque * 256 * Math.PI / 30 * Math.min(condition.engines, condition.level());
        double grade = track.equals("flat") ? 0 : (track.equals("up") ? 1 : -1) / Math.sqrt(65);
        var setup = new TrainDynamicsOracle.Setup(mass, drag, grade, condition.engines, radius, torque, power, mode.equals("brake") ? 1 : 0);
        var expected = TrainDynamicsOracle.advance(setup, initial, DT);
        double expectedAcceleration = direction * (expected.speed() - initial) / DT;
        double position = carriage.getLeadingPoint().position;
        int beforeTicks = TrainPhysics.state(train).ticks;

        class Probe implements Consumer<LevelTickEvent.Post> {
            final Consumer<LevelTickEvent.Post> command = event -> {
                if (event.getLevel() == h.getLevel()) TrainPhysics.manual(train, direction, mode.equals("brake"));
            };
            boolean captured;
            double speed, distance, actualMass, actualGrade, actualPower, fraction;
            int ticks, heat;
            long worldTick;

            @Override public void accept(LevelTickEvent.Post event) {
                if (event.getLevel() != h.getLevel()) return;
                close(); captured = true; worldTick = h.getLevel().getGameTime();
                speed = train.speed * 20; distance = carriage.getLeadingPoint().position - position;
                var state = TrainPhysics.state(train); ticks = state.ticks; actualMass = state.mass; actualGrade = state.grade;
                actualPower = steam.engines.values().stream().mapToDouble(e -> e.power).sum();
                var boiler = steam.boilers.values().iterator().next(); heat = boiler.activeHeat; fraction = BoilerAdmission.fraction(boiler);
            }
            void close() { NeoForge.EVENT_BUS.unregister(command); NeoForge.EVENT_BUS.unregister(this); }
        }
        var probe = new Probe();
        long requestedTick = h.getLevel().getGameTime() + 1;
        // Create advances railways at NORMAL LevelTickEvent.Post priority.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, LevelTickEvent.Post.class, probe.command);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LevelTickEvent.Post.class, probe);
        h.runAfterDelay(1, () -> {
            try {
                h.assertTrue(probe.captured && probe.worldTick == requestedTick && probe.ticks == beforeTicks + 1,
                    "sample observes one complete real server/railway tick");
                near(h, mass, probe.actualMass, 0, "native train mass agrees with captured geometry");
                near(h, grade, probe.actualGrade, 1e-10, "slope agrees with the fixture's 1:8 geometry");
                near(h, condition.heat, probe.heat, 0, "native burners produce the requested boiler heat");
                near(h, power, probe.actualPower, 1e-7, "available total power equals rated power times min(level, engine count)");
                if (powered) near(h, 1, probe.fraction, 1e-10, "finite native water inventory fully pays the measured tick");
                double acceleration = direction * (probe.speed - initial) / DT;
                // The reference solves the rigid constraint; the production
                // solver splits finite clutch impulses into 64 substeps/tick.
                near(h, expectedAcceleration, acceleration, 2e-5 + .002 * Math.abs(expectedAcceleration),
                    "actual acceleration agrees with the independent force/inertia reference");
                near(h, direction * expected.distance(), direction * probe.distance,
                    2e-5 + .0002 * Math.abs(expected.distance()), "actual native rail displacement agrees with the reference");
                f.close();
                completed.accept(new Trial(acceleration, expectedAcceleration, direction * probe.distance,
                    direction * expected.distance(), mass, capacity));
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
            finally { probe.close(); }
        });
    }

    private static void near(GameTestHelper h, double expected, double actual, double tolerance, String message) {
        h.assertTrue(Double.isFinite(expected) && Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            message + ": expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
    }
}
