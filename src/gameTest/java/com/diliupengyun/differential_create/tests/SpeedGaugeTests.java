package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;

import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeScale;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real gauge, config migration, saved controls, pointer motion and a neighbouring redstone comparator. */
final class SpeedGaugeTests {
	private SpeedGaugeTests() {}

	static void run(GameTestHelper helper) {
		var spec = AllConfigs.server().specification;
		helper.assertFalse(spec.getValues().contains("kinetics.maxRotationSpeed"), "speed limit must not appear in the config UI");
		var oldConfig = CommentedConfig.inMemory();
		spec.correct(oldConfig);
		oldConfig.set("kinetics.maxRotationSpeed", 4096);
		oldConfig.set("kinetics.maxBeltLength", 23);
		spec.correct(oldConfig);
		helper.assertFalse(oldConfig.contains("kinetics.maxRotationSpeed"), "old speed limit must be removed on config migration");
		helper.assertTrue((int) oldConfig.get("kinetics.maxBeltLength") == 23, "unrelated user settings survive config migration");
		helper.assertTrue(AllConfigs.server().kinetics.maxRotationSpeed.get() == 256, "the current Create advancement reference stays readable");

		BlockPos pos = new BlockPos(6, 2, 6), secondPos = new BlockPos(6, 2, 12);
		SpeedGaugeBlockEntity gauge = place(helper, pos), second = place(helper, secondPos);
		helper.setBlock(pos.east().below(), Blocks.STONE);
		helper.setBlock(pos.east(), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		var player = helper.makeMockPlayer(GameType.CREATIVE);
		var settings = range(gauge);
		helper.startSequence().thenExecuteAfter(5, () -> {
			NetworkTopology.flush();
			helper.assertTrue(SpeedGaugeRangeBehaviour.rangeOf(gauge) == 256, "new gauges default to a 256 RPM range");
			helper.assertTrue(settings.onlyVisibleWithWrench(), "range control uses the wrench interaction");
			var hit = new BlockHitResult(Vec3.atCenterOf(gauge.getBlockPos()), Direction.NORTH, gauge.getBlockPos(), false);
			var board = settings.createBoard(player, hit);
			helper.assertTrue(board.formatter().format(new ValueSettings(0, 2)).getString().equals("1024"), "setting board displays RPM rather than an internal index");
			var slot = (ValueBoxTransform.Sided) settings.getSlotPositioning();
			for (Direction face : Direction.values()) {
				boolean visible = ((GaugeBlock) gauge.getBlockState().getBlock()).shouldRenderHeadOnFace(
					gauge.getLevel(), gauge.getBlockPos(), gauge.getBlockState(), face);
				helper.assertTrue(slot.fromSide(face).shouldRender(gauge.getLevel(), gauge.getBlockPos(), gauge.getBlockState()) == visible,
					"range control must appear only on a visible dial face: " + face);
			}
			settings.setValueSettings(player, new ValueSettings(0, 2), false);
			setSpeed(gauge, 512);
			setSpeed(second, 512);
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 512, .5f, 8, SpeedGaugeScale.RUNNING_COLOR);
			helper.assertTrue(SpeedGaugeRangeBehaviour.rangeOf(second) == 256 && second.dialTarget == 1,
				"separate gauges retain independent ranges");
			// Save/reload and client packets must carry the selected range; old saves get the default.
			var clone = new SpeedGaugeBlockEntity(gauge.getType(), gauge.getBlockPos(), gauge.getBlockState());
			clone.setLevel(helper.getLevel());
			clone.loadWithComponents(gauge.saveWithoutMetadata(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
			helper.assertTrue(SpeedGaugeRangeBehaviour.rangeOf(clone) == 1024, "range survives a full NBT round trip");
			range(clone).setValue(0);
			clone.readClient(gauge.writeClient(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
			helper.assertTrue(SpeedGaugeRangeBehaviour.rangeOf(clone) == 1024, "range survives client synchronization");
			range(clone).read(new CompoundTag(), helper.getLevel().registryAccess(), false);
			helper.assertTrue(SpeedGaugeRangeBehaviour.rangeOf(clone) == 256, "legacy saves default to 256 RPM");
			var safe = new CompoundTag();
			gauge.writeSafe(safe, helper.getLevel().registryAccess());
			helper.assertTrue(safe.getInt("DCSpeedGaugeRange") == 2, "schematics retain the selected range");
			var clipboard = new CompoundTag();
			settings.writeToClipboard(helper.getLevel().registryAccess(), clipboard, Direction.NORTH);
			range(second).readFromClipboard(helper.getLevel().registryAccess(), clipboard, player, Direction.NORTH, false);
			helper.assertTrue(SpeedGaugeRangeBehaviour.rangeOf(second) == 1024, "clipboard applies the same range");
			setSpeed(gauge, -512);
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), -512, .5f, 8, SpeedGaugeScale.RUNNING_COLOR);
			setSpeed(gauge, 1024);
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 1024, 1, 15, SpeedGaugeScale.RUNNING_COLOR);
			setSpeed(gauge, 2048);
			gauge.dialState = 4; // Simulate a stale off-scale state from the old implementation.
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 2048, 1, 15, SpeedGaugeScale.OVER_RANGE_COLOR);
			var tooltip = new ArrayList<Component>();
			// The vanilla tooltip wrapper uses the client font; exercise our shared readouts on the server.
			KineticGoggleInfo.append(gauge, tooltip, false);
			helper.assertTrue(hasKey(tooltip, "gui.differentialcreate.gauge.over_range"), "goggles identify an exceeded instrument range");
			helper.assertTrue(hasKey(tooltip, "gui.differentialcreate.gauge.range"), "goggles show the selected range");
			helper.assertTrue(tooltip.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents contents
				&& contents.getKey().equals("gui.differentialcreate.goggles.speed") && contents.getArgs()[0].equals("+2048.0")),
				"goggles retain the full RPM above the selected range");
		}).thenExecuteFor(20, () -> {
			helper.assertTrue(gauge.dialState == 1 && gauge.prevDialState == 1, "over-range pointer remains steady without random wobble");
		}).thenExecute(() -> {
			settings.setValueSettings(player, new ValueSettings(0, 3), false); // 4096 RPM at unchanged speed
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 2048, .5f, 8, SpeedGaugeScale.RUNNING_COLOR);
			settings.setValue(2);
			setSpeed(gauge, 68.26);
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 68.26, 68.26f / 1024, 1, SpeedGaugeScale.RUNNING_COLOR);
			setSpeed(gauge, 68.27); // Below the generic kinetic speed-change notification threshold.
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 68.27, 68.27f / 1024, 2, SpeedGaugeScale.RUNNING_COLOR);
			setSpeed(gauge, .25);
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), .25, .25f / 1024, 1, SpeedGaugeScale.RUNNING_COLOR);
			setSpeed(gauge, 0);
		}).thenExecuteAfter(5, () -> {
			assertGauge(helper, gauge, pos.east(), 0, 0, 0, SpeedGaugeScale.STOPPED_COLOR);
		}).thenSucceed();
	}

	private static boolean hasKey(ArrayList<Component> tooltip, String key) {
		return tooltip.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key));
	}

	private static SpeedGaugeBlockEntity place(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos.west(2), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(pos.west(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(pos, AllBlocks.SPEEDOMETER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH)
			.setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true));
		return helper.getBlockEntity(pos);
	}

	private static SpeedGaugeRangeBehaviour range(SpeedGaugeBlockEntity gauge) {
		return (SpeedGaugeRangeBehaviour) gauge.getBehaviour(ScrollValueBehaviour.TYPE);
	}

	private static void setSpeed(SpeedGaugeBlockEntity gauge, double speed) {
		INetworkDynamics dynamics = (INetworkDynamics) gauge.getOrCreateNetwork();
		dynamics.differentialcreate$setSpeed(speed / dynamics.differentialcreate$getRatios().get(gauge));
	}

	private static void assertGauge(GameTestHelper helper, SpeedGaugeBlockEntity gauge, BlockPos comparatorPos,
		double speed, float fraction, int signal, int color) {
		helper.assertTrue(!gauge.isRemoved(), "high speed must not destroy the gauge");
		helper.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(gauge) - speed) < 1e-8, "range must not change the physical speed");
		helper.assertTrue(Math.abs(gauge.dialTarget - fraction) < 1e-6, "pointer uses the selected linear range");
		helper.assertTrue(gauge.color == color, "gauge color follows stopped/running/out-of-range state");
		helper.assertTrue(gauge.getBlockState().getAnalogOutputSignal(gauge.getLevel(), gauge.getBlockPos()) == signal,
			"analog output follows the selected range");
		ComparatorBlockEntity comparator = helper.getBlockEntity(comparatorPos);
		helper.assertTrue(comparator.getOutputSignal() == signal, "neighbouring comparator updates to " + signal + ", got " + comparator.getOutputSignal());
	}
}
