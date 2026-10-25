package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.client.KineticItemTooltip;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.createmod.ponder.foundation.PonderTooltipHandler;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PonderTooltipHandler.class)
public abstract class PonderTooltipMixin {
    @WrapOperation(method = "addToTooltip", at = @At(value = "INVOKE", target = "Ljava/util/List;add(ILjava/lang/Object;)V"))
    private static void differentialcreate$afterProperties(List<Component> tooltip, int index, Object hint, Operation<Void> original) {
        original.call(tooltip, KineticItemTooltip.ponderIndex(tooltip, index), hint);
    }
}
