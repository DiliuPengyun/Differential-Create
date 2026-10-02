package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.diliupengyun.differential_create.kinetics.IMovementWork;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MovementContext.class)
public abstract class MovementContextMixin implements IMovementWork {
	@Unique private ContraptionActorWork differentialcreate$work;
	@Override public ContraptionActorWork differentialcreate$actorWork() {
		if (differentialcreate$work == null) differentialcreate$work = new ContraptionActorWork((MovementContext) (Object) this);
		return differentialcreate$work;
	}
}
