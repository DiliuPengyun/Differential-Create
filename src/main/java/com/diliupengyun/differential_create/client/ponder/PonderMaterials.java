package com.diliupengyun.differential_create.client.ponder;

import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Explicit starting material for native roller examples whose decorative chest was empty. */
final class PonderMaterials {
    private PonderMaterials() {}
    static void stock(PonderLevel world, Iterable<net.minecraft.core.BlockPos> selected) {
        ItemStack material = ItemStack.EMPTY;
        for (var pos : selected) if (world.getBlockEntity(pos) instanceof com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity roller) {
            var filter = roller.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
            if (filter != null && !filter.getFilter().isEmpty()) { material = filter.getFilter().copyWithCount(192); break; }
        }
        if (material.isEmpty()) return;
        var input = material;
        for (var cursor : selected) {
            var pos = cursor.immutable();
            if (!(world.getBlockEntity(pos) instanceof net.minecraft.world.Container)) continue;
            var inventory = world.getCapability(Capabilities.ItemHandler.BLOCK, pos, null); if (inventory == null) continue;
            PonderSimulation.of(world).initializeMaterial(pos, input.getItem(), () -> {
                for (int i = 0; i < inventory.getSlots(); i++) if (ItemStack.isSameItemSameComponents(inventory.getStackInSlot(i), input)) return;
                ItemHandlerHelper.insertItemStacked(inventory, input.copy(), false);
            });
            return;
        }
    }
}
