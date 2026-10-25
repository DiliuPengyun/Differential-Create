package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.ArmScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.foundation.element.ElementLinkImpl;
import com.simibubi.create.foundation.ponder.element.BeltItemElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Operator supplies stay scripted; pickup, placement and crafting use actual inventories. */
@Mixin(ArmScenes.class)
public abstract class ArmResultSceneMixin {
    @WrapOperation(method = {"setup", "modes", "redstone"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;removeItemsFromBelt(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$actualPickup(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBeltLike(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void differentialcreate$actualPlacement(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Direction side, ItemStack stack, Operation<Void> original,
        net.createmod.ponder.api.scene.SceneBuilder builder, net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        if (side != Direction.UP) {
            var output = stack.is(net.minecraft.world.item.Items.COPPER_INGOT) ? new BlockPos(0, 2, 1)
                : stack.is(net.minecraft.world.item.Items.COPPER_BLOCK) ? new BlockPos(1, 1, 0) : new BlockPos(0, 2, 2);
            world.modifyBlockEntity(new BlockPos(2, 1, 2), com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.class,
                arm -> ((com.diliupengyun.differential_create.client.ponder.PonderArmConfiguration) arm).differentialcreate$configure(java.util.List.of(pos), java.util.List.of(output)));
            original.call(world, pos, side, stack);
        }
        else builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene ->
            scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity depot
                && ItemStack.isSameItemSameComponents(depot.getHeldItem(), stack), 4000));
    }
    @WrapOperation(method = {"modes", "redstone"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<BeltItemElement> differentialcreate$actualBeltPlacement(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Direction side, ItemStack stack, Operation<ElementLink<BeltItemElement>> original) {
        return new ElementLinkImpl<>(BeltItemElement.class);
    }
    @WrapOperation(method = "filtering", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$actualCrafterSupply(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Class<?> type,
        Consumer<?> action, Operation<Void> original) {}
}
