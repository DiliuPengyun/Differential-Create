package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChainConveyorBlockEntity.class)
public interface ChainConveyorAccessor {
    @Invoker("exportToPort") boolean differentialcreate$export(ChainConveyorPackage box, BlockPos port);
    @Invoker("notifyPortToAnticipate") void differentialcreate$anticipate(BlockPos port);
}
