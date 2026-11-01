package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.observer.TrackObserverBlock;
import com.simibubi.create.content.trains.observer.TrackObserverBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RedStoneWireBlock;

/** The observer demonstration follows actual axle travel and its displayed cargo filter. */
final class PonderTrackObserverScene {
    static void tick(PonderLevel world) {
        if (world.scene == null || !world.scene.getId().getPath().equals("track_observer")) return;
        var pos = new BlockPos(5, 1, 4);
        if (!(world.getBlockEntity(pos) instanceof TrackObserverBlockEntity observer)) return;
        var filter = observer.getBehaviour(FilteringBehaviour.TYPE);
        double[] bounds = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        boolean[] matches = {filter == null || filter.getFilter().isEmpty()};
        world.scene.forEachVisible(WorldSectionElement.class, section -> {
            var selected = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selected == null) return;
            for (var block : selected) {
                var be = world.getBlockEntity(block);
                if (be instanceof AbstractBogeyBlockEntity && Math.abs(block.getZ() + section.getAnimatedOffset().z - 7) < .01) {
                    double x = block.getX() + .5 + section.getAnimatedOffset().x;
                    bounds[0] = Math.min(bounds[0], x); bounds[1] = Math.max(bounds[1], x);
                }
                if (be instanceof FluidTankBlockEntity tank && filter != null && filter.test(tank.getTankInventory().getFluid())) matches[0] = true;
            }
        });
        boolean powered = matches[0] && bounds[0] <= 5.5 && bounds[1] >= 5.5;
        var state = world.getBlockState(pos);
        if (state.getValue(TrackObserverBlock.POWERED) != powered)
            world.setBlock(pos, state.setValue(TrackObserverBlock.POWERED, powered), 2);
        for (int z : new int[]{2, 3}) {
            var wirePos = new BlockPos(5, 1, z); var wire = world.getBlockState(wirePos);
            if (wire.hasProperty(RedStoneWireBlock.POWER) && wire.getValue(RedStoneWireBlock.POWER) != (powered ? 15 : 0))
                world.setBlock(wirePos, wire.setValue(RedStoneWireBlock.POWER, powered ? 15 : 0), 2);
        }
    }
}
