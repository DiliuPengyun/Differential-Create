package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.KineticStressDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Physical source/load sampling, a real dial and comparator, and unavailable states. */
final class LoadGaugeTests {
    static void displayLink(GameTestHelper h) {
        BlockPos pos = new BlockPos(5, 2, 5);
        h.setBlock(pos, AllBlocks.STRESSOMETER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH)
            .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        h.setBlock(pos.south(), AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, Direction.SOUTH));
        h.runAfterDelay(8, () -> {
            NetworkTopology.flush();
            StressGaugeBlockEntity gauge = h.getBlockEntity(pos);
            DisplayLinkBlockEntity link = h.getBlockEntity(pos.south());
            var context = new DisplayLinkContext(h.getLevel(), link);
            var source = new KineticStressDisplaySource();
            var stats = new DisplayTargetStats(1, 30, null);
            var data = ((INetworkDynamics) gauge.getOrCreateNetwork()).differentialcreate$data();
            for (double fraction : new double[]{0, 1, 2, Double.POSITIVE_INFINITY}) {
                data.loadFactor = new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, fraction);
                for (int mode : new int[]{1, 2, 3, 4}) {
                    link.getSourceConfig().putInt("Mode", mode);
                    var text = source.provideText(context, stats).getFirst().getString();
                    h.assertTrue(text.equals(LoadGaugeScale.percent(fraction)), "percent/legacy display modes retain full physical load: " + text);
                }
                if (fraction == 1 || Double.isInfinite(fraction)) {
                    link.getSourceConfig().putInt("Mode", 0);
                    String expected = fraction == 1 ? "█".repeat(15) + "▒".repeat(15) : "█".repeat(30);
                    h.assertTrue(source.provideText(context, stats).getFirst().getString().equals(expected),
                        "progress bar uses the same centred/infinite scale as the dial");
                }
            }
            for (var unavailable : new LoadFactor.Reading[]{LoadFactor.Reading.UNDEFINED, LoadFactor.Reading.UNKNOWN,
                    LoadFactor.Reading.NO_POWER, new LoadFactor.Reading(LoadFactor.Status.BRAKING, 0)}) {
                data.loadFactor = unavailable;
                for (int mode : new int[]{0, 1}) {
                    link.getSourceConfig().putInt("Mode", mode);
                    var actual = source.provideText(context, stats).getFirst();
                    h.assertTrue(actual.equals(LoadGaugeBehaviour.text(unavailable)), "bar and percent modes both retain explicit instrument status");
                }
            }
            h.succeed();
        });
    }

    static void run(GameTestHelper h) {
        BlockPos sourcePos = new BlockPos(3, 2, 4), gaugePos = sourcePos.east(2), pressPos = gaugePos.east();
        h.setBlock(sourcePos, AllBlocks.WATER_WHEEL.getDefaultState().setValue(WaterWheelBlock.FACING, Direction.EAST));
        h.setBlock(sourcePos.east(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(gaugePos, AllBlocks.STRESSOMETER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH)
            .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        h.setBlock(pressPos, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        h.setBlock(gaugePos.north().below(), Blocks.STONE);
        h.setBlock(gaugePos.north(), Blocks.COMPARATOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        WaterWheelBlockEntity source = h.getBlockEntity(sourcePos);
        StressGaugeBlockEntity gauge = h.getBlockEntity(gaugePos);
        MechanicalPressBlockEntity press = h.getBlockEntity(pressPos);
        double[] speed = {Double.NaN};
        source.setFlowScoreAndUpdate(1);
        h.onEachTick(() -> {
            if (source.flowScore != 1) source.setFlowScoreAndUpdate(1);
            if (Double.isFinite(speed[0]) && source.hasNetwork()) {
                setSpeed(source, speed[0]);
                LoadGaugeBehaviour.sample(source.getOrCreateNetwork());
            }
        });
        h.startSequence().thenExecuteAfter(12, () -> {
            NetworkTopology.flush();
            h.assertTrue(gauge.hasNetwork() && gauge.network.equals(source.network) && press.network.equals(source.network),
                "load instrument belongs to the physical source/machine network");
            press.getPressingBehaviour().start(PressingBehaviour.Mode.WORLD);
            for (double[] sample : new double[][]{{12, .5}, {14, 1}, {15, 2}, {16, Double.POSITIVE_INFINITY}, {0, .125}}) {
                setSpeed(source, sample[0]);
                var reading = LoadGaugeBehaviour.sample(source.getOrCreateNetwork());
                h.assertTrue(reading.status() == LoadFactor.Status.AVAILABLE && reading.fraction() == sample[1], "real network load " + sample[1]);
                h.assertTrue(gauge.dialTarget == LoadGaugeScale.position(reading), "pointer follows physical load, including rest/infinity");
                h.assertTrue(gauge.getBlockState().getAnalogOutputSignal(h.getLevel(), gauge.getBlockPos()) == LoadGaugeScale.signal(reading),
                    "comparator source follows the same nonlinear scale");
                LoadFactorTests.tooltip(h, gauge, LoadGaugeScale.percent(reading.fraction()),
                    reading.fraction() > 1 ? net.minecraft.ChatFormatting.RED : reading.fraction() < .9
                        ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.YELLOW);
            }
            var data = ((INetworkDynamics) source.getOrCreateNetwork()).differentialcreate$data();
            for (var unavailable : new LoadFactor.Reading[]{LoadFactor.Reading.UNDEFINED, LoadFactor.Reading.UNKNOWN}) {
                data.loadFactor = unavailable;
                gauge.getBehaviour(LoadGaugeBehaviour.TYPE).refresh();
                h.assertTrue(gauge.dialTarget == LoadGaugeScale.REST, "unavailable instrument parks below its zero mark");
                var text = LoadGaugeBehaviour.text(unavailable).getContents();
                h.assertTrue(text instanceof TranslatableContents contents && contents.getKey().equals(
                    unavailable.status() == LoadFactor.Status.UNDEFINED ? "gui.differentialcreate.goggles.load_undefined"
                        : "gui.differentialcreate.goggles.load_waiting"), "undefined and waiting have distinct text");
            }
            speed[0] = 14;
            setSpeed(source, speed[0]);
            LoadGaugeBehaviour.sample(source.getOrCreateNetwork());
            gauge.dialState = 4; // An old overstress pointer must not retain its random wobble.
            gauge.getBehaviour(LoadGaugeBehaviour.TYPE).refresh();
            h.assertTrue(gauge.dialState <= 1, "legacy off-scale state is bounded");
        }).thenExecuteAfter(5, () -> {
            ComparatorBlockEntity comparator = h.getBlockEntity(gaugePos.north());
            h.assertTrue(comparator.getOutputSignal() == 8, "neighbouring comparator receives the centred 100% signal");
            h.assertTrue(Math.abs(gauge.dialTarget - .5f) < .001, "equilibrium is at the middle of the dial");
        }).thenSucceed();
    }

    private static void setSpeed(WaterWheelBlockEntity source, double speed) {
        var ratios = ((INetworkDynamics) source.getOrCreateNetwork()).differentialcreate$getRatios();
        double reference = ratios.get(source);
        ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = speed * ratio / reference);
    }
}
