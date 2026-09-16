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
			NetworkTopology.mark(self);
			self.sendData();
		}
		ci.cancel();
	}

	@Redirect(method = "addToGoggleTooltip",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$suppressGeneratorStressLines() { return false; }
}
