package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.FluidMaterials;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.client.PipeFluidGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.TransparentStraightPipeRenderer;
import com.simibubi.create.foundation.fluid.FluidRenderer;
import net.createmod.catnip.render.FluidRenderHelper;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
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
        var axis = be.getBlockState().getValue(BlockStateProperties.AXIS);
        if (saved.phases != null) {
            for (var part : saved.slices()) {
                float a = (float)Math.max(0, part.from()), b = (float)Math.min(1, part.to());
                if (!(b > a)) continue;
                float x1 = 5 / 16f, y1 = 5 / 16f, z1 = 5 / 16f, x2 = 11 / 16f, y2 = 11 / 16f, z2 = 11 / 16f;
                switch (axis) {
                    case X -> { x1 = a; x2 = b; }
                    case Y -> { y1 = a; y2 = b; }
                    case Z -> { z1 = a; z2 = b; }
                }
                NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(part.fluid(), x1, y1, z1, x2, y2, z2, buffer, poses, light, false, true);
            }
            return;
        }
        if (saved.fluid.isEmpty()) return;
        Direction side = switch (axis) { case X -> Direction.EAST; case Y -> Direction.UP; case Z -> Direction.SOUTH; };
        var contents = PipeContents.join(saved.content(side.getOpposite(), partial).reverse(), saved.content(side, partial), .5);
        if (axis != Direction.Axis.Y && Double.isFinite(saved.macroLiquid) && !FluidMaterials.isGas(saved.fluid)) {
            var layer = PipeFluidGeometry.horizontal(contents.fraction());
            if (layer.height() > 0) NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(saved.fluid,
                axis == Direction.Axis.X ? 0 : 5 / 16f, (float) layer.bottom(), axis == Direction.Axis.Z ? 0 : 5 / 16f,
                axis == Direction.Axis.X ? 1 : 11 / 16f, (float) layer.top(), axis == Direction.Axis.Z ? 1 : 11 / 16f,
                buffer, poses, light, false, true);
            return;
        }
        if (axis == Direction.Axis.Y && Double.isFinite(saved.macroLiquid) && !FluidMaterials.isGas(saved.fluid)
            && contents.intervals() == 1 && contents.start(0) == 0) {
            var column = PipeFluidGeometry.vertical(contents.fraction(), -saved.visualFlow(Direction.UP, partial),
                saved.visualFlow(Direction.DOWN, partial));
            if (column.streamRadius() > 0) {
                float level = (float) column.poolHeight(), radius = (float) column.streamRadius() * 3 / 16f;
                if (level > 0) NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(saved.fluid,
                    5 / 16f, 0, 5 / 16f, 11 / 16f, level, 11 / 16f, buffer, poses, light, false, true);
                differentialcreate$fallingStream(saved.fluid, radius, level, buffer, poses, light);
                return;
            }
        }
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

    private static void differentialcreate$fallingStream(FluidStack fluid, float radius, float level,
                                                          MultiBufferSource buffer, PoseStack poses, int light) {
        float a = .5f - radius, b = .5f + radius;
        if (!(b > a) || !(1 > level)) return;
        var clientFluid = IClientFluidTypeExtensions.of(fluid.getFluid());
        var flowing = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(clientFluid.getFlowingTexture(fluid));
        int color = clientFluid.getTintColor(fluid);
        light = (light & 0xF00000) | Math.max((light >> 4) & 0xF, fluid.getFluidType().getLightLevel(fluid)) << 4;
        var builder = FluidRenderHelper.getFluidBuilder(buffer);
        // Open faces join the neighbouring stream and the pool without drawing
        // a horizontal cap at every block boundary.
        FluidRenderer.renderFlowingTiledFace(Direction.NORTH, a, level, b, 1, a, builder, poses, light, color, flowing);
        FluidRenderer.renderFlowingTiledFace(Direction.SOUTH, a, level, b, 1, b, builder, poses, light, color, flowing);
        FluidRenderer.renderFlowingTiledFace(Direction.WEST, a, level, b, 1, a, builder, poses, light, color, flowing);
        FluidRenderer.renderFlowingTiledFace(Direction.EAST, a, level, b, 1, b, builder, poses, light, color, flowing);
    }
}
