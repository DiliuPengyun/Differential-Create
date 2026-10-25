package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.BeltScrollPhase;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fallback renderer version of the same re-anchor. Replacing the render clock
 * with one and the speed with the accumulated travel reproduces Create's
 * original geometry flips while keeping the phase on the integral of speed.
 */
@Mixin(BeltRenderer.class)
public abstract class BeltRendererMixin {

	@Redirect(method = "renderSafe", at = @At(value = "INVOKE",
		target = "Lnet/createmod/catnip/animation/AnimationTickHolder;getRenderTime(Lnet/minecraft/world/level/LevelAccessor;)F"))
	private float differentialcreate$phaseTime(LevelAccessor level) {
		return 1f;
	}

	@Redirect(method = "renderSafe", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;getSpeed()F"))
	private float differentialcreate$phaseSpeed(BeltBlockEntity be) {
		double period = 31.5 * 16;
		double ticks = BeltScrollPhase.rendererTicks(be);
		if (Double.isNaN(ticks))
			ticks = (double) be.getSpeed() * AnimationTickHolder.getRenderTime(be.getLevel());
		double reduced = ticks % period;
		// Keep the original "speed != 0" branch when the phase lands exactly on a repeat.
		if (reduced == 0 && be.getSpeed() != 0)
			reduced = Math.copySign(period, ticks == 0 ? be.getSpeed() : ticks);
		return (float) reduced;
	}
}
