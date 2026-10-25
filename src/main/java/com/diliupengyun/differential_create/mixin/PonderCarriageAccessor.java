package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CarriageContraptionEntity.class)
public interface PonderCarriageAccessor {
    @Accessor("carriage") void differentialcreate$carriage(Carriage carriage);
}
