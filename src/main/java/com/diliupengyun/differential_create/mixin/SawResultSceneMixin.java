package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.MechanicalSawScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.element.ElementLinkImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MechanicalSawScenes.class)
public abstract class SawResultSceneMixin {
    @WrapOperation(method = "processing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBeltLike(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void differentialcreate$reinsertOutput(CreateSceneBuilder.WorldInstructions world, BlockPos pos, net.minecraft.core.Direction side,
        ItemStack stack, Operation<Void> original, net.createmod.ponder.api.scene.SceneBuilder builder, net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        if (stack.is(net.minecraft.world.item.Items.OAK_LOG)) original.call(world, pos, side, stack);
        else com.diliupengyun.differential_create.client.ponder.PonderBeltInput.retrieve(new CreateSceneBuilder(builder), pos, side, stack);
    }
    @WrapOperation(method = {"treeCutting", "contraption", "processing"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<EntityElement> differentialcreate$nativeOutput(CreateSceneBuilder.WorldInstructions world,
        Vec3 pos, Vec3 motion, ItemStack stack, Operation<ElementLink<EntityElement>> original) {
        // The processing lesson's first log is delivered explicitly on the saw;
        // the other item entities were pre-timed products or their duplicates.
        return new ElementLinkImpl<>(EntityElement.class);
    }
    @WrapOperation(method = {"treeCutting", "contraption"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;replaceBlocks(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$treeAlgorithm(CreateSceneBuilder.WorldInstructions world, Selection selection,
        BlockState state, boolean particles, Operation<Void> original) { if (!state.isAir()) original.call(world, selection, state, particles); }
    @WrapOperation(method = {"treeCutting", "contraption"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;destroyBlock(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$treeBreak(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
}
