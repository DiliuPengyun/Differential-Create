package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.press.BeltPressingCallbacks;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BeltPressingCallbacks.class)
public interface PonderPressCallbacks {
    @Invoker("onItemReceived") static ProcessingResult differentialcreate$receive(TransportedItemStack item, TransportedItemStackHandlerBehaviour handler, PressingBehaviour press) { throw new AssertionError(); }
    @Invoker("whenItemHeld") static ProcessingResult differentialcreate$held(TransportedItemStack item, TransportedItemStackHandlerBehaviour handler, PressingBehaviour press) { throw new AssertionError(); }
}
