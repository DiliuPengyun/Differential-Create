package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.graph.EdgePointType;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

/** Independent forced-red, real occupancy, reservations, chains and tail release. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleSignalTests {
    private TrainScheduleSignalTests() {}
    private static void add(List<TestFunction> cases, String name, int cars, int direction, Consumer<TrainScheduleScenario> test) {
        name = "train_schedule_signal_" + name + "_" + cars + "_" + direction;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 2600, 0, true,
                h -> create(h, "flat", cars, "both", test)));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int direction : new int[]{1, -1}) {
            for (int cars : new int[]{1, 2}) for (String mode : new String[]{"forced", "occupied", "reserved", "chain", "reload", "removed"})
                add(cases, mode, cars, direction, s -> signal(s, direction, mode));
            add(cases, "tail_release", 2, direction, s -> tail(s, direction));
        }
        return cases;
    }
    static TravellingPoint front(Train train, int direction) {
        return direction > 0 ? train.carriages.getFirst().getLeadingPoint() : train.carriages.getLast().getTrailingPoint();
    }
    static TravellingPoint rear(Train train, int direction) { return front(train, -direction); }
    static double coordinate(TravellingPoint p, int direction) { return direction > 0 ? p.position : p.edge.getLength() - p.position; }

    private static void signal(TrainScheduleScenario s, int direction, String mode) {
        var net = new TrainScheduleNetwork(s); var point = front(s.train(), direction);
        var from = direction > 0 ? point.node1 : point.node2; var to = direction > 0 ? point.node2 : point.node1;
        double start = coordinate(point, direction);
        var before = net.group(); var middle = net.group(); var occupied = mode.equals("chain") ? net.group() : middle;
        var after = net.group();
        boolean forced = mode.equals("forced") || mode.equals("reload") || mode.equals("removed");
        var first = net.signal(from, to, start + 6, before, middle, mode.equals("chain"), forced);
        if (mode.equals("chain")) net.signal(from, to, start + 12, middle, occupied, false, false);
        net.signal(from, to, start + 30, occupied, after, false, false);
        var target = net.station(from, to, start + 24, "Following train destination");
        Runnable begin = () -> {
            s.schedule(false, entry(target, delay(30)));
            s.until(() -> s.train().navigation.waitingForSignal != null, 80, "native scout detects a blocking signal", () ->
                s.until(() -> Math.abs(s.train().speed) < .01 && s.train().navigation.distanceToSignal < .1,
                    1000, "train physically waits at the signal", () -> {
                        s.h.assertTrue(first.id.equals(s.train().navigation.waitingForSignal.getFirst())
                            && coordinate(front(s.train(), direction), direction) <= start + 6 + 1e-6,
                            "entry and cross signals stop the train before the first blocked boundary");
                        if (mode.equals("reload")) {
                            var checkpoint = s.train().runtime.write(s.h.getLevel().registryAccess()); int water = s.f.water();
                            s.reload();
                            s.h.assertTrue(s.train().navigation.waitingForSignal != null
                                && first.id.equals(s.train().navigation.waitingForSignal.getFirst())
                                && checkpoint.equals(s.train().runtime.write(s.h.getLevel().registryAccess())) && s.f.water() == water,
                                "waiting-signal readback preserves navigation, runtime and paid inventory");
                        }
                        if (mode.equals("removed")) s.train().graph.removePoint(EdgePointType.SIGNAL, first.id);
                        else if (forced) first.blockEntities.getFirst().replaceAll((pos, power) -> false);
                        if (forced) s.arrived(target, s::succeed);
                    }));
        };
        if (forced) { begin.run(); return; }
        s.second(direction, mode.equals("reserved") ? -24 : 20, blocker -> {
            var goal = net.station(from, to, start + 45, "Leading train destination"); net.seal();
            blocker.train.updateSignalBlocks = true;
            s.after(1, () -> {
                if (mode.equals("reserved")) blocker.train.reservedSignalBlocks.add(occupied.id);
                begin.run();
                s.until(() -> s.train().navigation.waitingForSignal != null && Math.abs(s.train().speed) < .01
                    && s.train().navigation.distanceToSignal < .1, 1100, "following train is stopped by its real blocker", () -> {
                    s.h.assertTrue(occupied.trains.contains(blocker.train) && !first.isForcedRed(true),
                        "a real train's occupancy/reservation blocks without forced red");
                    if (mode.equals("reserved")) blocker.train.reservedSignalBlocks.remove(occupied.id);
                    else {
                        var schedule = new Schedule(); schedule.cyclic = false; schedule.entries.add(entry(goal, delay(30)));
                        blocker.train.runtime.setSchedule(schedule, false);
                    }
                    s.arrived(target, () -> {
                        s.h.assertTrue(!occupied.trains.contains(blocker.train), "the leading train's release enables following transit"); s.succeed();
                    });
                });
            });
        });
    }

    private static void tail(TrainScheduleScenario s, int direction) {
        var net = new TrainScheduleNetwork(s); var point = front(s.train(), direction);
        var from = direction > 0 ? point.node1 : point.node2; var to = direction > 0 ? point.node2 : point.node1;
        double start = coordinate(point, direction), exit = start + 8;
        var before = net.group(); var block = net.group(); var after = net.group();
        net.signal(from, to, start - 12, before, block, false, false); net.signal(from, to, exit, block, after, false, false);
        // The destinations need distinct exit blocks too: a parked leader
        // correctly keeps its own final block occupied.
        net.signal(from, to, start + 26, after, net.group(), false, false);
        var leadTarget = net.station(from, to, start + 40, "Long leading train");
        var followTarget = net.station(from, to, start + 24, "Waiting follower");
        s.second(direction, -28, follower -> {
            net.seal(); follower.train.updateSignalBlocks = true;
            var schedule = new Schedule(); schedule.cyclic = false; schedule.entries.add(entry(followTarget, delay(30)));
            follower.train.runtime.setSchedule(schedule, false);
            s.until(() -> follower.train.navigation.waitingForSignal != null && Math.abs(follower.train.speed) < .01
                && follower.train.navigation.distanceToSignal < .1, 1100, "follower waits for a multi-carriage block occupant", () -> {
                s.h.assertTrue(block.trains.contains(s.train()), "the actual long train occupies the block");
                s.schedule(false, entry(leadTarget, delay(30)));
                s.until(() -> coordinate(front(s.train(), direction), direction) > exit, 400, "leading bogey crosses the exit", () -> {
                    s.h.assertTrue(coordinate(rear(s.train(), direction), direction) < exit && block.trains.contains(s.train())
                        && follower.train.navigation.waitingForSignal != null,
                        "front exit alone cannot release a block still occupied by trailing carriages");
                    s.until(() -> !block.trains.contains(s.train()), 400, "last carriage releases the occupied block", () -> {
                        s.h.assertTrue(coordinate(rear(s.train(), direction), direction) >= exit,
                            "block release occurs only after the real trailing bogey clears its boundary");
                        s.until(() -> follower.train.getCurrentStation() == followTarget && follower.train.speed == 0,
                            1000, "follower resumes and arrives after tail release", s::succeed);
                    });
                });
            });
        });
    }
}
