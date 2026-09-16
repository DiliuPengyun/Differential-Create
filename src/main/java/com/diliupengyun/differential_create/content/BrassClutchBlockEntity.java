package com.diliupengyun.differential_create.content;

import java.util.List;

import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Inherits the same two-shaft physics, saved slip state and renderers as the starter clutch. */
public final class BrassClutchBlockEntity extends ClutchBlockEntity {
	public BrassClutchBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		behaviours.add(new ClutchEngagementBehaviour(this));
	}

	public double manualEngagement() {
		return getBehaviour(ScrollValueBehaviour.TYPE) instanceof ClutchEngagementBehaviour settings
			? settings.getValue() / 100.0 : 1;
	}
}
