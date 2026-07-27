package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.diliupengyun.differential_create.kinetics.MergePhysics;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Generator lifecycle changes for the physical model (DESIGN.md §6):
 * reverse activation is judged by engagement energy instead of the
 * unconditional destroy, and a deactivated source stays in its network as
 * an inert member so the group coasts on its inertia.
 */
@Mixin(GeneratingKineticBlockEntity.class)
public abstract class GeneratingKineticBlockEntityMixin {

	@Redirect(method = "applyNewSpeed",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
	private boolean differentialcreate$reverseActivationCheck(Level level, BlockPos pos, boolean drop) {
		if (MergePhysics.reverseActivationSpared((GeneratingKineticBlockEntity) (Object) this))
			return false; // spared: the source's torque fights the network instead
		return level.destroyBlock(pos, drop);
	}

	/**
	 * Deactivated source (generatedSpeed -> 0, e.g. water removed from a
	 * wheel): vanilla detaches it from the network, which would orphan the
	 * group's speed state. Cancel that branch for network members: the block
	 * stays as an inert member (its J still counts in J_eff), the network
	 * drops to K = 0 and coasts on the existing coasting logic. Re-activation
	 * later re-powers the network without any special path.
	 */
	@Inject(method = "applyNewSpeed", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$keepInertSource(float prevSpeed, float speed, CallbackInfo ci) {
		GeneratingKineticBlockEntity self = (GeneratingKineticBlockEntity) (Object) this;
		if (speed != 0 || self.hasSource() || !self.hasNetwork())
			return;
		if (self.getOrCreateNetwork() == null)
			return;
		ci.cancel();
	}

	/**
	 * The generator's own goggle stress lines ("generator stats / capacity
	 * provided") are nominal bookkeeping with no meaning in the physical
	 * model — short-circuit the stress-impact gate inside this method only
	 * (the KBE-level lines are suppressed by the sibling redirect there).
	 */
	@Redirect(method = "addToGoggleTooltip",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$suppressGeneratorStressLines() {
		return false;
	}

}
