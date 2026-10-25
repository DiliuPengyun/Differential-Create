package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

/** The native hoe/mining example applies its interaction only after a paid forward stroke. */
public final class PonderHandActions {
    private PonderHandActions() {}
    private static final class PreviewUse extends UseOnContext {
        PreviewUse(DeployerBlockEntity hand, BlockPos target, ItemStack tool) {
            super(hand.getLevel(), null, InteractionHand.MAIN_HAND, tool,
                new BlockHitResult(Vec3.atCenterOf(target).add(0, .5, 0), Direction.UP, target, false));
        }
    }
    public static void phase(DeployerBlockEntity hand, String state) {
        var data = hand.saveWithoutMetadata(hand.getLevel().registryAccess());
        data.putString("State", state); data.putInt("Timer", state.equals("WAITING") ? 0 : 1000);
        hand.loadWithComponents(data, hand.getLevel().registryAccess());
    }
    public static void contact(DeployerBlockEntity hand) {
        var world = hand.getLevel(); var data = hand.saveWithoutMetadata(world.registryAccess());
        var direction = hand.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        var target = com.diliupengyun.differential_create.kinetics.PhysicsPreview.relative(world, hand.getBlockPos(), BlockPos.ZERO.relative(direction, 2));
        var state = world.getBlockState(target);
        if (data.getString("Mode").equals("PUNCH")) {
            var level = (net.createmod.ponder.api.level.PonderLevel) world;
            PonderSimulation.of(level).punch(hand.getBlockPos(), target, state, 500);
            return;
        }
        var tool = ItemStack.parseOptional(world.registryAccess(), data.getCompound("HeldItem"));
        var modified = state.getToolModifiedState(new PreviewUse(hand, target, tool), ItemAbilities.HOE_TILL, true);
        if (modified == null || !world.getBlockState(target.above()).isAir()) return;
        world.setBlockAndUpdate(target, modified);
        if (tool.isDamageableItem()) { tool.setDamageValue(tool.getDamageValue() + 1); if (tool.getDamageValue() >= tool.getMaxDamage()) tool.shrink(1); }
        data.put("HeldItem", tool.isEmpty() ? new net.minecraft.nbt.CompoundTag() : tool.save(world.registryAccess()));
        hand.loadWithComponents(data, world.registryAccess());
    }
}
