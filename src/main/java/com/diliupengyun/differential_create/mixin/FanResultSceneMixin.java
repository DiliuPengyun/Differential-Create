package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.FanScenes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FanScenes.class)
public abstract class FanResultSceneMixin {
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "processing", at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/ItemLike;I)Lnet/minecraft/world/item/ItemStack;"))
    private static net.minecraft.world.item.ItemStack differentialcreate$probabilisticOutputCue(net.minecraft.world.item.ItemStack stack) {
        if (stack.is(net.minecraft.world.item.Items.GOLD_NUGGET)) stack.setCount(1);
        return stack;
    }
    @WrapOperation(method = "processing", at = {
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyEntities(Ljava/lang/Class;Ljava/util/function/Consumer;)V", ordinal = 0),
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyEntities(Ljava/lang/Class;Ljava/util/function/Consumer;)V", ordinal = 2)})
    private static void differentialcreate$realRecipe(CreateSceneBuilder.WorldInstructions world, Class<? extends Entity> type,
        Consumer<?> action, Operation<Void> original, net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        builder.idle(80);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene ->
            scene.getWorld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(-100, -10, -100, 100, 100, 100)).stream()
                .noneMatch(e -> e.getItem().is(net.minecraft.world.item.Items.GOLD_ORE) || e.getItem().is(net.minecraft.world.item.Items.RED_SAND)), 4000));
    }
    @WrapOperation(method = "processing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntityNBT(Lnet/createmod/ponder/api/scene/Selection;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$depotCompletion(CreateSceneBuilder.WorldInstructions world,
        net.createmod.ponder.api.scene.Selection selection, Class<?> type, Consumer<?> action, Operation<Void> original,
        net.createmod.ponder.api.scene.SceneBuilder builder, net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        builder.idle(130);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene -> {
            for (var pos : selection) if (scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity depot
                && !depot.getHeldItem().is(net.minecraft.world.item.Items.SAND)) return true;
            return false;
        }, 4000));
    }
    @WrapOperation(method = "processing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static net.createmod.ponder.api.element.ElementLink<com.simibubi.create.foundation.ponder.element.BeltItemElement> differentialcreate$allowExposure(
        CreateSceneBuilder.WorldInstructions world, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side,
        net.minecraft.world.item.ItemStack stack, Operation<net.createmod.ponder.api.element.ElementLink<com.simibubi.create.foundation.ponder.element.BeltItemElement>> original,
        net.createmod.ponder.api.scene.SceneBuilder builder, net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        world.modifyKineticSpeed(util.select().position(1, 2, 4), rpm -> Math.copySign(2f, rpm));
        return com.diliupengyun.differential_create.client.ponder.PonderBeltInput.insert(new CreateSceneBuilder(builder), pos, side, stack);
    }
    @WrapOperation(method = "processing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$EffectInstructions;emitParticles(Lnet/minecraft/world/phys/Vec3;Lnet/createmod/ponder/api/ParticleEmitter;FI)V"))
    private static void differentialcreate$actualParticles(CreateSceneBuilder.EffectInstructions effects, net.minecraft.world.phys.Vec3 pos,
        net.createmod.ponder.api.ParticleEmitter emitter, float count, int duration, Operation<Void> original) {}
}
