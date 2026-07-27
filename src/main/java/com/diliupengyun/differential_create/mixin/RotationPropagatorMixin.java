package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.diliupengyun.differential_create.kinetics.MergePhysics;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;

/**
 * Reworks the merge rules of Create's rotation propagation (DESIGN.md §6):
 * flicker and overspeed destruction are removed, reverse connections break
 * only above an energy threshold, and network merges conserve angular
 * momentum.
 */
@Mixin(RotationPropagator.class)
public abstract class RotationPropagatorMixin {

	/**
	 * The flicker tally counts speed sign changes; with speeds changing every
	 * tick it would raze whole factories (DESIGN.md §6: check deleted).
	 */
	@Redirect(method = "propagateNewSource",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;getFlickerScore()I"))
	private static int differentialcreate$noFlickerDestroy(KineticBlockEntity kbe) {
		return 0;
	}

	/**
	 * The overspeed cap (unboxing of maxRotationSpeed in the tooFast check) is
	 * removed: sources self-limit at their no-load speed (DESIGN.md §6).
	 */
	@Redirect(method = "propagateNewSource",
		at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"))
	private static int differentialcreate$noOverspeedDestroy(Integer configValue) {
		return Integer.MAX_VALUE;
	}

	/**
	 * Reverse connections: vanilla destroys the contact block. Here the
	 * destroy only happens above the merge-break energy; below it the
	 * networks merge inelastically through the overpower branches below.
	 * Ordinal 1 is the incompatible-direction destroyBlock call.
	 */
	@Inject(method = "propagateNewSource",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z", ordinal = 1),
		cancellable = true)
	private static void differentialcreate$incompatibleMerge(KineticBlockEntity currentTE, CallbackInfo ci,
		@Local(ordinal = 1) KineticBlockEntity neighbourTE) {
		MergePhysics.handleIncompatible(currentTE, neighbourTE, ci);
	}

	/**
	 * The overpower branches move members into the driving network through
	 * setSource. When that crosses between two existing networks it is a
	 * genuine merge: settle angular momentum once (per pair per tick).
	 */
	@Redirect(method = "propagateNewSource",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;setSource(Lnet/minecraft/core/BlockPos;)V"))
	private static void differentialcreate$settleMergeOnSetSource(KineticBlockEntity driven, BlockPos sourcePos) {
		MergePhysics.handleSetSource(driven, sourcePos);
	}

	/**
	 * When a source (or any link) is removed, vanilla strips every downstream
	 * member from its network, stopping the group instantly. With network
	 * inertia the group should coast on its own (K = 0, dry friction decays
	 * it): cancel the strip and keep membership, ratios and dcSpeed intact.
	 * Stale source links are skipped over by the validation hook while the
	 * network keeps moving.
	 */
	@Inject(method = "propagateMissingSource", at = @At("HEAD"), cancellable = true)
	private static void differentialcreate$coastMissingSource(KineticBlockEntity updateTE, CallbackInfo ci) {
		if (updateTE.hasNetwork() && updateTE.getOrCreateNetwork() != null)
			ci.cancel();
	}

}
