package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import java.util.function.Function;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Persist Create's native timer even when a processing tick has no output yet. */
@Mixin(AirCurrent.class)
public abstract class AirCurrentMixin {
	@Shadow @Final public IAirCurrentSource source;
	@WrapOperation(method = "tickAffectedHandlers", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour;handleProcessingOnAllItems(Ljava/util/function/Function;)V"))
	private void differentialcreate$persistProcessing(TransportedItemStackHandlerBehaviour handler,
		Function<TransportedItemStack, TransportedResult> processing, Operation<Void> original) {
		original.call(handler, processing);
		if (!handler.getWorld().isClientSide && source.getSpeed() != 0) handler.blockEntity.setChanged();
	}
}
