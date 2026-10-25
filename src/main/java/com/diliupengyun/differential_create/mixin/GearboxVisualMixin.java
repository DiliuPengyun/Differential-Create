package com.diliupengyun.differential_create.mixin;

import java.util.EnumMap;

import com.diliupengyun.differential_create.client.ClientShaftAngles;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.gearbox.GearboxVisual;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GearboxVisual.class)
public abstract class GearboxVisualMixin {
    @Shadow @Final protected EnumMap<Direction, RotatingInstance> keys;

    @Redirect(method = {"<init>", "update"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/base/RotatingInstance;setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction$Axis;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;"))
    private RotatingInstance differentialcreate$animateFace(RotatingInstance instance, KineticBlockEntity be,
        Direction.Axis axis, float speed) {
        for (var face : Iterate.directions) {
            if (face.getAxis() == be.getBlockState().getValue(BlockStateProperties.AXIS)) continue;
            // Constructor visits the first missing face; updates find the stored instance.
            if (!keys.containsKey(face) || keys.get(face) == instance)
                return ClientShaftAngles.at(be, face).setup(instance);
        }
        throw new IllegalStateException("Unknown gearbox face instance");
    }
}
