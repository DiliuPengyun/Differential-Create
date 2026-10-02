package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FanWork;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import java.util.function.Function;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AirCurrent.class)
public abstract class AirCurrentMixin {
	@Shadow @Final public IAirCurrentSource source;
	@WrapOperation(method = "tickAffectedHandlers", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour;handleProcessingOnAllItems(Ljava/util/function/Function;)V"))
	private void differentialcreate$persistExposure(TransportedItemStackHandlerBehaviour handler,
		Function<TransportedItemStack, TransportedResult> processing, Operation<Void> original) {
		original.call(handler, processing);
		if (!handler.getWorld().isClientSide && source.getSpeed() != 0) handler.blockEntity.setChanged();
	}
	@WrapOperation(method = "tickAffectedEntities", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessing;applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z"))
	private boolean differentialcreate$entity(ItemEntity entity, FanProcessingType type, Operation<Boolean> original) {
		return FanWork.apply(entity, type, source.getSpeed());
	}
	@WrapOperation(method = "lambda$tickAffectedHandlers$0", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessing;applyProcessing(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour$TransportedResult;"))
	private TransportedResult differentialcreate$transported(TransportedItemStack stack, Level level,
		FanProcessingType type, Operation<TransportedResult> original) {
		return FanWork.apply(stack, level, type, source.getSpeed());
	}
}
