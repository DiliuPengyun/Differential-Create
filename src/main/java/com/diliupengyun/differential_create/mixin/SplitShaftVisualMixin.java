package com.diliupengyun.differential_create.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.diliupengyun.differential_create.client.ClientClutchAngles;
import com.diliupengyun.differential_create.client.ClientSequencerAngles;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftVisual;

import net.createmod.catnip.data.Iterate;

/** A slipping clutch's output half belongs to the driven network's animation. */
@Mixin(SplitShaftVisual.class)
public abstract class SplitShaftVisualMixin {
	@Shadow @Final
	protected ArrayList<RotatingInstance> keys;

	@Redirect(method = {"<init>", "update"}, at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/RotatingInstance;setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;"))
	private RotatingInstance differentialcreate$animateOutput(RotatingInstance instance, KineticBlockEntity be, float speed) {
		if (be instanceof SequencedGearshiftBlockEntity seq && !seq.isVirtual()) {
			var axis = KineticBlockEntityVisual.rotationAxis(seq.getBlockState());
			int index = keys.indexOf(instance);
			if (index < 0) index = keys.size();
			var face = Iterate.directionsInAxis(axis)[index];
			if (seq.hasSource() ? face != seq.getSourceFacing()
				: face.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE) {
				float offset = ClientKineticAngles.shaftOffsetOf(seq, axis);
				var frame = ClientSequencerAngles.outputRotation(seq);
				instance.setup(seq, axis, 0).setRotationOffset(offset).setRotationalSpeed(frame.shaderSpeed());
				instance.rotationOffset = frame.shaderOffset(offset);
				return instance;
			}
		}
		if (be instanceof ClutchBlockEntity clutch && clutch.hasSource()) {
			var axis = KineticBlockEntityVisual.rotationAxis(clutch.getBlockState());
			int index = keys.indexOf(instance);
			// Construction appends each instance only after setup; update uses its existing index.
			if (index < 0) index = keys.size();
			if (Iterate.directionsInAxis(axis)[index] != clutch.getSourceFacing()) {
				KineticBlockEntity output = ClutchPhysics.output(clutch);
				KineticBlockEntity member = output == null ? clutch : output;
				float shaftOffset = ClientKineticAngles.shaftOffsetOf(member, axis);
				instance.setup(member, axis, member.getSpeed()).setRotationOffset(shaftOffset);
				var frame = ClientClutchAngles.outputRotation(clutch);
				instance.setRotationalSpeed(frame.shaderSpeed());
				// Final shader coefficient: setup has already captured the model offset.
				instance.rotationOffset = frame.shaderOffset(shaftOffset);
				return instance;
			}
		}
		return instance.setup(be, speed);
	}
}
