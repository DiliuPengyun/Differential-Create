package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
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
        });
        h.runAfterDelay(100, () -> h.getLevel().setDayTime(18000));
        h.runAfterDelay(185, () -> {
            for (var p : positions) ((ClockworkBearingBlockEntity) h.getBlockEntity(p)).disassemble();
            h.getLevel().setDayTime(previousTime);
            daylight.set(previousDaylight, h.getLevel().getServer());
            h.succeed();
        });
    }
}
