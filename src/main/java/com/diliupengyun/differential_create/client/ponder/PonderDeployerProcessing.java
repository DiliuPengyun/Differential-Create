package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Inventory contact and shaft travel, rather than the storyboard, complete a stroke. */
final class PonderDeployerProcessing {
    private TransportedItemStack workpiece;
    private double travel;
    private boolean processed;

    void tick(DeployerBlockEntity hand) {
        if (hand.getBlockState().getValue(BlockStateProperties.FACING) != Direction.DOWN) return;
        var support = com.diliupengyun.differential_create.kinetics.PhysicsPreview.relative(hand.getLevel(), hand.getBlockPos(), new net.minecraft.core.BlockPos(0, -2, 0));
        var handler = BlockEntityBehaviour.get(hand.getLevel(), support,
            TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null) return;
        if (workpiece == null) {
            handler.handleCenteredProcessingOnAllItems(.03f, item -> {
                if (workpiece == null && !item.stack.isEmpty() && hand.getRecipe(item.stack) != null) {
                    workpiece = item; item.locked = true; travel = 0; processed = false;
                    phase(hand, "EXPANDING", 1000);
                }
                return TransportedResult.doNothing();
            });
        }
        if (workpiece == null) return;
        travel += PonderKinetics.of((net.createmod.ponder.api.level.PonderLevel) hand.getLevel()).workTravel(hand.getBlockPos());
        hand.setAnimatedOffset((float) Math.clamp(travel < 500 ? travel / 500 : (1000 - travel) / 500, 0, 1));
        if (!processed && travel >= 500) {
            hand.setAnimatedOffset(1);
            var recipe = hand.getRecipe(workpiece.stack);
            if (recipe == null) { workpiece.locked = false; workpiece = null; return; }
            var outputs = RecipeApplier.applyRecipeOn(hand.getLevel(), workpiece.stack.copyWithCount(1), recipe.value(), true)
                .stream().map(stack -> { var result = workpiece.copy(); result.stack = stack; result.locked = false; return result; }).toList();
            var left = workpiece.copy(); left.stack.shrink(1);
            handler.handleProcessingOnItem(workpiece, TransportedResult.convertToAndLeaveHeld(outputs, left));
            workpiece.clearFanProcessingData();
            if (!(recipe.value() instanceof ItemApplicationRecipe application && application.shouldKeepHeldItem())) {
                var data = hand.saveWithoutMetadata(hand.getLevel().registryAccess());
                var tool = ItemStack.parseOptional(hand.getLevel().registryAccess(), data.getCompound("HeldItem"));
                if (tool.isDamageableItem()) {
                    tool.setDamageValue(tool.getDamageValue() + 1);
                    if (tool.getDamageValue() >= tool.getMaxDamage()) tool.shrink(1);
                } else {
                    var remainder = tool.getCraftingRemainingItem(); tool.shrink(1);
                    if (tool.isEmpty()) tool = remainder;
                }
                data.put("HeldItem", tool.isEmpty() ? new net.minecraft.nbt.CompoundTag() : tool.save(hand.getLevel().registryAccess()));
                hand.loadWithComponents(data, hand.getLevel().registryAccess());
            }
            processed = true;
            phase(hand, "RETRACTING", 1000);
        }
        if (travel >= 1000) { workpiece.locked = false; workpiece = null; phase(hand, "WAITING", 0); }
    }
    private static void phase(DeployerBlockEntity hand, String state, int timer) {
        var data = hand.saveWithoutMetadata(hand.getLevel().registryAccess());
        data.putString("State", state); data.putInt("Timer", timer);
        hand.loadWithComponents(data, hand.getLevel().registryAccess());
    }
}
