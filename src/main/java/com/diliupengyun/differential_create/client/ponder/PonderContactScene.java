package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.redstone.contact.RedstoneContactBlock;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.phys.Vec3;

/** The contact lesson's output follows the moving contact, not the script's old angles. */
final class PonderContactScene {
    static void tick(PonderLevel world) {
        if (world.scene == null || !world.scene.getId().getPath().equals("redstone_contact")) return;
        var moving = new BlockPos(1, 2, 2); var fixed = new BlockPos(1, 1, 2);
        if (!PonderSimulation.of(world).mountedBlock(moving)) return;
        var movingState = world.getBlockState(moving); var fixedState = world.getBlockState(fixed);
        if (!(movingState.getBlock() instanceof RedstoneContactBlock) || !(fixedState.getBlock() instanceof RedstoneContactBlock)) return;
        boolean[] touching = {false};
        world.scene.forEachVisible(WorldSectionElement.class, section -> {
            var selected = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selected == null || !selected.test(moving)) return;
            var pose = new PoseStack();
            ((WorldSectionElementImpl) section).transformMS(pose, 1);
            var facing = Vec3.atLowerCornerOf(movingState.getValue(RedstoneContactBlock.FACING).getNormal());
            // Same active point and nearest-facing rule as Create's ContactMovementBehaviour.
            var point = pose.last().pose().transformPosition(Vec3.atCenterOf(moving).add(facing.scale(.65f)).toVector3f());
            var normal = pose.last().pose().transformDirection(facing.toVector3f());
            touching[0] |= BlockPos.containing(point.x, point.y, point.z).equals(fixed)
                && fixedState.getValue(RedstoneContactBlock.FACING) == Direction.getNearest(normal.x, normal.y, normal.z).getOpposite();
        });
        for (var pos : java.util.List.of(moving, fixed)) {
            var state = world.getBlockState(pos);
            if (state.getValue(RedstoneContactBlock.POWERED) != touching[0])
                world.setBlock(pos, state.setValue(RedstoneContactBlock.POWERED, touching[0]), 2);
        }
        for (var pos : java.util.List.of(new BlockPos(1, 1, 0), new BlockPos(1, 1, 1))) {
            var state = world.getBlockState(pos);
            if (state.hasProperty(RedStoneWireBlock.POWER) && state.getValue(RedStoneWireBlock.POWER) != (touching[0] ? 15 : 0))
                world.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, touching[0] ? 15 : 0), 2);
        }
    }
}
