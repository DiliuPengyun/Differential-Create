package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DeployerMovementBehaviour.class)
public interface DeployerMovementAccessor {
    @Invoker("getPlayer") DeployerFakePlayer differentialcreate$player(MovementContext context);
    @Invoker("tryGrabbingItem") void differentialcreate$grab(MovementContext context);
}
