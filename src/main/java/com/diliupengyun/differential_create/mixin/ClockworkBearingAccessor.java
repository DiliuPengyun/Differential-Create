package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;

@Mixin(ClockworkBearingBlockEntity.class)
public interface ClockworkBearingAccessor {
	@Accessor("hourHand") ControlledContraptionEntity differentialcreate$hour();
	@Accessor("minuteHand") ControlledContraptionEntity differentialcreate$minute();
	@Accessor("hourAngle") float differentialcreate$hourAngle();
	@Accessor("hourAngle") void differentialcreate$hourAngle(float value);
	@Accessor("minuteAngle") float differentialcreate$minuteAngle();
	@Accessor("minuteAngle") void differentialcreate$minuteAngle(float value);
	@Accessor("operationMode") ScrollOptionBehaviour<?> differentialcreate$operationMode();
	@Invoker("getHourTarget") float differentialcreate$hourTarget(boolean cycle24);
	@Invoker("getMinuteTarget") float differentialcreate$minuteTarget();
	@Invoker("applyRotations") void differentialcreate$applyRotations();
}
