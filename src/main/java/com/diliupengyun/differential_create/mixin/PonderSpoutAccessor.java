package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpoutBlockEntity.class)
public interface PonderSpoutAccessor {
    @Invoker("whenItemHeld") ProcessingResult differentialcreate$fill(TransportedItemStack item, TransportedItemStackHandlerBehaviour handler);
}
