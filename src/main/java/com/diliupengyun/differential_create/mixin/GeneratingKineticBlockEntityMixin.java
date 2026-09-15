package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.NetworkPhysics;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.diliupengyun.differential_create.kinetics.RotationMath;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Source changes alter torque; they do not overwrite the physical shaft speed. */
@Mixin(GeneratingKineticBlockEntity.class)
public abstract class GeneratingKineticBlockEntityMixin {
	@Inject(method = "updateGeneratedRotation", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$updateSource(CallbackInfo ci) {
		GeneratingKineticBlockEntity self = (GeneratingKineticBlockEntity) (Object) this;
		if (self.getLevel() != null && !self.getLevel().isClientSide) {
			var member = ((com.diliupengyun.differential_create.kinetics.IMemberDynamics) self).differentialcreate$member();
			float command = self.getGeneratedSpeed();
			boolean activated = Float.isFinite(member.sourceCommand) && Math.signum(command) != Math.signum(member.sourceCommand);
			member.sourceCommand = command;
			if (activated && self.hasNetwork() && self.getSpeed() * command < 0) {
				var network = self.getOrCreateNetwork();
				var dynamics = (INetworkDynamics) network;
				float ratio = dynamics.differentialcreate$getRatios().getOrDefault(self, 0f);
				if (ratio != 0) {
					double sourceInertia = InertiaRegistry.getInertia(self);
					double rest = Math.max(0, NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios()).j()
						- sourceInertia * ratio * ratio);
					double loss = RotationMath.mergeLoss(rest, dynamics.differentialcreate$getSpeed(), sourceInertia,
						SourcePhysics.curve(self).noLoadSpeed(), ratio);
					if (loss > PhysicsTuning.MERGE_BREAK_ENERGY
						&& !NetworkTopology.slipForSourceImpact(self, SourcePhysics.curve(self).noLoadSpeed())) {
						self.getLevel().destroyBlock(self.getBlockPos(), true);
						ci.cancel();
						return;
					}
				}
			}
			NetworkTopology.mark(self);
			self.sendData();
		}
		ci.cancel();
	}

	@Redirect(method = "addToGoggleTooltip",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$suppressGeneratorStressLines() { return false; }
}
