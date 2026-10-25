package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Opted-in lessons run actual airflow/recipe processing on virtual depots and belts. */
@Mixin(AirCurrent.class)
public abstract class PonderAirCurrentMixin {
    @Shadow @Final public IAirCurrentSource source;
    @WrapOperation(method = "tickAffectedEntities", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessing;applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z"))
    private boolean differentialcreate$actualEntityParticles(net.minecraft.world.entity.item.ItemEntity item,
        com.simibubi.create.content.kinetics.fan.processing.FanProcessingType type, Operation<Boolean> original) {
        if (item.level() instanceof PonderLevel) type.spawnProcessingParticles(item.level(), item.position());
        return original.call(item, type);
    }
    @WrapOperation(method = "lambda$tickAffectedHandlers$0", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessing;applyProcessing(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour$TransportedResult;"))
    private com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult differentialcreate$actualHeldParticles(
        com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack item, Level world,
        com.simibubi.create.content.kinetics.fan.processing.FanProcessingType type,
        Operation<com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour handler) {
        if (world instanceof PonderLevel preview && (item.processedBy != type || item.processingTime >= 0) && type.canProcess(item.stack, world)) {
            var position = com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(preview).point(handler.getPos())
                .add(handler.getWorldPositionOf(item).subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(handler.getPos())));
            type.spawnProcessingParticles(world, position);
        }
        return original.call(item, world, type);
    }
    @WrapOperation(method = {"rebuild", "findAffectedHandlers"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;getAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;"))
    private com.simibubi.create.content.kinetics.fan.processing.FanProcessingType differentialcreate$displayedProcess(Level world,
        net.minecraft.core.BlockPos target, Operation<com.simibubi.create.content.kinetics.fan.processing.FanProcessingType> original) {
        return original.call(world, com.diliupengyun.differential_create.kinetics.PhysicsPreview.relative(world, source.getAirCurrentPos(), target.subtract(source.getAirCurrentPos())));
    }
    @WrapOperation(method = "getFlowLimit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static net.minecraft.world.level.block.state.BlockState differentialcreate$displayedObstacle(Level world,
        net.minecraft.core.BlockPos target, Operation<net.minecraft.world.level.block.state.BlockState> original,
        Level ignored, net.minecraft.core.BlockPos start, float distance, net.minecraft.core.Direction facing) {
        return original.call(world, com.diliupengyun.differential_create.kinetics.PhysicsPreview.relative(world, start, target.subtract(start)));
    }

    @ModifyArg(method = "findAffectedHandlers", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;get(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"), index = 1)
    private net.minecraft.core.BlockPos differentialcreate$displayedHandler(net.minecraft.core.BlockPos target) {
        return com.diliupengyun.differential_create.kinetics.PhysicsPreview.relative(source.getAirCurrentWorld(),
            source.getAirCurrentPos(), target.subtract(source.getAirCurrentPos()));
    }

    @WrapOperation(method = {"lambda$tickAffectedHandlers$0", "tickAffectedEntities"}, at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$processVirtualItems(Level world, Operation<Boolean> original) {
        return original.call(world) && !(world instanceof PonderLevel);
    }
}
