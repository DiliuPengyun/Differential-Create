package com.diliupengyun.differential_create.mixin;

import java.util.function.Supplier;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the same endpoint used by native hose extraction and deposition. */
@Mixin(HosePulleyFluidHandler.class)
public interface HosePulleyFluidHandlerAccessor {
    @Accessor("rootPosGetter")
    Supplier<BlockPos> differentialcreate$rootPosition();
}
