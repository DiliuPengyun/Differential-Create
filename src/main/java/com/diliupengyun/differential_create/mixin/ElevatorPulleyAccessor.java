package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ElevatorPulleyBlockEntity.class)
public interface ElevatorPulleyAccessor {
    @Invoker("getTargetOffset") int differentialcreate$targetOffset();
}
