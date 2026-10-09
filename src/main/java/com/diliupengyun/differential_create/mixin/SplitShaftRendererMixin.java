package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.diliupengyun.differential_create.client.ClientClutchAngles;
import com.diliupengyun.differential_create.client.ClientSequencerAngles;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** The ordinary renderer uses the same separate input/output phases as Flywheel. */
@Mixin(SplitShaftRenderer.class)
public abstract class SplitShaftRendererMixin {
	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"), cancellable = true)
	private void differentialcreate$animateClutch(SplitShaftBlockEntity be, float partialTicks, PoseStack pose,
		MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
		if (!(be instanceof ClutchBlockEntity) && !(be instanceof SequencedGearshiftBlockEntity)) return;
		if (be instanceof SequencedGearshiftBlockEntity seq && seq.isVirtual()) return;
		ci.cancel();
		if (VisualizationManager.supportsVisualization(be.getLevel())) return;
		var axis = KineticBlockEntityRenderer.getRotationAxisOf(be);
		for (var face : Iterate.directionsInAxis(axis)) {
			boolean outputFace = be.hasSource() ? face != be.getSourceFacing()
				: be instanceof SequencedGearshiftBlockEntity && face.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE;
			KineticBlockEntity member = outputFace && be instanceof ClutchBlockEntity clutch ? ClutchPhysics.output(clutch) : be;
			if (member == null) member = be;
			float angle = outputFace
				? (float) Math.toRadians(((be instanceof ClutchBlockEntity clutch ? ClientClutchAngles.outputRotation(clutch)
					: ClientSequencerAngles.outputRotation((SequencedGearshiftBlockEntity) be)).angleAt(ClientKineticAngles.renderTick())
					+ ClientKineticAngles.shaftOffsetOf(member, axis)) % 360)
				: KineticBlockEntityRenderer.getAngleForBe(member, member.getBlockPos(), axis);
			var shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), face);
			KineticBlockEntityRenderer.kineticRotationTransform(shaft, member, axis, angle, light)
				.renderInto(pose, buffers.getBuffer(RenderType.solid()));
		}
	}
}
