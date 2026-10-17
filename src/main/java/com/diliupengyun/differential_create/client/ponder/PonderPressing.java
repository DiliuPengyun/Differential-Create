package com.diliupengyun.differential_create.client.ponder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.VecHelper;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * One press working its depot the way Create does with bulkPressing off: a single ingot is
 * consumed per stroke, on the tick the head reaches it, and the sheet is set down on the rim.
 *
 * <p>Non-blocking, so idle/show alone drive the storyboard clock and keyframes stay exact.
 * Ponder's level is client-side, where the press never ends its cycle by itself; this ends it
 * like the server would and releases the permanent machine load.
 */
final class PonderPressing extends TickingInstruction {
    private static final MethodHandle OUTPUTS; // DepotBehaviour#processingOutputBuffer is package-private
    static {
        try {
            OUTPUTS = MethodHandles.privateLookupIn(DepotBehaviour.class, MethodHandles.lookup())
                .findGetter(DepotBehaviour.class, "processingOutputBuffer", ItemStackHandler.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final BlockPos pressPos, depotPos;
    private final int[] rimSlots;
    private final ItemStack batch, product;
    private ItemStack held = ItemStack.EMPTY;
    private boolean armed, busy, retired;
    private int made;

    private PonderPressing(int window, BlockPos pressPos, BlockPos depotPos, ItemStack batch, ItemStack product,
                           int[] rimSlots) {
        super(false, window);
        this.pressPos = pressPos.immutable();
        this.depotPos = depotPos.immutable();
        this.batch = batch.copy();
        this.product = product.copyWithCount(1);
        this.rimSlots = rimSlots.clone();
    }

    /** Load {@code batch}, then press it item by item; new strokes start only within {@code window}. */
    static void run(CreateSceneBuilder scene, int window, BlockPos press, BlockPos depot,
                    ItemStack batch, ItemStack product, int... rimSlots) {
        scene.addInstruction(new PonderPressing(window, press, depot, batch, product, rimSlots));
    }

    @Override
    public void reset(PonderScene scene) {
        super.reset(scene);
        held = ItemStack.EMPTY;
        armed = busy = retired = false;
        made = 0;
    }

    @Override
    public boolean isComplete() {
        return retired || super.isComplete() && !busy;
    }

    @Override
    public void tick(PonderScene scene) {
        boolean first = remainingTicks == totalTicks;
        super.tick(scene);
        if (retired) return;
        if (!(scene.getWorld().getBlockEntity(pressPos) instanceof MechanicalPressBlockEntity press)
            || !(scene.getWorld().getBlockEntity(depotPos) instanceof DepotBlockEntity tray)) {
            retired = true;
            return;
        }
        PressingBehaviour pressing = press.getPressingBehaviour();
        DepotBehaviour depot = tray.getBehaviour(DepotBehaviour.TYPE);
        ItemStackHandler rim = outputs(depot);
        if (first) {
            for (int slot = 0; slot < rim.getSlots(); slot++) rim.setStackInSlot(slot, ItemStack.EMPTY);
            place(depot, batch.copy());
            start(pressing);
            return;
        }
        if (depot.getHeldItemStack() != held) {
            retired = true; // a later run() owns this depot
            return;
        }
        if (!busy || pressing.runningTicks < PressingBehaviour.CYCLE / 2) return;
        if (armed) { // the virtual press reports the contact tick exactly once
            armed = false;
            pressing.makePressingParticleEffect(VecHelper.getCenterOf(depotPos).add(0, 8 / 16f, 0),
                batch.copyWithCount(1));
            if (held.getCount() > 1) {
                held.shrink(1);
                rim.insertItem(rimSlots[Math.min(made, rimSlots.length - 1)], product.copy(), false);
            } else place(depot, product.copy());
            made++;
        }
        if (pressing.runningTicks < PressingBehaviour.CYCLE) return;
        pressing.running = false; // the server-only end of cycle; releases the machine load
        pressing.finished = true;
        busy = false;
        // Shaft travel of 8 RPM-ticks advances one running tick; contact needs CYCLE / 2 of them.
        if (remainingTicks > 0 && ItemStack.isSameItem(held, batch)
            && Math.abs(press.getSpeed()) * remainingTicks >= 8 * PressingBehaviour.CYCLE / 2)
            start(pressing);
    }

    private void place(DepotBehaviour depot, ItemStack stack) {
        var item = new TransportedItemStack(stack);
        item.beltPosition = item.prevBeltPosition = .5f; // settled: the depot neither slides nor turns it
        item.angle = 200; // the constructor picks a random angle
        depot.setHeldItem(item);
        held = stack;
    }

    private void start(PressingBehaviour pressing) {
        pressing.start(PressingBehaviour.Mode.BELT);
        busy = armed = true;
    }

    private static ItemStackHandler outputs(DepotBehaviour depot) {
        try {
            return (ItemStackHandler) OUTPUTS.invokeExact(depot);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
