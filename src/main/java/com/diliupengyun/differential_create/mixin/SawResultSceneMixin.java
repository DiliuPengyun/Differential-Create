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
    @WrapOperation(method = "processing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<EntityElement> differentialcreate$trackProcessingMaterial(CreateSceneBuilder.WorldInstructions world,
        Vec3 pos, Vec3 motion, ItemStack stack, Operation<ElementLink<EntityElement>> original,
        net.createmod.ponder.api.scene.SceneBuilder builder, net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        // The first log is the input the native pickup/throw demonstrates. Later
        // handles must refer to real ejected results, so the original return and
        // removal gestures operate on those items rather than on empty links.
        return stack.is(net.minecraft.world.item.Items.OAK_LOG) ? original.call(world, pos, motion, stack)
            : com.diliupengyun.differential_create.client.ponder.PonderBeltInput.output(new CreateSceneBuilder(builder), stack);
    }
    @WrapOperation(method = "processing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyEntity(Lnet/createmod/ponder/api/element/ElementLink;Ljava/util/function/Consumer;)V", ordinal = 3))
    private static void differentialcreate$returnOwnsMaterial(CreateSceneBuilder.WorldInstructions world,
        ElementLink<EntityElement> link, java.util.function.Consumer<net.minecraft.world.entity.Entity> action, Operation<Void> original) {
        // The retrying insertion consumes the thrown stripped log only when the
        // real saw accepts it. The native pre-timed discard would lose that input.
    }
    @WrapOperation(method = "treeCutting", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;hideSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$removeDemonstratedRoots(CreateSceneBuilder.WorldInstructions world,
        Selection selection, net.minecraft.core.Direction direction, Operation<Void> original,
        net.createmod.ponder.api.scene.SceneBuilder builder, net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        original.call(world, selection, direction);
        var lastRoot = new BlockPos(2, 1, 2);
        var removedRoots = java.util.List.of(lastRoot.west(), lastRoot.south(), lastRoot.west().south());
        if (direction != net.minecraft.core.Direction.WEST || selection.test(lastRoot)
            || removedRoots.stream().anyMatch(pos -> !selection.test(pos))) return;
        // The native diagram removes three supporting roots before explaining
        // the last cut. Hiding their models alone leaves the real tree supported.
        builder.addInstruction(new net.createmod.ponder.foundation.instruction.TickingInstruction(false, 80) {
            @Override public void tick(net.createmod.ponder.foundation.PonderScene scene) {
                super.tick(scene);
                var preview = (com.diliupengyun.differential_create.kinetics.PhysicsPreview) scene.getWorld();
                if (removedRoots.stream().anyMatch(pos -> scene.getWorld().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG)
                    && preview.differentialcreate$physical(pos))) return;
                for (var pos : removedRoots)
                    if (scene.getWorld().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG))
                        scene.getWorld().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                remainingTicks = 0;
            }
        });
    }
    @WrapOperation(method = {"treeCutting", "contraption"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<EntityElement> differentialcreate$nativeOutput(CreateSceneBuilder.WorldInstructions world,
        Vec3 pos, Vec3 motion, ItemStack stack, Operation<ElementLink<EntityElement>> original) {
        // The tree-cutting algorithm owns its drops.
        return new ElementLinkImpl<>(EntityElement.class);
    }
    @WrapOperation(method = {"treeCutting", "contraption"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;replaceBlocks(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$treeAlgorithm(CreateSceneBuilder.WorldInstructions world, Selection selection,
        BlockState state, boolean particles, Operation<Void> original) { if (!state.isAir()) original.call(world, selection, state, particles); }
    @WrapOperation(method = {"treeCutting", "contraption"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;destroyBlock(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$treeBreak(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
}
