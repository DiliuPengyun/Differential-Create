package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.content.trains.schedule.condition.*;
import com.simibubi.create.content.trains.schedule.condition.CargoThresholdCondition.Ops;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleConditionTests {
    private TrainScheduleConditionTests() {}
    private static void add(List<TestFunction> cases, String name, int cars, int direction, Consumer<TrainScheduleScenario> test) {
        name = "train_schedule_condition_" + name + "_" + cars + "_" + direction;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 2400, 0, true,
                h -> create(h, "flat", cars, "both", setup -> {
                    setup.setBlock(new BlockPos(10, 4, 8), AllBlocks.SEATS.get(DyeColor.YELLOW).getDefaultState());
                    setup.setBlock(new BlockPos(10, 4, 9), AllBlocks.SEATS.get(DyeColor.YELLOW).getDefaultState());
                }, test)));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (int cars : new int[]{1, 2}) for (int direction : new int[]{1, -1}) {
            for (int ticks : new int[]{0, 1, 20, 40}) add(cases, "delay_" + ticks, cars, direction, s -> delayCase(s, direction, ticks, 0));
            for (int unit : new int[]{1, 2}) add(cases, "delay_unit_" + unit, cars, direction, s -> delayCase(s, direction, 1, unit));
            for (String mode : new String[]{"and", "or"}) add(cases, mode, cars, direction, s -> combination(s, direction, mode));
            for (boolean and : new boolean[]{false, true}) for (boolean first : new boolean[]{false, true}) for (boolean second : new boolean[]{false, true})
                add(cases, "logic_" + and + "_" + first + "_" + second, cars, direction, s -> logic(s, direction, and, first, second));
            for (boolean fluid : new boolean[]{false, true}) for (var op : Ops.values()) for (int offset : new int[]{-1, 0, 1})
                add(cases, (fluid ? "fluid_" : "items_") + op.name().toLowerCase() + "_" + offset,
                    cars, direction, s -> threshold(s, direction, fluid, op, offset));
            for (boolean fluid : new boolean[]{false, true}) add(cases, "wrong_filter_" + fluid, cars, direction, s -> filter(s, direction, fluid));
            for (boolean above : new boolean[]{false, true}) for (int count : new int[]{0, 1, 2})
                add(cases, "passengers_" + above + "_" + count, cars, direction, s -> passengers(s, direction, above, count));
            for (int diff : new int[]{-1, 0, 40, 41}) add(cases, "clock_" + diff, cars, direction, s -> clock(s, direction, diff));
            add(cases, "idle_cargo", cars, direction, s -> idle(s, direction));
            add(cases, "station_unloaded", cars, direction, s -> unloaded(s, direction));
            for (boolean inverted : new boolean[]{false, true}) add(cases, "wireless_" + inverted, cars, direction, s -> wireless(s, direction, inverted));
        }
        return cases;
    }

    private static void waiting(TrainScheduleScenario s, int direction, ScheduleWaitCondition condition, Runnable next) {
        var origin = s.park(direction); s.schedule(false, entry(origin, condition));
        s.until(() -> s.train().runtime.state == State.POST_TRANSIT, 10, "current-station instruction begins its wait", next);
    }
    private static void completed(TrainScheduleScenario s, int timeout) {
        s.until(() -> s.train().runtime.completed, timeout, "satisfied condition finishes its entry", () -> {
            s.h.assertTrue(s.train().speed == 0 && s.train().getCurrentStation() != null, "condition completion keeps the parked train registered"); s.succeed();
        });
    }

    private static void delayCase(TrainScheduleScenario s, int direction, int value, int unit) {
        var condition = delay(value); condition.getData().putInt("TimeUnit", unit);
        int ticks = value * (unit == 0 ? 1 : unit == 1 ? 20 : 1200);
        waiting(s, direction, condition, () -> {
            if (ticks == 0) { completed(s, 4); return; }
            s.after(ticks, () -> {
                s.h.assertTrue(!s.train().runtime.completed && s.train().runtime.currentEntry == 0
                    && s.train().runtime.conditionContext.getFirst().getInt("Time") == ticks,
                    "delay uses the exact number of real ticks for its configured unit"); completed(s, 4);
            });
        });
    }

    private static void combination(TrainScheduleScenario s, int direction, String mode) {
        var origin = s.park(direction); var entry = entry(origin);
        entry.conditions.clear();
        if (mode.equals("and")) entry.conditions.add(List.of(delay(8), new StationPoweredCondition()));
        else { entry.conditions.add(List.of(delay(80))); entry.conditions.add(List.of(delay(8), new StationPoweredCondition())); }
        s.schedule(false, entry);
        var pos = BlockPos.ZERO.below(); var previous = s.h.getBlockState(pos); s.cleanup.add(() -> s.h.setBlock(pos, previous));
        s.after(16, () -> {
            s.h.assertTrue(!s.train().runtime.completed && s.train().runtime.conditionProgress.get(mode.equals("and") ? 0 : 1) == 1,
                "the first sequential condition cannot bypass the unpowered second condition");
            s.h.setBlock(pos, Blocks.REDSTONE_BLOCK);
            s.until(() -> s.train().runtime.completed, 5, "AND group or either OR alternative completes", () -> {
                s.h.assertTrue(s.train().runtime.currentEntry == 0 && s.train().getCurrentStation() == origin,
                    "OR completion advances exactly one entry and retains parking"); s.succeed();
            });
        });
    }

    private static void logic(TrainScheduleScenario s, int direction, boolean and, boolean first, boolean second) {
        var origin = s.park(direction); var entry = entry(origin); entry.conditions.clear();
        var a = delay(first ? 0 : 80); var b = delay(second ? 0 : 80);
        if (and) entry.conditions.add(List.of(a, b)); else { entry.conditions.add(List.of(a)); entry.conditions.add(List.of(b)); }
        s.schedule(false, entry);
        s.after(8, () -> {
            boolean expected = and ? first && second : first || second;
            s.h.assertTrue(s.train().runtime.completed == expected && s.train().runtime.currentEntry == 0,
                "both truth tables include false/false, true/false, false/true and true/true without duplicate advancement");
            if (expected) { s.succeed(); return; }
            a.getData().putInt("Value", 0); b.getData().putInt("Value", 0); completed(s, 6);
        });
    }

    private static void setCargo(TrainScheduleScenario s, boolean fluid, int amount, boolean matching) {
        var storage = s.train().carriages.getFirst().storage;
        if (fluid) {
            storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            if (amount > 0) storage.getFluids().fill(new FluidStack(matching ? Fluids.WATER : Fluids.LAVA, amount), FluidAction.EXECUTE);
        } else {
            var inventory = storage.getAllItems();
            for (int i = 0; i < inventory.getSlots(); i++) inventory.setStackInSlot(i, ItemStack.EMPTY);
            if (amount > 0) inventory.setStackInSlot(0, new ItemStack(matching ? Items.DIAMOND : Items.EMERALD, amount));
        }
    }

    private static CargoThresholdCondition cargo(boolean fluid, Ops operator, int threshold) {
        CargoThresholdCondition condition = fluid ? new FluidThresholdCondition() : new ItemThresholdCondition();
        condition.setItem(0, new ItemStack(fluid ? Items.WATER_BUCKET : Items.DIAMOND));
        condition.getData().putInt("Operator", operator.ordinal()); condition.getData().putString("Threshold", "" + threshold); return condition;
    }

    private static void threshold(TrainScheduleScenario s, int direction, boolean fluid, Ops op, int offset) {
        int target = fluid ? 2000 : 8, amount = target + offset;
        // A second carriage's initial water/fuel must not pollute the threshold.
        for (var c : s.train().carriages) {
            if (fluid) c.storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            else for (int i = 0; i < c.storage.getAllItems().getSlots(); i++) c.storage.getAllItems().setStackInSlot(i, ItemStack.EMPTY);
        }
        setCargo(s, fluid, amount, true);
        waiting(s, direction, cargo(fluid, op, fluid ? 2 : 8), () -> s.after(24, () -> {
            boolean expected = switch (op) { case LESS -> offset < 0; case EQUAL -> offset == 0; case GREATER -> offset > 0; };
            s.h.assertTrue(s.train().runtime.completed == expected, "cargo threshold tests below/equal/above independently: " + op + " " + amount);
            if (expected) { s.succeed(); return; }
            setCargo(s, fluid, target + (op == Ops.LESS ? -1 : op == Ops.GREATER ? 1 : 0), true); completed(s, 24);
        }));
    }

    private static void filter(TrainScheduleScenario s, int direction, boolean fluid) {
        for (var c : s.train().carriages) if (fluid) c.storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
        setCargo(s, fluid, fluid ? 3000 : 9, false);
        waiting(s, direction, cargo(fluid, Ops.GREATER, fluid ? 2 : 8), () -> s.after(24, () -> {
            s.h.assertTrue(!s.train().runtime.completed, "unmatched cargo cannot satisfy a filtered condition");
            setCargo(s, fluid, fluid ? 3000 : 9, true); completed(s, 24);
        }));
    }

    private static void passengers(TrainScheduleScenario s, int direction, boolean above, int count) {
        var players = new ArrayList<net.minecraft.world.entity.player.Player>();
        for (int i = 0; i < count; i++) {
            var player = s.h.makeMockPlayer(GameType.SURVIVAL); player.setPos(s.f.entity().position()); s.f.entity().addSittingPassenger(player, i); players.add(player);
            s.cleanup.add(player::stopRiding);
        }
        var condition = new PlayerPassengerCondition(); condition.getData().putInt("Count", 1); condition.getData().putInt("Exact", above ? 1 : 0);
        s.h.assertTrue(s.train().countPlayerPassengers() == count, "native seat passenger count matches the boundary input");
        waiting(s, direction, condition, () -> s.after(6, () -> {
            s.h.assertTrue(s.train().runtime.completed == (above ? count >= 1 : count == 1), "passenger conditions distinguish exact count from a lower bound");
            if (s.train().runtime.completed) { s.succeed(); return; }
            if (count == 0) { var player = s.h.makeMockPlayer(GameType.SURVIVAL); s.f.entity().addSittingPassenger(player, 0); s.cleanup.add(player::stopRiding); }
            else players.getLast().stopRiding();
            s.h.assertTrue(s.train().countPlayerPassengers() == 1, "boarding or dismounting leaves exactly one real passenger");
            completed(s, 5);
        }));
    }

    private static void clock(TrainScheduleScenario s, int direction, int difference) {
        long old = s.h.getLevel().getDayTime(), start = s.h.getLevel().getGameTime();
        var rule = s.h.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT); boolean daylight = rule.get();
        rule.set(false, s.h.getLevel().getServer()); s.cleanup.add(() -> {
            rule.set(daylight, s.h.getLevel().getServer()); s.h.getLevel().setDayTime(old + (daylight ? s.h.getLevel().getGameTime() - start : 0));
        });
        var condition = new TimeOfDayCondition(); condition.getData().putInt("Hour", 6); condition.getData().putInt("Minute", 1); condition.getData().putInt("Rotation", 0);
        s.h.getLevel().setDayTime(17 + difference);
        waiting(s, direction, condition, () -> s.after(5, () -> {
            s.h.assertTrue(s.train().runtime.completed == (difference >= 0 && difference <= 40), "time-of-day window includes its endpoints and rejects adjacent ticks");
            if (s.train().runtime.completed) { s.succeed(); return; }
            s.h.getLevel().setDayTime(17); completed(s, 5);
        }));
    }

    private static void idle(TrainScheduleScenario s, int direction) {
        var condition = new IdleCargoCondition(); condition.getData().putInt("Value", 12); condition.getData().putInt("TimeUnit", 0);
        waiting(s, direction, condition, () -> s.after(6, () -> {
            var storage = s.train().carriages.getFirst().storage; storage.getAllItems().insertItem(1, new ItemStack(Items.DIAMOND), false);
            s.h.assertTrue(storage.getTicksSinceLastExchange() == 0, "actual cargo mutation resets the idle tracker");
            s.after(12, () -> { s.h.assertTrue(!s.train().runtime.completed, "idle requires more than its exact configured duration after the last exchange"); completed(s, 5); });
        }));
    }

    private static void unloaded(TrainScheduleScenario s, int direction) {
        waiting(s, direction, new StationUnloadedCondition(), () -> s.after(6, () -> {
            s.h.assertTrue(!s.train().runtime.completed, "an entity-ticking station does not satisfy unloaded waiting");
            s.train().getCurrentStation().blockEntityPos = new BlockPos(2_000_000, 64, 2_000_000); completed(s, 5);
        }));
    }

    private static void wireless(TrainScheduleScenario s, int direction, boolean inverted) {
        var condition = new RedstoneLinkCondition(); condition.setItem(0, new ItemStack(Items.DIAMOND)); condition.setItem(1, new ItemStack(Items.COPPER_INGOT));
        condition.getData().putInt("Inverted", inverted ? 1 : 0);
        class Transmitter implements IRedstoneLinkable {
            boolean powered = inverted;
            public int getTransmittedStrength() { return powered ? 15 : 0; }
            public void setReceivedStrength(int power) {}
            public boolean isListening() { return false; }
            public boolean isAlive() { return true; }
            public Couple<Frequency> getNetworkKey() { return condition.freq; }
            public BlockPos getLocation() { return s.h.absolutePos(BlockPos.ZERO); }
        }
        var transmitter = new Transmitter(); var handler = Create.REDSTONE_LINK_NETWORK_HANDLER;
        handler.addToNetwork(s.h.getLevel(), transmitter); s.cleanup.add(() -> handler.removeFromNetwork(s.h.getLevel(), transmitter));
        waiting(s, direction, condition, () -> s.after(6, () -> {
            s.h.assertTrue(!s.train().runtime.completed, "wireless wait rejects the opposite activation state");
            transmitter.powered = !transmitter.powered; handler.updateNetworkOf(s.h.getLevel(), transmitter); completed(s, 5);
        }));
    }
}
