package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real powered clocks must consume their integrated travel during changing loads. */
final class ClockOrientationTests {
    static void run(GameTestHelper h) {
        var positions = new ArrayList<BlockPos>();
        long time = h.getLevel().getDayTime();
        h.getLevel().setDayTime(6000);
        for (var facing : Direction.values()) {
            var p = new BlockPos(6 + facing.ordinal() % 3 * 9, 12, 6 + facing.ordinal() / 3 * 12);
            positions.add(p);
            h.setBlock(p, AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            Direction radial = facing.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
            for (int n = 0; n < 4; n++) h.setBlock(p.relative(facing).relative(radial, n), Blocks.SLIME_BLOCK);
            var motor = p.relative(facing.getOpposite());
            h.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            ((CreativeMotorBlockEntity) h.getBlockEntity(motor)).generatedSpeed.setValue(24);
        }
        h.runAfterDelay(15, () -> {
            for (var p : positions) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(p);
                if (!be.isRunning()) be.assemble();
            }
            h.getLevel().setDayTime(12000);
        });
        double[] previous = new double[6]; java.util.Arrays.fill(previous, Double.NaN);
        h.onEachTick(() -> {
            for (int i = 0; i < positions.size(); i++) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(positions.get(i));
                var root = ((ClockworkBearingAccessor) be).differentialcreate$hour();
                if (root == null) continue;
                double angle = root.getAngle(1);
                if (h.getTick() >= 20 && h.getTick() < 70 && Math.abs(angle) > 5 && Math.abs(angle) < 150 && Double.isFinite(previous[i]))
                    h.assertTrue(Math.abs(angle - previous[i]) > .01, "a powered hand must not pause at a fictitious per-tick endpoint: "
                        + Direction.values()[i] + " angle=" + angle + " previous=" + previous[i]);
                previous[i] = angle;
            }
        });
        h.runAfterDelay(70, () -> {
            for (int i = 0; i < positions.size(); i++) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(positions.get(i));
                var root = ((ClockworkBearingAccessor) be).differentialcreate$hour();
                h.assertTrue(root != null && !root.isRemoved(), "powered clock retains hour hand: " + Direction.values()[i]);
                h.assertTrue(Math.abs(root.getAngle(1)) > 30, "powered clock must turn toward the changed time despite gravity and RPM notifications: "
                    + Direction.values()[i] + " angle=" + root.getAngle(1));
                be.disassemble();
            }
            h.getLevel().setDayTime(time); h.succeed();
        });
    }
}
