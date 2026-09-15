package com.diliupengyun.differential_create.kinetics;

import java.util.List;

import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** A wrench-adjustable, saved range. Sampling every tick also catches small redstone threshold crossings. */
public final class SpeedGaugeRangeBehaviour extends ScrollValueBehaviour {
	private static final String RANGE_TAG = "DCSpeedGaugeRange";
	private int lastSignal = -1;

	public SpeedGaugeRangeBehaviour(SpeedGaugeBlockEntity gauge) {
		super(Component.translatable("gui.differentialcreate.gauge.range_setting"), gauge, new RangeSlot());
		between(0, SpeedGaugeScale.MAX_INDEX);
		value = SpeedGaugeScale.DEFAULT_INDEX;
		requiresWrench();
		withFormatter(index -> SpeedGaugeScale.range(index) + " RPM");
		withCallback(index -> refresh());
	}

	public static int rangeOf(SpeedGaugeBlockEntity gauge) {
		return gauge.getBehaviour(TYPE) instanceof SpeedGaugeRangeBehaviour range
			? SpeedGaugeScale.range(range.getValue()) : SpeedGaugeScale.range(SpeedGaugeScale.DEFAULT_INDEX);
	}

	@Override public void tick() { refresh(); }
	@Override public void initialize() { refresh(); }

	private void refresh() {
		SpeedGaugeBlockEntity gauge = (SpeedGaugeBlockEntity) blockEntity;
		Level level = gauge.getLevel();
		if (level == null) return;
		int range = SpeedGaugeScale.range(value);
		double speed = NetworkDynamics.preciseSpeed(gauge);
		gauge.dialTarget = SpeedGaugeScale.fraction(speed, range);
		gauge.color = SpeedGaugeScale.color(speed, range);
		// Old packets or saves can contain an off-scale pointer. Bounded states also
		// bypass GaugeBlockEntity's random "overload" wobble while keeping its easing.
		gauge.dialState = Math.clamp(gauge.dialState, 0, 1);
		gauge.prevDialState = Math.clamp(gauge.prevDialState, 0, 1);
		if (!level.isClientSide) {
			int signal = SpeedGaugeScale.signal(speed, range);
			if (signal != lastSignal) {
				lastSignal = signal;
				level.updateNeighbourForOutputSignal(gauge.getBlockPos(), gauge.getBlockState().getBlock());
			}
		}
	}

	@Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		tag.putInt(RANGE_TAG, value);
	}

	@Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		value = tag.contains(RANGE_TAG) ? Math.clamp(tag.getInt(RANGE_TAG), 0, SpeedGaugeScale.MAX_INDEX)
			: SpeedGaugeScale.DEFAULT_INDEX;
		lastSignal = -1;
	}

	@Override public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
		return new ValueSettingsBoard(label, SpeedGaugeScale.MAX_INDEX, 1, List.of(Component.literal("RPM")),
			new ValueSettingsFormatter(settings -> Component.literal(Integer.toString(SpeedGaugeScale.range(settings.value())))));
	}

	@Override public String getClipboardKey() { return "SpeedGaugeRange"; }

	private static final class RangeSlot extends ValueBoxTransform.Sided {
		@Override protected Vec3 getSouthLocation() { return new Vec3(0.5, 0.5, 15.5 / 16); }

		@Override public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
			if (!(level instanceof Level world) || !(state.getBlock() instanceof GaugeBlock gauge)
				|| !gauge.shouldRenderHeadOnFace(world, pos, state, getSide())) return null;
			return super.getLocalOffset(level, pos, state);
		}
	}
}
