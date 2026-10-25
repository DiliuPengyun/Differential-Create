package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ChainScrollPhase;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRenderer;

import net.createmod.catnip.animation.AnimationTickHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keep the chain link texture on the member's accumulated angle. The native
 * {@code renderChains} computes {@code renderTime / (360 / |getSpeed()|)} from
 * absolute time, so any speed change re-phases the links. Returning a synthetic
 * speed for that one call makes the renderer solve for the desired phase while
 * leaving the rest of its geometry, connection lengths and lighting untouched.
 */
@Mixin(ChainConveyorRenderer.class)
public abstract class ChainConveyorRendererMixin {

	@Redirect(method = "renderChains", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;getSpeed()F"))
	private float differentialcreate$chainPhaseSpeed(ChainConveyorBlockEntity be) {
		double phase = ChainScrollPhase.phase(be);
		if (!Double.isFinite(phase))
			return be.getSpeed();
		float renderTime = AnimationTickHolder.getRenderTime(be.getLevel());
		if (renderTime <= 0)
			return 0;
		return (float) (phase * 360.0 / renderTime);
	}
}
