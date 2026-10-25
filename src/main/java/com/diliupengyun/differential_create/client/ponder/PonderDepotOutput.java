package com.diliupengyun.differential_create.client.ponder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Combine actual recipe outputs already placed on the depot's rim. */
final class PonderDepotOutput {
    private static final int[] RIM_ORDER = {4, 3, 5, 2, 6, 1, 7, 0};
    private static final MethodHandle OUTPUTS;
    static {
        try {
            OUTPUTS = MethodHandles.privateLookupIn(DepotBehaviour.class, MethodHandles.lookup())
                .findGetter(DepotBehaviour.class, "processingOutputBuffer", ItemStackHandler.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static ItemStackHandler buffer(DepotBehaviour depot) {
        try {
            return (ItemStackHandler) OUTPUTS.invokeExact(depot);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void stack(DepotBlockEntity tray) {
        var depot = tray.getBehaviour(DepotBehaviour.TYPE);
        var output = buffer(depot);
        if (output.getSlots() != RIM_ORDER.length) return;
        ItemStack prototype = ItemStack.EMPTY;
        int total = 0;
        for (int slot = 0; slot < output.getSlots(); slot++) {
            var stack = output.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (prototype.isEmpty()) prototype = stack;
            else if (!ItemStack.isSameItemSameComponents(prototype, stack)) return;
            total += stack.getCount();
        }
        if (prototype.isEmpty()) return;
        int limit = Math.min(prototype.getMaxStackSize(), output.getSlotLimit(RIM_ORDER[0]));
        if (total == 0) return;

        // Fill one rim stack before using overflow slots. The native callback's
        // final product remains in the center, exactly where it was pressed.
        for (int i = 0; i < RIM_ORDER.length; i++) {
            int count = Math.min(limit, total);
            total -= count;
            var stack = count == 0 ? ItemStack.EMPTY : prototype.copyWithCount(count);
            int slot = RIM_ORDER[i];
            if (!ItemStack.matches(output.getStackInSlot(slot), stack)) output.setStackInSlot(slot, stack);
        }
    }
}
