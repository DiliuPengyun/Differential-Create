package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.fluids.FluidNetwork;
import com.simibubi.create.content.fluids.PipeConnection;
import net.createmod.catnip.math.BlockFace;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FluidNetwork.class)
public abstract class FluidNetworkMixin {
    @Shadow BlockFace start;
    @Shadow private PipeConnection get(BlockFace location) { throw new AssertionError(); }

    /** Create caches this rate when discovering the inlet; a coasting pump changes it every tick. */
    @Redirect(method = "tick", at = @At(value = "FIELD",
        target = "Lcom/simibubi/create/content/fluids/FluidNetwork;transferSpeed:I", opcode = Opcodes.GETFIELD))
    private int differentialcreate$liveTransferRate(FluidNetwork network) {
        PipeConnection inlet = get(start);
        float pressure = inlet == null ? 0 : inlet.getPressure().getFirst();
        return pressure > 0 ? (int) Math.max(1, pressure / 2f) : 0;
    }
}
