package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import com.simibubi.create.content.kinetics.flywheel.FlywheelRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render the wheel body at the same angle as its ordinary shaft renderer. */
@Mixin(FlywheelRenderer.class)
public abstract class FlywheelRendererMixin {
	@Inject(method = "renderFlywheel", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$sharedWheelAngle(FlywheelBlockEntity be, PoseStack pose, int light,
		BlockState state, float ignoredVisualAngle, VertexConsumer buffer, CallbackInfo ci) {
		KineticBlockEntityRenderer.kineticRotationTransform(CachedBuffers.block(state), be,
			KineticBlockEntityRenderer.getRotationAxisOf(be), ClientKineticAngles.shaftAngleOf(be), light)
			.renderInto(pose, buffer);
		ci.cancel();
	}
}
