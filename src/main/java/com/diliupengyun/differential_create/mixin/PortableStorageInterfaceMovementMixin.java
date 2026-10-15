package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.portable.PortableDocking;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceMovement;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PortableStorageInterfaceMovement.class)
public abstract class PortableStorageInterfaceMovementMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$discover(MovementContext context, BlockPos pos, CallbackInfo ci) {
        if (!context.world.isClientSide && PortableDocking.managed(context)) { PortableDocking.tick(context); ci.cancel(); }
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$physicalTick(MovementContext context, CallbackInfo ci) {
        if (context.world.isClientSide) {
            PortableDocking.clientTick(context); ci.cancel();
        } else if (!context.world.isClientSide && PortableDocking.managed(context)) {
            PortableDocking.tick(context); ci.cancel();
        }
    }
}
