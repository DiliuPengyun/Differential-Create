package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.SteamHoseRenderer;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractBogeyBlockEntity.class)
public abstract class SteamBogeyClientMixin {
    @Inject(method="getBogeyData",at=@At("RETURN"))
    private void differentialcreate$observe(CallbackInfoReturnable<CompoundTag> ci) {SteamHoseRenderer.observe((AbstractBogeyBlockEntity)(Object)this);}
}
