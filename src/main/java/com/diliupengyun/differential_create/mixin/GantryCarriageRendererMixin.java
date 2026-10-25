package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both the fallback renderer and Flywheel call this separate, degree-valued cog angle. */
@Mixin(GantryCarriageRenderer.class)
public abstract class GantryCarriageRendererMixin {
    @Inject(method = "getAngleForBE", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$continuousCogAngle(KineticBlockEntity be, BlockPos visualPos, Axis axis,
        CallbackInfoReturnable<Float> cir) {
        float angle = ClientKineticAngles.angleOf(be, axis,
            KineticBlockEntityRenderer.getRotationOffsetForPosition(be, visualPos, axis));
        if (!Float.isNaN(angle)) cir.setReturnValue((float) Math.toDegrees(angle));
    }
}
