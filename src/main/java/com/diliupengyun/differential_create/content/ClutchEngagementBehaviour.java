package com.diliupengyun.differential_create.content;

import java.util.List;

import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The setting is a pressure ceiling; redstone can release it without overwriting it. */
public final class ClutchEngagementBehaviour extends ScrollValueBehaviour {
	public static final String NBT_KEY = "DCClutchEngagement";

	public ClutchEngagementBehaviour(BrassClutchBlockEntity clutch) {
		super(Component.translatable("gui.differentialcreate.clutch.engagement_setting"), clutch, new EngagementSlot());
		between(0, 100);
		value = 100;
		withFormatter(percent -> percent + "%");
		withCallback(percent -> ClutchPhysics.manualEngagementChanged(clutch));
	}

	@Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		tag.putInt(NBT_KEY, value);
	}

	@Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		value = tag.contains(NBT_KEY) ? Math.clamp(tag.getInt(NBT_KEY), 0, 100) : 100;
	}

	@Override public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
		return new ValueSettingsBoard(label, 100, 10, List.of(Component.literal("%")),
			new ValueSettingsFormatter(this::formatSettings));
	}

	public MutableComponent formatSettings(ValueSettings settings) {
		int percent = Math.clamp(settings.value(), 0, 100);
		return Component.literal(percent + "%");
	}

	@Override public String getClipboardKey() { return "ClutchEngagement"; }

	private static final class EngagementSlot extends ValueBoxTransform.Sided {
		@Override protected Vec3 getSouthLocation() { return new Vec3(0.5, 0.5, 17 / 16.0); }
		@Override protected boolean isSideActive(BlockState state, Direction face) {
			return face.getAxis() != state.getValue(BlockStateProperties.AXIS);
		}
	}
}
