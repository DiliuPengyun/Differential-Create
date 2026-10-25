package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.MovementActorScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.element.ElementLinkImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Moving tools and portable interfaces use actual operations in the native compositions. */
@Mixin(MovementActorScenes.class)
public abstract class MovementActorSceneMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "psiRedstone", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$redstoneLesson(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.diliupengyun.differential_create.client.ponder.PonderInterfaceRedstoneScene.redstone(builder, util);
        ci.cancel();
    }

    @WrapOperation(method = {"harvester", "contraptionControls"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$toolCutsCrop(CreateSceneBuilder.WorldInstructions world, BlockPos pos, BlockState state,
        boolean particles, Operation<Void> original) {}
    @WrapOperation(method = "plough", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;destroyBlock(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$toolBreaksBlock(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = "plough", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlocks(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$toolTillsGround(CreateSceneBuilder.WorldInstructions world, Selection selection, BlockState state,
        boolean particles, Operation<Void> original) {
        if (!state.is(Blocks.FARMLAND)) original.call(world, selection, state, particles);
    }
    @WrapOperation(method = "contraptionControls", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;incrementBlockBreakingProgress(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$paidCutting(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = {"harvester", "plough", "contraptionControls"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<EntityElement> differentialcreate$noInventedDrops(CreateSceneBuilder.WorldInstructions world,
        Vec3 pos, Vec3 motion, ItemStack stack, Operation<ElementLink<EntityElement>> original) { return new ElementLinkImpl<>(EntityElement.class); }
    @WrapOperation(method = "psiTransfer", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<com.simibubi.create.foundation.ponder.element.BeltItemElement> differentialcreate$actualDockOutput(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, net.minecraft.core.Direction face, ItemStack stack, Operation<ElementLink<com.simibubi.create.foundation.ponder.element.BeltItemElement>> original) { return new ElementLinkImpl<>(com.simibubi.create.foundation.ponder.element.BeltItemElement.class); }
    @WrapOperation(method = "psiTransfer", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyEntity(Lnet/createmod/ponder/api/element/ElementLink;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$actualHopperInput(CreateSceneBuilder.WorldInstructions world,
        ElementLink<?> link, Consumer<?> action, Operation<Void> original) {}
}
