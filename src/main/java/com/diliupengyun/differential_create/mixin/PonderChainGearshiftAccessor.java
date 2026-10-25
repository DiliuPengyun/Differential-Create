package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.chainDrive.ChainGearshiftBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The virtual Ponder world does not run the server's redstone neighbour updates. */
@Mixin(ChainGearshiftBlockEntity.class)
public interface PonderChainGearshiftAccessor {
    @Accessor("signal") void differentialcreate$setSignal(int signal);
    @Accessor("signal") int differentialcreate$getSignal();
}
