package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.TransparentStraightPipeRenderer;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TransparentStraightPipeRenderer.class)
public abstract class TransparentStraightPipeRendererMixin {
    @Inject(method = "renderSafe(Lcom/simibubi/create/content/fluids/pipes/StraightPipeBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"), cancellable = true)
    private void differentialcreate$ownedWater(StraightPipeBlockEntity be, float partial, PoseStack poses,
                                               MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (be.isVirtual()) return;
        ci.cancel();
        var pipe = be.getBehaviour(FluidTransportBehaviour.TYPE);
        if (pipe == null) return;
        var saved = FluidKinetics.state(pipe);
        if (saved.fluid.isEmpty()) return;
        var axis = be.getBlockState().getValue(BlockStateProperties.AXIS);
        Direction side = switch (axis) { case X -> Direction.EAST; case Y -> Direction.UP; case Z -> Direction.SOUTH; };
        var contents = PipeContents.join(saved.content(side.getOpposite(), partial).reverse(), saved.content(side, partial), .5);
        for (int i = 0; i < contents.intervals(); i++) {
            float a = (float) contents.start(i), b = (float) contents.end(i);
            float x1 = 5 / 16f, y1 = 5 / 16f, z1 = 5 / 16f, x2 = 11 / 16f, y2 = 11 / 16f, z2 = 11 / 16f;
            switch (side.getAxis()) {
                case X -> { x1 = a; x2 = b; }
                case Y -> { y1 = a; y2 = b; }
                case Z -> { z1 = a; z2 = b; }
            }
            NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(saved.fluid, x1, y1, z1, x2, y2, z2, buffer, poses, light, false, true);
        }
    }
}
