package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import net.createmod.catnip.data.Couple;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleRouteTests {
    private TrainScheduleRouteTests() {}
    private static void add(List<TestFunction> cases, String name, int cars, int direction, Consumer<TrainScheduleScenario> test) {
        name = "train_schedule_route_" + name + "_" + cars + "_" + direction;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 2400, 0, true,
                h -> create(h, "flat", cars, "both", test)));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int cars : new int[]{1, 2}) for (int direction : new int[]{1, -1}) {
            for (String mode : new String[]{"missing", "rename", "wrong_face", "wildcard", "removed", "unreachable", "broken_link"})
                add(cases, mode, cars, direction, s -> route(s, direction, mode));
            for (String mode : new String[]{"shorter", "blocked", "loop"}) add(cases, "fork_" + mode, cars, direction, s -> fork(s, direction, mode));
        }
        return cases;
    }

    private static void route(TrainScheduleScenario s, int direction, String mode) {
        if (mode.equals("unreachable") || mode.equals("broken_link")) { disconnected(s, direction, mode.equals("broken_link")); return; }
        var target = s.station(direction, 12, mode.equals("rename") ? "Old name" : "Ore_A");
        if (mode.equals("wildcard")) {
            s.station(direction, 20, "Ore_B"); s.station(direction, 6, "Unmatched");
            s.schedule(false, entry("Ore_*", delay(20))); s.arrived(target, s::succeed); return;
        }
        if (mode.equals("wrong_face")) {
            var location = target.edgeLocation; double length = s.train().carriages.getFirst().getLeadingPoint().edge.getLength();
            s.train().graph.removePoint(EdgePointType.STATION, target.id);
            target.setLocation(Couple.create(location.getSecond(), location.getFirst()), length - target.position);
            s.train().graph.addPoint(EdgePointType.STATION, target);
        }
        s.schedule(false, entry(mode.equals("missing") || mode.equals("rename") ? "Wanted" : target.name, delay(20)));
        if (mode.equals("removed")) {
            s.until(() -> s.train().navigation.destination == target && Math.abs(s.train().speed) > .02, 80, "trip starts before its target is removed", () -> {
                s.train().graph.removePoint(EdgePointType.STATION, target.id);
                s.after(100, () -> {
                    s.h.assertTrue(!s.train().runtime.completed && s.train().getCurrentStation() != target, "removing a target cannot create a phantom arrival");
                    s.until(() -> s.train().navigation.destination == null && s.train().speed == 0, 800, "removed target eventually interrupts transit", () -> {
                        var replacement = s.station(direction, 10, "Ore_A"); s.arrived(replacement, s::succeed);
                    });
                });
            }); return;
        }
        int water = s.f.water(); double position = s.train().carriages.getFirst().getLeadingPoint().position;
        s.after(85, () -> {
            s.h.assertTrue(s.train().runtime.state == State.PRE_TRANSIT && s.train().navigation.destination == null
                && s.train().speed == 0 && s.f.water() == water && s.train().carriages.getFirst().getLeadingPoint().position == position,
                "unmatched or wrong-facing destinations fail safely across cooldown retries");
            if (mode.equals("wrong_face")) {
                s.train().graph.removePoint(EdgePointType.STATION, target.id);
                var restored = s.station(direction, 12, "Ore_A"); s.arrived(restored, s::succeed);
            } else { target.name = "Wanted"; s.arrived(target, s::succeed); }
        });
    }

    private static void disconnected(TrainScheduleScenario s, int direction, boolean breakDuringTrip) {
        var net = new TrainScheduleNetwork(s); var point = s.train().carriages.getFirst().getLeadingPoint();
        Vec3 origin = point.edge.getPosition(null, 0);
        // Keep the current points on a short native edge, with an independent
        // continuation that can be connected/disconnected without deleting them.
        var old1 = point.node1; var old2 = point.node2;
        var a = net.node(origin); var b = net.node(origin.add(0, 0, 40));
        s.train().graph.disconnectNodes(old1, old2); net.connect(a, b);
        var edge = s.train().graph.getConnection(Couple.create(a.node(), b.node()));
        s.train().forEachTravellingPoint(p -> { p.node1 = a.node(); p.node2 = b.node(); p.edge = edge; p.position -= 100; });
        for (var c : s.train().carriages) { c.updateContraptionAnchors(); c.manageEntities(s.h.getLevel()); }
        var from = direction > 0 ? b : a;
        Vec3 start = direction > 0 ? origin.add(0, 0, 40) : origin;
        var end = net.node(start.add(0, 0, direction * 20));
        var distant = net.node(start.add(0, 0, direction * 40)); net.connect(end, distant);
        var target = net.station(end.node(), distant.node(), 6, "Connected later");
        if (breakDuringTrip) net.connect(from, end);
        s.schedule(false, entry(target, delay(20)));
        Runnable repair = () -> {
            s.h.assertTrue(!s.train().runtime.completed, "unreachable targets never finish the entry"); net.connect(from, end); s.arrived(target, s::succeed);
        };
        if (!breakDuringTrip) s.after(85, () -> {
            s.h.assertTrue(s.train().navigation.destination == null && s.train().speed == 0, "matching but disconnected stations cannot start transit"); repair.run();
        });
        else s.until(() -> s.train().navigation.destination == target, 30, "path is selected before the link changes", () -> {
            s.train().graph.disconnectNodes(from.node(), end.node());
            s.until(() -> s.train().navigation.destination == null && s.train().speed == 0, 1000,
                "the broken connection interrupts actual transit before repair", () -> {
                    s.h.assertTrue(!s.train().derailed && s.train().getCurrentStation() == null && s.train().runtime.state == State.PRE_TRANSIT,
                        "a broken continuation cannot teleport, complete the entry or derail the train");
                    repair.run();
                });
        });
    }

    private static void fork(TrainScheduleScenario s, int direction, String mode) {
        var net = new TrainScheduleNetwork(s); var point = s.train().carriages.getFirst().getLeadingPoint();
        Vec3 origin = point.edge.getPosition(null, 0); var old1 = point.node1; var old2 = point.node2;
        var a = net.node(origin); var b = net.node(origin.add(0, 0, 40));
        s.train().graph.disconnectNodes(old1, old2); net.connect(a, b);
        var edge = s.train().graph.getConnection(Couple.create(a.node(), b.node()));
        s.train().forEachTravellingPoint(p -> { p.node1 = a.node(); p.node2 = b.node(); p.edge = edge; p.position -= 100; });
        for (var c : s.train().carriages) { c.updateContraptionAnchors(); c.manageEntities(s.h.getLevel()); }
        var fork = direction > 0 ? b : a; Vec3 at = direction > 0 ? origin.add(0, 0, 40) : origin;
        var left = net.node(at.add(-4, 0, direction * 20)); var right = net.node(at.add(8, 0, direction * 20));
        net.connect(fork, left); net.connect(fork, right);
        var leftEdge = s.train().graph.getConnection(Couple.create(fork.node(), left.node()));
        var rightEdge = s.train().graph.getConnection(Couple.create(fork.node(), right.node()));
        var near = net.station(fork.node(), left.node(), leftEdge.getLength() - 2, "Branch_A");
        var far = net.station(fork.node(), right.node(), rightEdge.getLength() - 2, "Branch_B");
        if (mode.equals("shorter")) far.name = near.name;
        if (mode.equals("loop")) {
            var join = net.node(at.add(0, 0, direction * 40)); net.connect(left, join); net.connect(right, join);
        }
        if (mode.equals("blocked")) net.signal(fork.node(), left.node(), 2, net.group(), net.group(), false, true);
        s.schedule(false, entry(mode.equals("shorter") ? near.name : "Branch_*", delay(20)));
        var expected = mode.equals("blocked") ? far : near;
        s.until(() -> s.train().navigation.destination != null, 30, "fork search selects a candidate", () -> {
            s.h.assertTrue(s.train().navigation.destination == expected, "native path cost selects the shorter or unblocked candidate, including a rejoining loop");
            s.arrived(expected, s::succeed);
        });
    }
}
