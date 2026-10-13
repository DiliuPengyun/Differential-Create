package com.diliupengyun.differential_create.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StandardBogeyRenderer.Large.class)
public abstract class GearedBogeyRendererMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/SuperByteBuffer;renderInto(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V", ordinal = 3))
    private void differentialcreate$hiddenMainRod(SuperByteBuffer mesh, PoseStack pose, VertexConsumer vertex,
        CompoundTag data, float angle, float partial, PoseStack originalPose, MultiBufferSource source, int light, int overlay, boolean moving) {
        if (!data.getBoolean("DCGeared")) mesh.renderInto(pose, vertex);
    }
}
