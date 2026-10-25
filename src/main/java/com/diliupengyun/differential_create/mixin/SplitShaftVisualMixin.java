package com.diliupengyun.differential_create.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.diliupengyun.differential_create.client.ClientShaftAngles;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.transmission.SplitShaftVisual;

import net.createmod.catnip.data.Iterate;

/** All split-shaft instances use the same face phase as the ordinary renderer. */
@Mixin(SplitShaftVisual.class)
public abstract class SplitShaftVisualMixin {
	@Shadow @Final
	protected ArrayList<RotatingInstance> keys;

	@Redirect(method = {"<init>", "update"}, at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/RotatingInstance;setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;"))
	private RotatingInstance differentialcreate$animateFace(RotatingInstance instance, KineticBlockEntity be, float speed) {
		var axis = KineticBlockEntityVisual.rotationAxis(be.getBlockState());
		int index = keys.indexOf(instance);
		// Construction appends each instance only after setup; update uses its existing index.
		if (index < 0) index = keys.size();
		return ClientShaftAngles.at(be, Iterate.directionsInAxis(axis)[index]).setup(instance);
	}
}
