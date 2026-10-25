package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderBeltInput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.foundation.ponder.element.BeltItemElement;
import com.simibubi.create.infrastructure.ponder.scenes.DeployerScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve the native polishing demonstration while conserving its delivered workpieces. */
@Mixin(DeployerScenes.class)
public abstract class DeployerSceneMixin {
    @WrapOperation(method = "processing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<BeltItemElement> differentialcreate$beltMaterial(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Direction side, ItemStack stack, Operation<ElementLink<BeltItemElement>> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder);
        return stack.is(AllItems.POLISHED_ROSE_QUARTZ.get())
            ? PonderBeltInput.convert(scene, pos, AllItems.ROSE_QUARTZ.get(), stack)
            : PonderBeltInput.insert(scene, pos, side, stack);
    }

    @WrapOperation(method = "processing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBeltLike(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void differentialcreate$depotMaterial(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Direction side, ItemStack stack, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        if (stack.is(AllItems.POLISHED_ROSE_QUARTZ.get()))
            PonderBeltInput.convert(new CreateSceneBuilder(builder), pos, AllItems.ROSE_QUARTZ.get(), stack);
        else original.call(world, pos, side, stack);
    }

    @WrapOperation(method = "processing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;removeItemsFromBelt(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$retainWorkpiece(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Operation<Void> original) {}

    @WrapOperation(method = "processing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;stallBeltItem(Lnet/createmod/ponder/api/element/ElementLink;Z)V"))
    private static void differentialcreate$holdAtWorkstation(CreateSceneBuilder.WorldInstructions world,
        ElementLink<BeltItemElement> link, boolean stalled, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        if (stalled) PonderBeltInput.holdAt(new CreateSceneBuilder(builder), new BlockPos(2, 1, 2), link);
        else original.call(world, link, false);
    }
}
