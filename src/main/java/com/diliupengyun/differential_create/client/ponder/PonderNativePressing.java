package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayList;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Keep the original press tutorial's construction and inputs, but process actual workpieces. */
public final class PonderNativePressing extends TickingInstruction {
    private final BlockPos pressPos, depotPos;
    private TransportedItemStack workpiece;
    private boolean contact;
    private int completed;

    public PonderNativePressing(BlockPos press, BlockPos depot) {
        super(false, 600);
        pressPos = press.immutable();
        depotPos = depot.immutable();
    }

    @Override public void reset(PonderScene scene) {
        super.reset(scene);
        workpiece = null;
        contact = false;
        completed = 0;
    }

    @Override public void tick(PonderScene scene) {
        super.tick(scene);
        if (!(scene.getWorld().getBlockEntity(pressPos) instanceof MechanicalPressBlockEntity press)) return;
        var pressing = press.getPressingBehaviour();
        if (workpiece == null) {
            for (var support : new BlockPos[]{depotPos, pressPos.below(2)}) {
                if (!(scene.getWorld().getBlockEntity(support) instanceof SmartBlockEntity be)) continue;
                var transport = be.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
                if (transport == null) continue;
                transport.handleCenteredProcessingOnAllItems(.03f, item -> {
                    if (workpiece == null && item.stack.getCount() == 1
                        && press.tryProcessOnBelt(item, null, true)) {
                        workpiece = item;
                    }
                    return TransportedResult.doNothing();
                });
                if (workpiece != null) break;
            }
            if (workpiece == null) return;
            contact = false;
            workpiece.locked = true;
            pressing.start(PressingBehaviour.Mode.BELT);
            return;
        }
        if (!contact && pressing.runningTicks >= PressingBehaviour.CYCLE / 2) {
            var products = new ArrayList<ItemStack>();
            ItemStack input = workpiece.stack.copy();
            if (press.tryProcessOnBelt(workpiece, products, false) && products.size() == 1) {
                // Change the existing item. No recipe input means no output can be manufactured.
                workpiece.stack = products.getFirst().copy();
                workpiece.clearFanProcessingData();
                pressing.makePressingParticleEffect(Vec3.atCenterOf(pressPos.below(2)).add(0, .5, 0), input);
                completed++;
            }
            contact = true;
        }
        if (contact && pressing.runningTicks >= PressingBehaviour.CYCLE * 2 / 3)
            workpiece.locked = false;
        if (pressing.runningTicks < PressingBehaviour.CYCLE) return;
        pressing.running = false;
        pressing.finished = true;
        workpiece.locked = false;
        workpiece = null;
        if (completed >= 3) remainingTicks = 0;
    }
}
