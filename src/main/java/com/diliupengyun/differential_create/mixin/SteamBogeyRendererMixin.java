package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.SteamBogeyModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StandardBogeyRenderer.class)
public abstract class SteamBogeyRendererMixin {
    @Inject(method="render",at=@At("RETURN"))
    private void differentialcreate$cylinders(CompoundTag data,float angle,float partial,PoseStack pose,MultiBufferSource source,int light,int overlay,boolean moving,CallbackInfo ci) {
        SteamBogeyModels.render(data,pose,source,light,overlay,(Object)this instanceof StandardBogeyRenderer.Large);
    }
}
