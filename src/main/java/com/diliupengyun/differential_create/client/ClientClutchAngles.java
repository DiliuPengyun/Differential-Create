package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.diliupengyun.differential_create.kinetics.IClutchDynamics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;

/** Output phases live with the clutch, so renderer rebuilds cannot reset a free port. */
public final class ClientClutchAngles {
	private static final ClutchAnimationCache<ClutchBlockEntity> outputs = new ClutchAnimationCache<>();

	private ClientClutchAngles() {}

	public static RotationFrame outputRotation(ClutchBlockEntity clutch) {
		if (clutch.getLevel() instanceof net.createmod.ponder.api.level.PonderLevel level) {
			Double angle = com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(level)
				.clutchOutputAngle(clutch, net.createmod.catnip.animation.AnimationTickHolder.getPartialTicks(level));
			return new RotationFrame(Minecraft.getInstance().levelRenderer.getTicks(), angle == null ? 0 : angle, 0);
		}
		return update(clutch, outputs.get(clutch));
	}

	private static RotationFrame update(ClutchBlockEntity clutch, FreeShaftRotation rotation) {
		Minecraft minecraft = Minecraft.getInstance();
		int tick = minecraft.levelRenderer.getTicks();
		double renderTick = ClientKineticAngles.renderTick();
		boolean frozen = clutch.getLevel().tickRateManager().isFrozen();
		KineticBlockEntity member = ClutchPhysics.output(clutch);
		if (member == null && ClutchPhysics.signal(clutch) < 15) member = clutch;
		RotationFrame connected = member == null ? null : ClientNetworkSpeeds.rotationOf(member);
		if (member != null && connected == null) {
			// Until the network snapshot arrives, use that shaft's ordinary fallback.
			var axis = KineticBlockEntityRenderer.getRotationAxisOf(clutch);
			double rate = frozen ? 0 : member.getSpeed() * 0.3;
			double angle = Math.toDegrees(KineticBlockEntityRenderer.getAngleForBe(member, member.getBlockPos(), axis))
				- KineticBlockEntityRenderer.getRotationOffsetForPosition(member, member.getBlockPos(), axis);
			connected = new RotationFrame(tick, angle - (renderTick - tick) * rate, rate);
		}
		return rotation.resolve(connected, ((IClutchDynamics) clutch).differentialcreate$getFreeOutputSpeed(),
			tick, renderTick, frozen);
	}

	/** Runs even during /tick freeze, when block entity ticks do not refresh visuals. */
	public static void onClientTick() {
		Minecraft minecraft = Minecraft.getInstance();
		var snapshot = outputs.snapshot(clutch -> clutch.isRemoved() || clutch.getLevel() != minecraft.level);
		if (minecraft.isPaused()) return;
		snapshot.forEach((clutch, rotation) -> {
			RotationFrame before = rotation.frame();
			RotationFrame after = update(clutch, rotation);
			if (before == null || !before.sameMotion(after)) VisualizationHelper.queueUpdate(clutch);
		});
	}
}
