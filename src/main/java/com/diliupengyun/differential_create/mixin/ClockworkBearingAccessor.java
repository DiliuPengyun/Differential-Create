package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClockworkBearingBlockEntity.class)
public interface ClockworkBearingAccessor {
	@Accessor("hourHand") ControlledContraptionEntity differentialcreate$hour();
	@Accessor("minuteHand") ControlledContraptionEntity differentialcreate$minute();
	@Accessor("hourAngle") float differentialcreate$hourAngle();
	@Accessor("hourAngle") void differentialcreate$hourAngle(float value);
	@Accessor("minuteAngle") float differentialcreate$minuteAngle();
	@Accessor("minuteAngle") void differentialcreate$minuteAngle(float value);
}
