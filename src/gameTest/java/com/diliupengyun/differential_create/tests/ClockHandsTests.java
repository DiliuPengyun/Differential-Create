package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.diliupengyun.differential_create.kinetics.ClockworkMotion;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Two independently collected hands, native targets and both shaft directions. */
final class ClockHandsTests {
    static void targets(GameTestHelper h) {
        // Exercise the transformed native methods, including Create's faster
        // non-natural dimension clock, rather than calling our formula directly.
        for (var dimension : java.util.List.of(net.minecraft.world.level.Level.OVERWORLD, net.minecraft.world.level.Level.NETHER)) {
            var world = h.getLevel().getServer().getLevel(dimension);
            h.assertTrue(world != null, "target test dimension is loaded");
            // Derived dimension data reads the overworld clock; its time setter is a no-op.
            var timeWorld = h.getLevel().getServer().overworld();
            long previous = timeWorld.getDayTime();
            try {
                for (var facing : Direction.values()) {
                    var state = AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing);
                    var be = new ClockworkBearingBlockEntity(com.simibubi.create.AllBlockEntityTypes.CLOCKWORK_BEARING.get(), BlockPos.ZERO, state);
                    be.setLevel(world); var access = (ClockworkBearingAccessor) be;
                    int sign = facing.getAxisDirection().getStep();
                    for (long time : new long[]{0, 1, 16, 17, 125, 500, 999, 1000, 5999, 6000, 17999, 18000, 23999, 24000}) {
                        timeWorld.setDayTime(time);
                        h.assertTrue(world.getDayTime() == time, "dimension sees the controlled world time");
                        double hours = (time * (world.dimensionType().natural() ? 1 : 24) / 1000d + 6) % 24;
                        double minute = (hours * 60) % 60;
                        h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$hourTarget(false), (float) (-sign * hours * 30))) < 5e-5,
                            "native 12-hour target includes minutes: " + dimension + " " + facing + " " + time);
                        h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$hourTarget(true), (float) (-sign * hours * 15))) < 5e-5,
                            "native 24-hour target includes minutes: " + dimension + " " + facing + " " + time);
                        h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$minuteTarget(), (float) (-sign * minute * 6))) < 5e-5,
                            "native minute target includes seconds: " + dimension + " " + facing + " " + time);
                    }
                }
            } finally { timeWorld.setDayTime(previous); }
        }
        h.succeed();
    }
    static void run(GameTestHelper h, int mode) {
        var positions = new ArrayList<BlockPos>();
        var daylight = h.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        boolean previousDaylight = daylight.get();
        long previousTime = h.getLevel().getDayTime();
        daylight.set(false, h.getLevel().getServer());
        h.getLevel().setDayTime(18000);
        for (var facing : Direction.values()) {
            var p = new BlockPos(6 + facing.ordinal() % 3 * 9, 12, 6 + facing.ordinal() / 3 * 12);
            positions.add(p);
            h.setBlock(p, AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            ((ClockworkBearingAccessor) h.getBlockEntity(p)).differentialcreate$operationMode().setValue(mode);
            Direction radial = facing.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
            for (int n = 0; n < 4; n++) h.setBlock(p.relative(facing).relative(radial, n), Blocks.SLIME_BLOCK);
            for (int n = 0; n < 3; n++) h.setBlock(p.relative(facing, 2).relative(radial, n), Blocks.HONEY_BLOCK);
            var motor = p.relative(facing.getOpposite());
            h.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            ((CreativeMotorBlockEntity) h.getBlockEntity(motor)).generatedSpeed.setValue(facing.ordinal() % 2 == 0 ? 48 : -48);
        }
        h.runAfterDelay(15, () -> {
            for (var p : positions) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(p);
                if (!be.isRunning()) be.assemble();
                var access = (ClockworkBearingAccessor) be;
                h.assertTrue(access.differentialcreate$hour() != null && access.differentialcreate$minute() != null,
                    "both independently collected hands assemble in mode " + mode);
            }
            h.getLevel().setDayTime(12500);
        });
        var rows = new ArrayList<String>(); rows.add("tick,day_time,facing,first_angle,second_angle,first_target,second_target");
        double[][] previousAngles = new double[6][2];
        for (var pair : previousAngles) java.util.Arrays.fill(pair, Double.NaN);
        h.onEachTick(() -> {
            if (h.getTick() >= 85 && h.getTick() < 100 || h.getTick() >= 170 && h.getTick() < 185) {
                for (int i = 0; i < positions.size(); i++) {
                    var access = (ClockworkBearingAccessor) h.getBlockEntity(positions.get(i));
                    float first = mode == 1 ? access.differentialcreate$minuteTarget() : access.differentialcreate$hourTarget(mode == 2);
                    float second = mode == 1 ? access.differentialcreate$hourTarget(false) : access.differentialcreate$minuteTarget();
                    h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$hourAngle(), first)) < .01,
                        "first hand reaches and holds target: mode=" + mode + " facing=" + Direction.values()[i]
                            + " angle=" + access.differentialcreate$hourAngle() + " target=" + first);
                    h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$minuteAngle(), second)) < .01,
                        "second hand reaches and holds target: mode=" + mode + " facing=" + Direction.values()[i]
                            + " angle=" + access.differentialcreate$minuteAngle() + " target=" + second);
                }
            }
            if (h.getTick() >= 300 && h.getTick() < 425) {
                for (int i = 0; i < positions.size(); i++) {
                    ClockworkBearingBlockEntity be = h.getBlockEntity(positions.get(i));
                    var access = (ClockworkBearingAccessor) be; var clock = ClockworkMotion.of(be);
                    double[] angles = {clock.hour.angle * 180, clock.minute.angle * 360};
                    float[] targets = {mode == 1 ? access.differentialcreate$minuteTarget() : access.differentialcreate$hourTarget(mode == 2),
                        mode == 1 ? access.differentialcreate$hourTarget(false) : access.differentialcreate$minuteTarget()};
                    for (int hand = 0; hand < 2; hand++) {
                        boolean hour = mode == 1 ? hand == 1 : hand == 0;
                        double rate = hour ? mode == 2 ? .015 : .03 : .36;
                        double step = -Direction.values()[i].getAxisDirection().getStep() * (angles[hand] - previousAngles[i][hand]);
                        if (Double.isFinite(step)) h.assertTrue(step > rate * .25 && step < rate * 1.75,
                            "running hand follows time without whole-minute/hour stops or jumps: mode=" + mode + " facing=" + Direction.values()[i]
                                + " hand=" + hand + " tick=" + h.getTick() + " step=" + step);
                        h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff((float) (angles[hand] % 360), targets[hand])) < (hour ? .5 : 5),
                            "finite controller tracks the advancing time with bounded lag");
                        previousAngles[i][hand] = angles[hand];
                    }
                    rows.add(h.getTick() + "," + h.getLevel().getDayTime() + "," + Direction.values()[i] + "," + angles[0] + "," + angles[1] + "," + targets[0] + "," + targets[1]);
                }
            }
        });
        h.runAfterDelay(100, () -> h.getLevel().setDayTime(18000));
        h.runAfterDelay(185, () -> h.getLevel().setDayTime(17900));
        h.runAfterDelay(265, () -> daylight.set(true, h.getLevel().getServer()));
        h.runAfterDelay(425, () -> daylight.set(false, h.getLevel().getServer()));
        h.runAfterDelay(500, () -> {
            for (var p : positions) {
                var access = (ClockworkBearingAccessor) h.getBlockEntity(p);
                float first = mode == 1 ? access.differentialcreate$minuteTarget() : access.differentialcreate$hourTarget(mode == 2);
                float second = mode == 1 ? access.differentialcreate$hourTarget(false) : access.differentialcreate$minuteTarget();
                h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$hourAngle(), first)) < .01
                    && Math.abs(AngleHelper.getShortestAngleDiff(access.differentialcreate$minuteAngle(), second)) < .01,
                    "freezing world time stops the target and both hands settle");
            }
            for (var p : positions) ((ClockworkBearingBlockEntity) h.getBlockEntity(p)).disassemble();
            PositionControlTests.save("clock-target-tracking-" + mode + ".csv", rows);
            h.getLevel().setDayTime(previousTime);
            daylight.set(previousDaylight, h.getLevel().getServer());
            h.succeed();
        });
    }
}
