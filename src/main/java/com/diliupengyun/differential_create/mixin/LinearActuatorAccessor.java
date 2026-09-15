package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LinearActuatorBlockEntity.class)
public interface LinearActuatorAccessor {
	@Accessor("waitingForSpeedChange") boolean differentialcreate$waiting();
	@Invoker("isPassive") boolean differentialcreate$passive();
}
