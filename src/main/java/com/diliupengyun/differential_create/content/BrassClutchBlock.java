package com.diliupengyun.differential_create.content;

import com.simibubi.create.content.kinetics.transmission.ClutchBlock;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class BrassClutchBlock extends ClutchBlock {
	public BrassClutchBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntityType<? extends SplitShaftBlockEntity> getBlockEntityType() {
		return ModContent.BRASS_CLUTCH_ENTITY.get();
	}
}
