package com.diliupengyun.differential_create.mixin;

import java.util.Optional;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.PipeConnection;
import net.createmod.catnip.data.Couple;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PipeConnection.class)
public abstract class PipeConnectionMixin implements IPipePressure {
    @Shadow Couple<Float> pressure;
    @Shadow Optional<FlowSource> source;
    @Shadow Optional<PipeConnection.Flow> flow;

    @Override public FlowSource differentialcreate$source() { return source.orElse(null); }
    @Override public void differentialcreate$showFlow(FluidStack identity, double outward) {
        boolean inbound = outward < 0;
        float rate = (float) Math.abs(outward * 2);
        pressure.setFirst(inbound ? rate : 0f);
        pressure.setSecond(inbound ? 0f : rate);
        if (identity.isEmpty()) {
            flow = Optional.empty();
            return;
        }
        PipeConnection self = (PipeConnection) (Object) this;
        PipeConnection.Flow shown = flow.orElse(null);
        if (shown == null || shown.inbound != inbound || !FluidStack.isSameFluidSameComponents(shown.fluid, identity))
            shown = self.new Flow(inbound, identity.copyWithAmount(1));
        shown.complete = true;
        shown.progress.setValue(1);
        flow = Optional.of(shown);
    }
    @Inject(method = "tickFlowProgress", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$stationaryPresentation(Level level, BlockPos pos, CallbackInfo ci) {
        if (pressure.getFirst() == 0 && pressure.getSecond() == 0) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, pos);
            if (pipe != null && !pipe.blockEntity.isVirtual()) ci.cancel();
        }
    }
}
