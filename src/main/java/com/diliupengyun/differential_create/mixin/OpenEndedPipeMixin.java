package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.fluids.FluidReactions;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.foundation.fluid.FluidHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OpenEndedPipe.class)
public abstract class OpenEndedPipeMixin {
    @Inject(method = "provideFluidToSpace", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$stageCollision(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        var self = (OpenEndedPipe) (Object) this;
        var world = self.getWorld();
        var output = self.getOutputPos();
        if (world == null || !world.isLoaded(output) || fluid.isEmpty()
            || !(fluid.getFluid() instanceof FlowingFluid) || !FluidHelper.hasBlockState(fluid.getFluid())) return;
        var state = world.getBlockState(output);
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED) && !state.canBeReplaced()) return;
        var outside = state.getFluidState();
        if (outside.isEmpty() || FluidHelper.convertToStill(outside.getType()) == fluid.getFluid()) return;

        // Planning may reserve real buffer space, but cannot post a collision
        // event or change the world. Execution is reached only with owned stock.
        if (simulate) {
            cir.setReturnValue(true);
            return;
        }
        FluidReactions.handlePipeSpillCollision(world, output, fluid.getFluid(), outside);
        // Commit a real discharge only when the registered reaction changed
        // the target; an unsupported or cancelled collision retains its stock.
        cir.setReturnValue(!world.getBlockState(output).equals(state));
    }
}
