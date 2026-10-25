package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.mixin.PonderPressCallbacks;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;

/** Actual native belt/depot recipe callbacks on the displayed contact beneath a press. */
final class PonderPressProcessing {
    void tick(MechanicalPressBlockEntity press) {
        var world = (PonderLevel) press.getLevel(); var cycle = press.getPressingBehaviour();
        if (cycle.running && cycle.runningTicks >= PressingBehaviour.CYCLE) {
            cycle.running = false; cycle.finished = true; press.onPressingCompleted();
        }
        var support = PonderSimulation.of(world).relative(world, press.getBlockPos(), new BlockPos(0, -2, 0));
        var handler = BlockEntityBehaviour.get(world, support, TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null) return;
        handler.handleCenteredProcessingOnAllItems(.03f, item -> {
            if (press.tryProcessOnBelt(item, null, true)) {
                var result = cycle.running ? PonderPressCallbacks.differentialcreate$held(item, handler, cycle)
                    : PonderPressCallbacks.differentialcreate$receive(item, handler, cycle);
                item.locked = result == ProcessingResult.HOLD;
            } else if (cycle.runningTicks >= PressingBehaviour.CYCLE * 2 / 3) item.locked = false;
            return TransportedResult.doNothing();
        });
        if (world.getBlockEntity(support) instanceof DepotBlockEntity depot) PonderDepotOutput.stack(depot);
    }
}
