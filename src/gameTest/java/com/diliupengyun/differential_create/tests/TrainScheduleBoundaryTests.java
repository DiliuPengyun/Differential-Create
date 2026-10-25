package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

/** Adjacent values and equality at controller/listener branch boundaries.
 * Listener tests invoke the actual mixed native callback at an exact velocity;
 * navigation cancellation tests the command directly; admission and creep
 * tests sample one ordinary railway-manager frame. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleBoundaryTests {
    private TrainScheduleBoundaryTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int cars : new int[]{1, 2}) for (int direction : new int[]{1, -1})
            for (String mode : new String[]{"creep_speed", "remaining", "admission", "negative_distance", "station_arrival", "signal_arrival"})
                for (int side : new int[]{-1, 0, 1}) {
                    String name = "train_schedule_boundary_" + mode + "_" + side + "_" + cars + "_" + direction;
                    if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
                        cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 1500, 0, true,
                            h -> create(h, "flat", cars, "both", s -> boundary(s, direction, mode, side))));
                }
        return cases;
    }
    private static void boundary(TrainScheduleScenario s, int direction, String mode, int side) {
        var target = s.station(direction, mode.equals("admission") ? 2 : mode.equals("creep_speed") || mode.equals("remaining") ? .05 : 10, "Boundary target");
        s.schedule(false, entry(target, delay(30)));
        s.until(() -> s.train().navigation.destination == target, 10, "native schedule supplies the boundary test's navigation", () -> {
            var train = s.train(); var state = TrainPhysics.state(train); var nav = train.navigation;
            if (mode.endsWith("arrival")) {
                double velocity = direction * (.15 + side * 1e-6) / 20;
                train.speed = velocity;
                var point = TrainScheduleSignalTests.front(train, direction);
                var nodes = direction > 0 ? Couple.create(point.node1, point.node2) : Couple.create(point.node2, point.node1);
                boolean stopped;
                if (mode.equals("station_arrival")) {
                    stopped = train.frontSignalListener().test(0d, Pair.of(target, nodes));
                    s.h.assertTrue(side > 0 ? train.getCurrentStation() == null && nav.destination == null && train.runtime.state == State.PRE_TRANSIT
                        : train.getCurrentStation() == target && train.runtime.state == State.POST_TRANSIT,
                        "station callback parks only within the inclusive 0.15 m/s tolerance");
                } else {
                    var net = new TrainScheduleNetwork(s); var before = net.group(); var after = net.group();
                    var signal = net.signal(nodes.getFirst(), nodes.getSecond(), target.position - 2, before, after, false, true);
                    nav.waitingForSignal = Pair.of(signal.id, true);
                    stopped = train.frontSignalListener().test(0d, Pair.of(signal, nodes));
                    s.h.assertTrue(side > 0 ? train.occupiedSignalBlocks.containsKey(after.id) && nav.waitingForSignal == null
                        : !train.occupiedSignalBlocks.containsKey(after.id) && nav.waitingForSignal != null,
                        "a signal overrun occupies the crossed group; an admissible stop does not cross it");
                }
                s.h.assertTrue(side > 0 ? !stopped && train.speed == velocity && state.emergencyStop
                    : stopped && train.speed == 0 && !state.emergencyStop,
                    "both listener types preserve high-speed momentum and request emergency braking at the exact boundary");
                s.succeed(); return;
            }
            if (mode.equals("negative_distance")) {
                nav.distanceToDestination = -10 + side * 1e-6;
                TrainPhysics.navigate(train);
                s.h.assertTrue(side < 0 ? nav.destination == null && state.brake && state.direction == 0
                    && train.runtime.state == State.PRE_TRANSIT : nav.destination == target && !state.brake,
                    "only distances strictly below minus ten metres interrupt transit");
                s.succeed(); return;
            }
            double distance = mode.equals("remaining") ? 1e-6 + side * 1e-9 : mode.equals("admission") ? 2 + side * 1e-6 : .05;
            nav.distanceToDestination = distance;
            train.speed = mode.equals("creep_speed") ? direction * (.1 + side * 1e-6) / 20 : 0;
            s.after(1, () -> {
                boolean brake = mode.equals("remaining") ? side <= 0 : mode.equals("creep_speed") && side >= 0;
                s.h.assertTrue(state.controlsBraking == brake, "creep requires speed strictly below 0.1 m/s and distance strictly above 1e-6 m");
                double expected = brake ? 0 : Math.min(1, distance / 2);
                s.h.assertTrue(Math.abs(state.controlsAdmission - expected) < 1e-12,
                    "flat-track admission is distance / 2 below two metres and full admission at or above it: actual=" + state.controlsAdmission + " expected=" + expected);
                s.succeed();
            });
        });
    }
}
