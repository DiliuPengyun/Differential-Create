package com.diliupengyun.differential_create.client;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction.Axis;

/** Both rendering paths use the member's assembly-aligned angle and the shader's clock. */
public final class ClientKineticAngles {
	private ClientKineticAngles() {}

	public static float renderTick() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.levelRenderer.getTicks() + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
	}

	public static RotationFrame rotationOf(KineticBlockEntity be, float portSpeed, PortRotation port) {
		RotationFrame member = ClientNetworkSpeeds.rotationOf(be);
		return member == null ? null : port.resolve(member, be.getSpeed(), portSpeed, renderTick());
	}

	public static float angleOf(KineticBlockEntity be, Axis axis, float staticOffset) {
		Double theta = ClientNetworkSpeeds.thetaOf(be);
		return theta == null ? Float.NaN : (float) (Math.toRadians((staticOffset + theta) % 360));
	}

	/** A neighbouring large cog's teeth and centre shaft have different static alignments. */
	public static float shaftOffsetOf(KineticBlockEntity be, Axis axis) {
		return ShaftModelOffset.of(ICogWheel.isLargeCog(be.getBlockState()),
			KineticBlockEntityVisual.shouldOffset(axis, be.getBlockPos()), be.getRotationAngleOffset(axis));
	}

	/** Shared shaft radians, including the same alignment before the first network snapshot. */
	public static float shaftAngleOf(KineticBlockEntity be) {
		Axis axis = KineticBlockEntityRenderer.getRotationAxisOf(be);
		float modelAngle = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis);
		return modelAngle + (float) Math.toRadians(shaftOffsetOf(be, axis)
			- KineticBlockEntityRenderer.getRotationOffsetForPosition(be, be.getBlockPos(), axis));
	}
}
