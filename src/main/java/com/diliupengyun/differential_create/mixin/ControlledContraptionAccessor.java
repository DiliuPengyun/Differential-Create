package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ControlledContraptionEntity.class)
public interface ControlledContraptionAccessor {
	@Invoker("getController") IControlContraption differentialcreate$controller();
}
