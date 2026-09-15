package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import net.minecraft.core.Direction.Axis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ordinary and encased large-cog shafts bypass the base renderer's angle method. */
@Mixin(BracketedKineticBlockEntityRenderer.class)
public abstract class BracketedKineticBlockEntityRendererMixin {
	@Inject(method = "getAngleForLargeCogShaft", at = @At("RETURN"), cancellable = true)
	private static void differentialcreate$continuousShaftAngle(SimpleKineticBlockEntity be, Axis axis,
		CallbackInfoReturnable<Float> cir) {
		float angle = ClientKineticAngles.angleOf(be, axis,
			BracketedKineticBlockEntityRenderer.getShaftAngleOffset(axis, be.getBlockPos()));
		if (!Float.isNaN(angle)) cir.setReturnValue(angle);
	}
}
