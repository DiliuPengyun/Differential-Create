package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.portable.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block-state entry point also serves add-on blocks with their own comparator implementation. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PortableInterfaceComparatorMixin {
    @Inject(method = "getAnalogOutputSignal", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$signal(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (level.getBlockEntity(pos) instanceof PortableEndpoint endpoint) cir.setReturnValue(endpoint.differentialcreate$docking().signal());
    }
    @Inject(method = "hasAnalogOutputSignal", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$hasSignal(CallbackInfoReturnable<Boolean> cir) {
        if (PortableDocking.endpointBlock(((BlockState) (Object) this).getBlock())) cir.setReturnValue(true);
    }
}
