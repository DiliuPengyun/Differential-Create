package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.psi.PortableFluidInterfaceBlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PortableFluidInterfaceBlockEntity.class)
public abstract class PortableFluidInterfaceBlockEntityMixin {
    @Shadow protected IFluidHandler capability;
    @Inject(method = "startTransferringTo", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$reservation(Contraption contraption, float distance, CallbackInfo ci) {
        ci.cancel(); var endpoint = (PortableEndpoint) this;
        if (!endpoint.differentialcreate$docking().accepts(contraption)) return;
        endpoint.differentialcreate$attachStorage(contraption, distance);
        var inventory = contraption.getStorage().getFluids();
        capability = new DockingHandlers.Fluids(inventory, endpoint.differentialcreate$docking().session(), inventory);
        var owner = (PortableFluidInterfaceBlockEntity) (Object) this;
        owner.invalidateCapabilities(); owner.notifyUpdate();
    }
}
