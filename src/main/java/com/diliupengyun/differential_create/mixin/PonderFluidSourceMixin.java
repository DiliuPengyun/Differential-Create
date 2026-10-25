package com.diliupengyun.differential_create.mixin;

import java.util.Optional;
import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.content.fluids.*;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipeConnection.class)
public abstract class PonderFluidSourceMixin {
    @Shadow @Final Direction side;
    @Shadow Optional<FlowSource> source;
    @Inject(method = "determineSource", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$displayedSource(Level world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!PhysicsPreview.enabled(world)) return;
        if (world instanceof net.createmod.ponder.api.level.PonderLevel preview) {
            var boundary = com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(preview).fluidBoundary(pos, side);
            if (boundary != null) {
                source = Optional.of(boundary);
                cir.setReturnValue(true);
                return;
            }
        }
        var neighbor = PhysicsPreview.neighbor(world, pos, side);
        var location = new BlockFace(neighbor.relative(side.getOpposite()), side);
        if (FluidPropagator.isOpenEnd(world, pos, side)) {
            if (!(source.orElse(null) instanceof OpenEndedPipe end) || !end.getOutputPos().equals(neighbor))
                source = Optional.of(new OpenEndedPipe(location));
        } else if (FluidPropagator.hasFluidCapability(world, neighbor, side.getOpposite()))
            source = Optional.of(new FlowSource.FluidHandler(location));
        else source = Optional.of(FluidPropagator.getPipe(world, neighbor) == null ? new FlowSource.Blocked(location) : new FlowSource.OtherPipe(location));
        cir.setReturnValue(true);
    }
}
