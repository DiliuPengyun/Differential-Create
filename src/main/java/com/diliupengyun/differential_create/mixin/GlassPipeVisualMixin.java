package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.FluidMaterials;
import com.diliupengyun.differential_create.kinetics.PipeContents;
import com.diliupengyun.differential_create.client.PipeFluidGeometry;
import com.simibubi.create.content.fluids.FluidInstance;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.GlassPipeVisual;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.util.SmartRecycler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The instanced and ordinary paths share owned water geometry, including
 * horizontal layers and thin falling streams. Transport progress is not inventory. */
@Mixin(GlassPipeVisual.class)
public abstract class GlassPipeVisualMixin extends AbstractBlockEntityVisual<StraightPipeBlockEntity> {
    @Shadow private int light;
    @Shadow @Final private SmartRecycler<TextureAtlasSprite, FluidInstance> stream;
    @Shadow @Final private SmartRecycler<TextureAtlasSprite, TransformedInstance> surface;

    protected GlassPipeVisualMixin(VisualizationContext context, StraightPipeBlockEntity be, float partialTick) {
        super(context, be, partialTick);
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$ownedWater(DynamicVisual.Context context, CallbackInfo ci) {
        if (blockEntity.isVirtual() && !com.diliupengyun.differential_create.kinetics.PhysicsPreview.enabled(blockEntity.getLevel())) return;
        ci.cancel(); stream.resetCount(); surface.resetCount();
        var pipe = blockEntity.getBehaviour(FluidTransportBehaviour.TYPE);
        if (pipe != null) {
            var saved = FluidKinetics.state(pipe);
            if (saved.phases != null) {
                var axis = blockEntity.getBlockState().getValue(BlockStateProperties.AXIS);
                Direction side = Direction.get(Direction.AxisDirection.POSITIVE, axis);
                var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
                for (var part : saved.slices()) {
                    var clientFluid = IClientFluidTypeExtensions.of(part.fluid().getFluid());
                    var flowing = atlas.apply(clientFluid.getFlowingTexture(part.fluid()));
                    var still = atlas.apply(clientFluid.getStillTexture(part.fluid()));
                    int color = clientFluid.getTintColor(part.fluid());
                    int packedLight = (light & 0xF00000) | Math.max((light >> 4) & 0xF, part.fluid().getFluidType().getLightLevel(part.fluid())) << 4;
                    differentialcreate$segment(side, (float)Math.max(0, part.from()), (float)Math.min(1, part.to()), 1, true, true, flowing, still, color, packedLight);
                }
                stream.discardExtra(); surface.discardExtra(); return;
            }
            if (!saved.fluid.isEmpty()) {
                var axis = blockEntity.getBlockState().getValue(BlockStateProperties.AXIS);
                Direction side = switch (axis) { case X -> Direction.EAST; case Y -> Direction.UP; case Z -> Direction.SOUTH; };
                var contents = PipeContents.join(saved.content(side.getOpposite(), context.partialTick()).reverse(),
                    saved.content(side, context.partialTick()), .5);
                if (contents.intervals() != 0) {
                    var clientFluid = IClientFluidTypeExtensions.of(saved.fluid.getFluid());
                    var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
                    var flowing = atlas.apply(clientFluid.getFlowingTexture(saved.fluid));
                    var still = atlas.apply(clientFluid.getStillTexture(saved.fluid));
                    int color = clientFluid.getTintColor(saved.fluid);
                    int packedLight = (light & 0xF00000) | Math.max((light >> 4) & 0xF,
                        saved.fluid.getFluidType().getLightLevel(saved.fluid)) << 4;
                    var column = axis == Direction.Axis.Y && Double.isFinite(saved.macroLiquid)
                        && !FluidMaterials.isGas(saved.fluid) && contents.intervals() == 1 && contents.start(0) == 0
                        ? PipeFluidGeometry.vertical(contents.fraction(), -saved.visualFlow(Direction.UP, context.partialTick()),
                            saved.visualFlow(Direction.DOWN, context.partialTick())) : null;
                    if (axis != Direction.Axis.Y && Double.isFinite(saved.macroLiquid) && !FluidMaterials.isGas(saved.fluid)) {
                        differentialcreate$layer(side, PipeFluidGeometry.horizontal(contents.fraction()), flowing, still, color, packedLight);
                    } else if (column != null && column.streamRadius() > 0) {
                        float level = (float) column.poolHeight();
                        differentialcreate$segment(side, 0, level, 1, true, true, flowing, still, color, packedLight);
                        differentialcreate$segment(side, level, 1, (float) column.streamRadius(), false, false,
                            flowing, still, color, packedLight);
                    } else for (int n = 0; n < contents.intervals(); n++)
                        differentialcreate$segment(side, (float) contents.start(n), (float) contents.end(n), 1, true, true,
                            flowing, still, color, packedLight);
                }
            }
        }
        stream.discardExtra(); surface.discardExtra();
    }

    private void differentialcreate$layer(Direction side, PipeFluidGeometry.Layer layer,
                                           TextureAtlasSprite flowing, TextureAtlasSprite still, int color, int packedLight) {
        float height = (float) layer.height(), offset = (float) layer.centerOffset();
        if (!(height > 0)) return;
        var instance = stream.get(flowing);
        // Scale in world Y before rotating the stream mesh onto X or Z. The
        // downward offset anchors both orientations to the bore's lower wall.
        instance.setIdentityTransform().translate(getVisualPosition()).translate(0, offset, 0).center()
            .scale(1, height, 1).rotateTo(Direction.UP, side).translate(0, -.5f, 0);
        instance.light(packedLight).colorArgb(color);
        instance.progress = 1;
        instance.vScale = (flowing.getV1() - flowing.getV0()) * .5f;
        instance.v0 = flowing.getV0();
        instance.setChanged();
        surface.get(still).setIdentityTransform().translate(getVisualPosition()).translate(0, offset, 0).center()
            .scale(1, height, 1).rotateTo(Direction.UP, side.getOpposite()).translate(0, .5f, 0)
            .light(packedLight).colorArgb(color).setChanged();
        surface.get(still).setIdentityTransform().translate(getVisualPosition()).translate(0, offset, 0).center()
            .scale(1, height, 1).rotateTo(Direction.UP, side).translate(0, .5f, 0)
            .light(packedLight).colorArgb(color).setChanged();
    }

    private void differentialcreate$segment(Direction side, float a, float b, float radius, boolean capStart, boolean capEnd,
                                             TextureAtlasSprite flowing, TextureAtlasSprite still, int color, int packedLight) {
        if (!(b > a) || !(radius > 0)) return;
        var instance = stream.get(flowing);
        instance.setIdentityTransform().translate(getVisualPosition()).center()
            .rotateTo(Direction.UP, side).translate(0, a - .5f, 0).scale(radius, 1, radius);
        instance.light(packedLight).colorArgb(color);
        instance.progress = b - a;
        instance.vScale = (flowing.getV1() - flowing.getV0()) * .5f;
        instance.v0 = flowing.getV0() + a * instance.vScale;
        instance.setChanged();
        if (capStart) surface.get(still).setIdentityTransform().translate(getVisualPosition()).center()
            .rotateTo(Direction.UP, side.getOpposite()).translate(0, .5f - a, 0).scale(radius, 1, radius)
            .light(packedLight).colorArgb(color).setChanged();
        if (capEnd) surface.get(still).setIdentityTransform().translate(getVisualPosition()).center()
            .rotateTo(Direction.UP, side).translate(0, b - .5f, 0).scale(radius, 1, radius)
            .light(packedLight).colorArgb(color).setChanged();
    }
}
