package com.diliupengyun.differential_create.kinetics;

import java.util.List;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.world.item.ItemStack;

/** The native item list stays authoritative; each entry belongs to a fixed launch trajectory. */
public interface IEjectorDynamics {
    EjectorCharge differentialcreate$charge();
    List<IntAttached<ItemStack>> differentialcreate$launchedItems();
    EjectorFlight differentialcreate$flight(IntAttached<ItemStack> item);
}
