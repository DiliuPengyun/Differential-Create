package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FixedRotationSpeedConfig;
import com.simibubi.create.infrastructure.config.CKinetics;
import net.createmod.catnip.config.ConfigBase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CKinetics.class)
public abstract class CKineticsMixin extends ConfigBase {
	@Shadow @Final @Mutable public ConfigInt maxRotationSpeed;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void differentialcreate$removeSpeedLimit(CallbackInfo ci) {
		allValues.remove(maxRotationSpeed);
		// Create still uses this field for crusher advancement and controller construction.
		maxRotationSpeed = new FixedRotationSpeedConfig(this);
		allValues.remove(maxRotationSpeed);
	}
}
