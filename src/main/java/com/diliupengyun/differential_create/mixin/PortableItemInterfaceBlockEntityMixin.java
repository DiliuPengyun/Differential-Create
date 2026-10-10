package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.psi.PortableItemInterfaceBlockEntity;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PortableItemInterfaceBlockEntity.class)
public abstract class PortableItemInterfaceBlockEntityMixin {
    @Shadow protected IItemHandlerModifiable capability;
    @Inject(method = "startTransferringTo", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$reservation(Contraption contraption, float distance, CallbackInfo ci) {
        ci.cancel(); var endpoint = (PortableEndpoint) this;
        if (!endpoint.differentialcreate$docking().accepts(contraption)) return;
        endpoint.differentialcreate$attachStorage(contraption, distance);
        var inventory = contraption.getStorage().getAllItems();
        capability = new DockingHandlers.Items(inventory, endpoint.differentialcreate$docking().session(), inventory);
        var owner = (PortableItemInterfaceBlockEntity) (Object) this;
        owner.invalidateCapabilities(); owner.notifyUpdate();
        if (owner.getLevel() != null && !owner.getLevel().isClientSide) owner.getLevel().updateNeighborsAt(owner.getBlockPos(), owner.getBlockState().getBlock());
    }
}
