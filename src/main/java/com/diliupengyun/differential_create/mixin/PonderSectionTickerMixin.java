package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The isolated simulation owns machine ticks, independently of render-cache rebuilding. */
@Mixin(WorldSectionElementImpl.class)
public abstract class PonderSectionTickerMixin {
    @Inject(method = "addTicker", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$oneClock(BlockEntity entity, BlockEntityTicker<?> ticker, CallbackInfo ci) {
        if (entity instanceof SmartBlockEntity) ci.cancel();
    }
}
