package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import com.diliupengyun.differential_create.trains.MountedBurner;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

/** Resource loss and resupply while an actual timetable remains in control. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleResourceTests {
    private TrainScheduleResourceTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int cars : new int[]{1, 2}) for (int direction : new int[]{1, -1})
            for (String mode : new String[]{"pre_water", "pre_valve", "pre_heat", "pre_engine", "mid_water", "mid_valve", "fuel_expiry"}) {
                String name = "train_schedule_resource_" + mode + "_" + cars + "_" + direction;
                if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
                    cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 2600, 0, true,
                        h -> create(h, "flat", cars, "both", setup -> {
                            if (mode.equals("pre_engine")) ((AbstractBogeyBlockEntity) setup.getBlockEntity(TrainTestFixture.WHEEL)).setBogeyData(new CompoundTag());
                        }, s -> resource(s, direction, mode))));
            }
        return cases;
    }

    private static void water(TrainScheduleScenario s, int amount) {
        for (var c : s.train().carriages) {
            c.storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            TrainPhysics.carriage(c).water.replaceAll((pos, old) -> 0d);
        }
        if (amount > 0) s.train().carriages.getFirst().storage.getFluids().fill(new FluidStack(Fluids.WATER, amount), FluidAction.EXECUTE);
    }
    private static int water(TrainScheduleScenario s) {
        int total = 0;
        for (var c : s.train().carriages) for (int i = 0; i < c.storage.getFluids().getTanks(); i++) total += c.storage.getFluids().getFluidInTank(i).getAmount();
        return total;
    }
    private static void resource(TrainScheduleScenario s, int direction, String mode) {
        var origin = s.park(direction); var target = s.station(direction, 60, "Resupplied destination");
        var restoreHeat = new ArrayList<Runnable>();
        if (mode.equals("pre_water") || mode.equals("mid_water")) water(s, mode.startsWith("mid") ? 5 : 0);
        if (mode.equals("pre_valve")) s.train().throttle = 0;
        if (mode.equals("pre_heat")) for (var c : s.train().carriages) {
            var burners = TrainPhysics.carriage(c).burners; var original = new LinkedHashMap<>(burners);
            burners.replaceAll((pos, old) -> new MountedBurner(new CompoundTag(), HeatLevel.NONE));
            restoreHeat.add(() -> burners.putAll(original));
        }
        if (mode.equals("fuel_expiry")) for (var c : s.train().carriages) {
            var items = c.storage.getAllItems(); for (int i = 0; i < items.getSlots(); i++) items.setStackInSlot(i, ItemStack.EMPTY);
            TrainPhysics.carriage(c).burners.values().forEach(b -> { b.creative = false; b.fuel = 1; b.remaining = 8; });
        }
        s.schedule(false, entry(target, delay(30)));
        if (mode.equals("fuel_expiry")) {
            s.after(24, () -> {
                for (var c : s.train().carriages) s.h.assertTrue(TrainPhysics.carriage(c).burners.values().stream().allMatch(b -> b.fuel == 0)
                    && TrainPhysics.carriage(c).boilers.values().stream().allMatch(b -> b.activeHeat == 0 && b.passiveHeat),
                    "native fuel expiry retains only passive smouldering heat");
                s.h.assertTrue(s.train().speed * direction > 0 && s.train().navigation.destination == target && s.train().runtime.currentEntry == 0,
                    "passive steam remains valid traction without replaying the schedule");
                for (var c : s.train().carriages) c.storage.getAllItems().insertItem(0, new ItemStack(Items.COAL), false);
                s.after(4, () -> {
                    for (var c : s.train().carriages) s.h.assertTrue(c.storage.getAllItems().getStackInSlot(0).isEmpty()
                        && TrainPhysics.carriage(c).burners.values().stream().allMatch(b -> b.fuel > 0 && b.remaining > 0),
                        "scheduled traction takes each replenished coal exactly once");
                    s.arrived(target, s::succeed);
                });
            }); return;
        }
        if (mode.startsWith("mid")) {
            s.until(() -> s.train().getCurrentStation() == null && s.train().speed * direction > .005, 80, "scheduled train actually departs before resource loss", () -> {
                double momentum = s.train().speed;
                if (mode.equals("mid_valve")) s.train().throttle = 0;
                s.until(() -> mode.equals("mid_valve") || water(s) == 0, 800,
                    "scheduled traction exhausts its finite reserve", () -> {
                        int stockAfterLoss = water(s);
                        s.after(40, () -> {
                            s.h.assertTrue(Math.abs(momentum) > 0 && s.train().speed * direction > 0 && Double.isFinite(s.train().speed)
                                && water(s) == stockAfterLoss && s.train().getCurrentStation() == null
                                && s.train().navigation.destination == target && s.train().runtime.state == State.IN_TRANSIT && !s.train().runtime.completed,
                                "resource loss retains finite coasting momentum and the pending timetable without taking unavailable supplies");
                            for (var c : s.train().carriages) s.h.assertTrue(TrainPhysics.carriage(c).engines.values().stream().allMatch(e -> e.power == 0),
                                "an exhausted reserve or closed admission supplies no cylinder power");
                            if (mode.equals("mid_water")) water(s, 4000); else s.train().throttle = 1;
                            s.arrived(target, s::succeed);
                        });
                    });
            }); return;
        }
        int stock = water(s); double position = s.train().carriages.getFirst().getLeadingPoint().position;
        s.after(85, () -> {
            s.h.assertTrue(s.train().getCurrentStation() == origin && origin.getPresentTrain() == s.train() && s.train().speed == 0
                && s.train().carriages.getFirst().getLeadingPoint().position == position && water(s) == stock
                && !s.train().runtime.completed && s.train().runtime.currentEntry == 0,
                "unavailable traction preserves the departure station, inventory and pending timetable: " + mode);
            if (mode.equals("pre_engine")) { s.succeed(); return; }
            if (mode.equals("pre_water")) water(s, 4000);
            if (mode.equals("pre_valve")) s.train().throttle = 1;
            restoreHeat.forEach(Runnable::run);
            s.arrived(target, s::succeed);
        });
    }
}
