package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;

/**
 * Block-entity-renderer path of the continuous-angle model: replace the
 * time × speed angle (discontinuous for varying speeds) with the network's
 * accumulated r × θ.
 */
@Mixin(KineticBlockEntityRenderer.class)
public abstract class KineticBlockEntityRendererMixin {

	@Inject(method = "getAngleForBe", at = @At("RETURN"), cancellable = true)
	private static void differentialcreate$continuousAngleForBe(KineticBlockEntity be, BlockPos pos, Axis axis,
		CallbackInfoReturnable<Float> cir) {
		float angle = ClientKineticAngles.angleOf(be, axis,
			KineticBlockEntityRenderer.getRotationOffsetForPosition(be, pos, axis));
		if (!Float.isNaN(angle))
			cir.setReturnValue(angle);
	}

}
