package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import com.simibubi.create.content.trains.schedule.destination.ChangeThrottleInstruction;
import com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

/** Schedule state transitions over actual server ticks. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleStateTests {
    private TrainScheduleStateTests() {}
    private static void add(List<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        name = "train_schedule_state_" + name;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 2400, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int direction : new int[]{1, -1}) {
            for (String crew : new String[]{"front", "back", "none", "both"})
                add(cases, "crew_" + crew + "_" + direction, h -> create(h, "flat", 1, crew, s -> conductor(s, crew, direction)));
            add(cases, "crew_lost_restored_" + direction, h -> create(h, "flat", 1, "both", s -> lostCrew(s, direction)));
            for (String action : new String[]{"pause_before", "pause_wait", "pause_transit", "cancel_resume", "discard", "replace", "same_station", "instructions"})
                add(cases, action + "_" + direction, h -> create(h, "flat", 1, "both", s -> transition(s, direction, action)));
            for (int cars : new int[]{1, 2}) for (String phase : new String[]{"pre", "transit", "waiting", "completed", "unloaded"})
                add(cases, "reload_" + phase + "_" + cars + "_" + direction,
                    h -> create(h, "flat", cars, "both", s -> checkpoint(s, direction, phase)));
        }
        return cases;
    }

    private static void conductor(TrainScheduleScenario s, String crew, int direction) {
        var target = s.station(direction, 8, "Crew target");
        s.h.assertTrue(s.train().hasForwardConductor() == (crew.equals("front") || crew.equals("both"))
            && s.train().hasBackwardConductor() == (crew.equals("back") || crew.equals("both")), "assembly captures actual directional conductor qualifications");
        boolean available = direction > 0 ? s.train().hasForwardConductor() : s.train().hasBackwardConductor();
        s.train().doubleEnded = !crew.equals("front");
        s.schedule(false, entry(target, delay(5)));
        if (available) { s.arrived(target, s::succeed); return; }
        double start = s.train().carriages.getFirst().getLeadingPoint().position; int water = s.f.water();
        s.after(85, () -> {
            s.h.assertTrue(s.train().navigation.destination == null && s.train().runtime.state == State.PRE_TRANSIT
                && s.train().speed == 0 && s.f.water() == water
                && s.train().carriages.getFirst().getLeadingPoint().position == start, "missing/wrong-direction conductor cannot create a trip or traction");
            s.crew(true, true); s.train().doubleEnded = true; s.arrived(target, s::succeed);
        });
    }

    private static void lostCrew(TrainScheduleScenario s, int direction) {
        var target = s.station(direction, 12, "Crew recovery"); s.schedule(false, entry(target, delay(5)));
        s.until(() -> s.train().navigation.destination != null && Math.abs(s.train().speed) > .02, 80, "trip starts with its conductor", () -> {
            s.crew(false, false);
            s.after(60, () -> {
                s.h.assertTrue(s.train().navigation.destination == null && !s.train().runtime.completed && s.train().runtime.state == State.PRE_TRANSIT,
                    "losing the conductor interrupts transit without completing the entry");
                s.crew(true, true); s.arrived(target, s::succeed);
            });
        });
    }

    private static void transition(TrainScheduleScenario s, int direction, String action) {
        var origin = s.park(direction); var target = s.station(direction, 12, "Next");
        if (action.equals("same_station")) {
            s.schedule(false, entry(origin, delay(4)), entry(origin, delay(6)));
            int water = s.f.water();
            s.until(() -> s.train().runtime.completed, 50, "duplicate destination entries finish", () -> {
                s.h.assertTrue(s.train().speed == 0 && s.train().getCurrentStation() == origin && s.f.water() == water,
                    "consecutive entries at the current station never depart or consume steam"); s.succeed();
            }); return;
        }
        if (action.equals("instructions")) {
            var title = new ChangeTitleInstruction(); title.getData().putString("Text", "Ore service");
            var throttle = new ChangeThrottleInstruction(); throttle.getData().putInt("Value", 50);
            s.schedule(false, new ScheduleEntry(title, List.of()), new ScheduleEntry(throttle, List.of()), entry(target, delay(5)));
            s.arrived(target, () -> {
                s.h.assertTrue(s.train().runtime.currentTitle.equals("Ore service") && s.train().throttle == .5
                    && s.train().runtime.currentEntry == 2, "non-destination instructions execute once before physical transit"); s.succeed();
            }); return;
        }
        if (action.equals("pause_wait")) {
            s.schedule(false, entry(origin, delay(30)), entry(target, delay(5)));
            s.until(() -> s.train().runtime.state == State.POST_TRANSIT && !s.train().runtime.conditionContext.isEmpty()
                && s.train().runtime.conditionContext.getFirst().getInt("Time") >= 8, 30, "station wait has partial progress", () -> {
                    s.train().runtime.paused = true;
                    var saved = s.train().runtime.conditionContext.getFirst().copy();
                    s.after(40, () -> {
                        s.h.assertTrue(s.train().getCurrentStation() == origin && s.train().runtime.conditionContext.getFirst().equals(saved), "pause freezes waiting-condition progress");
                        s.train().runtime.paused = false; s.arrived(target, s::succeed);
                    });
                }); return;
        }
        s.schedule(false, entry(target, delay(10)));
        if (action.equals("pause_before")) {
            s.train().runtime.paused = true;
            s.after(40, () -> {
                s.h.assertTrue(s.train().getCurrentStation() == origin && s.train().navigation.destination == null && s.train().speed == 0,
                    "paused pre-transit schedules retain their parked train");
                s.train().runtime.paused = false; s.arrived(target, s::succeed);
            }); return;
        }
        s.until(() -> s.train().navigation.destination != null && Math.abs(s.train().speed) > .02, 80, "native trip is in transit", () -> {
            if (action.equals("discard")) {
                s.train().runtime.discardSchedule(); double speed = s.train().speed;
                s.after(1, () -> {
                    s.h.assertTrue(s.train().runtime.getSchedule() == null && s.train().navigation.destination == null
                        && Math.abs(s.train().speed - speed) < .025, "discard cancels navigation without erasing momentum");
                    s.after(30, () -> { s.h.assertTrue(s.train().runtime.getSchedule() == null && !s.train().runtime.completed, "discarded entries never restart themselves"); s.succeed(); });
                });
            } else if (action.equals("pause_transit")) {
                double position = s.train().carriages.getFirst().getLeadingPoint().position;
                s.train().runtime.paused = true;
                s.after(20, () -> {
                    s.h.assertTrue(s.train().navigation.destination == target && s.train().runtime.currentEntry == 0
                        && s.train().runtime.state == State.IN_TRANSIT
                        && direction * (s.train().carriages.getFirst().getLeadingPoint().position - position) > 0,
                        "pausing timetable advancement preserves an already active native navigation command");
                    s.train().runtime.paused = false; s.arrived(target, s::succeed);
                });
            } else if (action.equals("replace")) {
                s.train().runtime.discardSchedule(); var replacement = s.station(direction, 16, "Replacement");
                s.schedule(false, entry(replacement, delay(5))); s.arrived(replacement, () -> {
                    s.h.assertTrue(s.train().getCurrentStation() != target && s.train().runtime.currentEntry == 0, "replacement owns the destination and resets old entry progress"); s.succeed();
                });
            } else {
                s.train().navigation.cancelNavigation(); s.train().runtime.paused = true;
                s.after(30, () -> {
                    s.h.assertTrue(s.train().navigation.destination == null && s.train().runtime.state == State.PRE_TRANSIT && !s.train().runtime.completed,
                        "interrupted navigation retains its current entry for resumption");
                    s.train().runtime.paused = false; s.arrived(target, s::succeed);
                });
            }
        });
    }

    private static void checkpoint(TrainScheduleScenario s, int direction, String phase) {
        var origin = s.park(direction); var target = s.station(direction, 12, "Reload destination");
        s.schedule(false, entry(phase.equals("waiting") || phase.equals("completed") ? origin : target, delay(80)));
        Runnable save = () -> {
            var before = s.train().runtime.write(s.h.getLevel().registryAccess());
            int water = s.f.water(); double speed = s.train().speed;
            s.reload();
            s.h.assertTrue(s.train().runtime.write(s.h.getLevel().registryAccess()).equals(before)
                && s.f.water() == water && s.train().speed == speed, "native readback preserves exact schedule state, condition progress, supplies and momentum");
            if (phase.equals("completed")) {
                s.after(30, () -> { s.h.assertTrue(s.train().runtime.completed && s.train().runtime.paused && s.train().getCurrentStation() == origin,
                    "completed readback cannot replay the trip"); s.succeed(); });
            } else if (phase.equals("waiting")) {
                int remaining = 80 - s.train().runtime.conditionContext.getFirst().getInt("Time");
                s.after(Math.max(1, remaining - 2), () -> {
                    s.h.assertTrue(!s.train().runtime.completed, "saved partial delay cannot finish early after readback");
                    s.until(() -> s.train().runtime.completed, 8, "saved remaining delay finishes", s::succeed);
                });
            } else { s.train().runtime.paused = false; s.arrived(target, s::succeed); }
        };
        switch (phase) {
            case "pre" -> { s.train().runtime.paused = true; s.after(3, save); }
            case "waiting" -> s.until(() -> s.train().runtime.state == State.POST_TRANSIT && !s.train().runtime.conditionContext.isEmpty()
                && s.train().runtime.conditionContext.getFirst().getInt("Time") >= 15, 40, "partial wait is ready to save", save);
            case "completed" -> s.until(() -> s.train().runtime.completed, 100, "single-entry schedule finishes", save);
            case "unloaded" -> s.until(() -> s.train().navigation.destination != null && Math.abs(s.train().speed) > .02, 80, "trip starts", () -> {
                suspend(s.f); s.h.assertTrue(s.f.entity() == null, "carriage entities actually unload through the native save path"); save.run();
            });
            default -> s.until(() -> s.train().navigation.destination != null && Math.abs(s.train().speed) > .02, 80, "moving schedule checkpoint is reached", save);
        }
    }
}
