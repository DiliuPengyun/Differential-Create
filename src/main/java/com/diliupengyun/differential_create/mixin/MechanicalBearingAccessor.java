package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;

/**
 * Access to the bearing's contraption state for the windmill sail
 * interpolation.
 */
@Mixin(MechanicalBearingBlockEntity.class)
public interface MechanicalBearingAccessor {

	@Accessor("running")
	boolean differentialcreate$isRunning();

	@Accessor("movedContraption")
	ControlledContraptionEntity differentialcreate$getMovedContraption();

}
