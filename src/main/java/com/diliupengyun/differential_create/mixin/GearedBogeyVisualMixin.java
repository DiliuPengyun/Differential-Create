package com.diliupengyun.differential_create.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.StandardBogeyVisual;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StandardBogeyVisual.Large.class)
public abstract class GearedBogeyVisualMixin {
    @Shadow @Final private TransformedInstance piston;
    @Inject(method = "update", at = @At("RETURN"))
    private void differentialcreate$hiddenMainRod(CompoundTag data, float angle, PoseStack pose, CallbackInfo ci) {
        if (data.getBoolean("DCGeared")) piston.setZeroTransform().setChanged();
    }
}
