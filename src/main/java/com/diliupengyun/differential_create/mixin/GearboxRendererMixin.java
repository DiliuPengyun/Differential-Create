package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.diliupengyun.differential_create.client.ClientShaftAngles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Gearbox faces share the split-shaft renderer's phase resolver. */
@Mixin(GearboxRenderer.class)
public abstract class GearboxRendererMixin {
    @Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/gearbox/GearboxBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"), cancellable = true)
    private void differentialcreate$animateGearbox(GearboxBlockEntity be, float partialTicks, PoseStack pose,
        MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
        ci.cancel();
        if (VisualizationManager.supportsVisualization(be.getLevel())) return;

        var boxAxis = be.getBlockState().getValue(BlockStateProperties.AXIS);
        for (Direction direction : Iterate.directions) {
            var faceAxis = direction.getAxis();
            if (boxAxis == faceAxis) continue;

            var rotation = ClientShaftAngles.at(be, direction);
            var shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), direction);
            KineticBlockEntityRenderer.kineticRotationTransform(shaft, rotation.owner(), faceAxis, rotation.radians(), light)
                .renderInto(pose, buffers.getBuffer(RenderType.solid()));
        }
    }
}
