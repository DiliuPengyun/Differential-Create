package com.diliupengyun.differential_create.kinetics.portable;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Resource adapters only. All geometry, permission, renewal and lifetime rules live in the shared endpoint. */
public final class DockingHandlers {
    private DockingHandlers() {}
    public record Items(IItemHandlerModifiable wrapped, DockingSession session, Object inventoryIdentity)
        implements IItemHandlerModifiable, DockingSession.Bound {
        private boolean slot(int slot) { return session.valid() && slot >= 0 && slot < wrapped.getSlots(); }
        @Override public int getSlots() { return session.valid() ? wrapped.getSlots() : 0; }
        @Override public ItemStack getStackInSlot(int slot) { return slot(slot) ? wrapped.getStackInSlot(slot).copy() : ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return slot(slot) ? wrapped.getSlotLimit(slot) : 0; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return slot(slot) && wrapped.isItemValid(slot, stack); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!slot(slot)) return stack;
            var result = wrapped.insertItem(slot, stack, simulate);
            if (!simulate && result.getCount() < stack.getCount()) session.transferred();
            return result;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!slot(slot)) return ItemStack.EMPTY;
            var result = wrapped.extractItem(slot, amount, simulate);
            if (!simulate && !result.isEmpty()) session.transferred();
            return result;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) {
            if (!slot(slot)) return;
            boolean changed = !ItemStack.matches(wrapped.getStackInSlot(slot), stack);
            wrapped.setStackInSlot(slot, stack);
            if (changed) session.transferred();
        }
    }
    public record Fluids(IFluidHandler wrapped, DockingSession session, Object inventoryIdentity)
        implements IFluidHandler, DockingSession.Bound {
        private boolean tank(int tank) { return session.valid() && tank >= 0 && tank < wrapped.getTanks(); }
        @Override public int getTanks() { return session.valid() ? wrapped.getTanks() : 0; }
        @Override public FluidStack getFluidInTank(int tank) { return tank(tank) ? wrapped.getFluidInTank(tank).copy() : FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return tank(tank) ? wrapped.getTankCapacity(tank) : 0; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return tank(tank) && wrapped.isFluidValid(tank, stack); }
        @Override public int fill(FluidStack stack, FluidAction action) {
            if (!session.valid()) return 0;
            int result = wrapped.fill(stack, action); if (action.execute() && result > 0) session.transferred(); return result;
        }
        @Override public FluidStack drain(FluidStack stack, FluidAction action) {
            if (!session.valid()) return FluidStack.EMPTY;
            var result = wrapped.drain(stack, action); if (action.execute() && !result.isEmpty()) session.transferred(); return result;
        }
        @Override public FluidStack drain(int amount, FluidAction action) {
            if (!session.valid()) return FluidStack.EMPTY;
            var result = wrapped.drain(amount, action); if (action.execute() && !result.isEmpty()) session.transferred(); return result;
        }
    }
}
