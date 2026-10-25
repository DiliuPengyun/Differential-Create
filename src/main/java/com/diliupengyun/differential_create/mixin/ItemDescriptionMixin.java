package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.KineticItemTooltip;
import com.simibubi.create.foundation.item.ItemDescription;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemDescription.class)
public abstract class ItemDescriptionMixin {
    @Inject(method = "create(Lnet/minecraft/world/item/Item;Lnet/createmod/catnip/lang/FontHelper$Palette;)Lcom/simibubi/create/foundation/item/ItemDescription;",
        at = @At("RETURN"), cancellable = true)
    private static void differentialcreate$properties(Item item, Palette palette, CallbackInfoReturnable<ItemDescription> cir) {
        cir.setReturnValue(KineticItemTooltip.description(item, cir.getReturnValue()));
    }
}
