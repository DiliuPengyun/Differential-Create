package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LinearActuatorBlockEntity.class)
public interface LinearActuatorAccessor {
	@Accessor("sequencedOffsetLimit") double differentialcreate$offsetLimit();
	@Accessor("waitingForSpeedChange") boolean differentialcreate$waiting();
	@Invoker("isPassive") boolean differentialcreate$passive();
	@Invoker("toMotionVector") net.minecraft.world.phys.Vec3 differentialcreate$toMotionVector(float speed);
	@Invoker("getExtensionRange") int differentialcreate$extensionRange();
	@Invoker("assemble") void differentialcreate$assemble() throws com.simibubi.create.content.contraptions.AssemblyException;
}
