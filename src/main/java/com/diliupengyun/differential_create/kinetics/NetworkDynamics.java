package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class NetworkDynamics {
	private NetworkDynamics() {}

	public static float computeTargetSpeed(KineticNetwork network) {
		float target = 0;
		for (KineticBlockEntity source : network.sources.keySet())
			target = Math.max(target, Math.abs(source.getGeneratedSpeed()));
		return target;
	}

	public static double localSpeed(KineticBlockEntity be, KineticNetwork network) {
		INetworkDynamics dynamics = (INetworkDynamics) network;
		Float r = dynamics.differentialcreate$getRatios().get(be);
		return r == null ? 0 : r * dynamics.differentialcreate$getSpeed();
	}

	public static double preciseSpeed(KineticBlockEntity be) {
		return be.getLevel() != null && !be.getLevel().isClientSide && be.hasNetwork()
			? localSpeed(be, be.getOrCreateNetwork()) : be.getSpeed();
	}

	public static void restoreMember(KineticBlockEntity be, KineticNetwork network) {
		IMemberDynamics member = (IMemberDynamics) be;
		INetworkDynamics dynamics = (INetworkDynamics) network;
		MemberState saved = member.differentialcreate$member();
		double pending = member.differentialcreate$getPendingNetworkSpeed();
		if (Double.isFinite(pending) && !dynamics.differentialcreate$data().initialized) {
			dynamics.differentialcreate$setSpeed(pending);
			dynamics.differentialcreate$setLastNonZeroTarget(saved.savedNominal);
			dynamics.differentialcreate$data().anchor = saved.savedAnchor;
			dynamics.differentialcreate$data().initialized = true;
		}
		if (!dynamics.differentialcreate$getRatios().containsKey(be)) {
			float ratio = saved.savedRatio;
			if (!Float.isFinite(ratio)) {
				float nominal = dynamics.differentialcreate$getLastNonZeroTarget();
				if (nominal == 0) nominal = computeTargetSpeed(network);
				if (nominal == 0) nominal = Math.abs(be.getTheoreticalSpeed());
				ratio = nominal == 0 ? 1 : be.getTheoreticalSpeed() / nominal;
			}
			dynamics.differentialcreate$getRatios().put(be, ratio);
			dynamics.differentialcreate$data().invalidate();
		}
		member.differentialcreate$clearPendingNetworkSpeed();
		saved.savedRatio = Float.NaN;
	}

	public static void writeNetworkDynamics(KineticBlockEntity be, CompoundTag tag, boolean clientPacket) {
		if (clientPacket && MachineActivity.manages(be)) tag.putBoolean("DCMachineWorking", MachineActivity.active(be));
		if (be instanceof IPumpDynamics pump) tag.putFloat("DCPumpHead", pump.differentialcreate$head());
		if (be instanceof IClutchDynamics clutch) {
			tag.putInt("DCClutchSignal", clutch.differentialcreate$getRedstoneSignal());
			tag.putFloat("DCClutchTorque", clutch.differentialcreate$getLastCouplingTorque());
			tag.putFloat("DCClutchSlip", clutch.differentialcreate$getLastDeltaOmega());
			tag.putBoolean("DCClutchLocked", clutch.differentialcreate$isLocked());
			tag.putFloat("DCClutchFreeOutputSpeed", clutch.differentialcreate$getFreeOutputSpeed());
		}
		if (clientPacket || be.getLevel() == null || be.getLevel().isClientSide || !be.hasNetwork()) return;
		INetworkDynamics dynamics = (INetworkDynamics) be.getOrCreateNetwork();
		CompoundTag network = tag.getCompound("Network");
		network.putDouble("DCSpeed", dynamics.differentialcreate$getSpeed());
		network.putFloat("DCTargetSpeed", dynamics.differentialcreate$getTargetSpeed());
		network.putFloat("DCNominal", dynamics.differentialcreate$getLastNonZeroTarget());
		network.putFloat("DCRatio", dynamics.differentialcreate$getRatios().getOrDefault(be, 1f));
		BlockPos anchor = dynamics.differentialcreate$data().anchor;
		if (anchor != null) network.putLong("DCAnchor", anchor.asLong());
		tag.put("Network", network);
	}

	public static void readNetworkDynamics(CompoundTag tag, boolean clientPacket, KineticBlockEntity be) {
		if (clientPacket && tag.contains("DCMachineWorking"))
			((IMemberDynamics) be).differentialcreate$member().machineActive = tag.getBoolean("DCMachineWorking");
		if (be instanceof IClutchDynamics clutch) {
			clutch.differentialcreate$setRedstoneSignal(tag.contains("DCClutchSignal") ? tag.getInt("DCClutchSignal") : -1);
			clutch.differentialcreate$setLastCouplingTorque(tag.getFloat("DCClutchTorque"));
			clutch.differentialcreate$setLastDeltaOmega(tag.getFloat("DCClutchSlip"));
			clutch.differentialcreate$setLocked(tag.getBoolean("DCClutchLocked"));
			float outputSpeed = tag.getFloat("DCClutchFreeOutputSpeed");
			clutch.differentialcreate$setFreeOutputSpeed(Float.isFinite(outputSpeed) ? outputSpeed : 0);
			clutch.differentialcreate$setPreviousTorqueLimit(-1);
		}
		if (clientPacket || !tag.contains("Network")) return;
		CompoundTag network = tag.getCompound("Network");
		IMemberDynamics member = (IMemberDynamics) be;
		member.differentialcreate$setPendingNetworkSpeed(network.contains("DCSpeed") ? network.getDouble("DCSpeed")
			: network.getFloat("DCTargetSpeed"));
		MemberState saved = member.differentialcreate$member();
		saved.savedRatio = network.contains("DCRatio") ? network.getFloat("DCRatio") : Float.NaN;
		saved.savedNominal = network.contains("DCNominal") ? network.getFloat("DCNominal") : network.getFloat("DCTargetSpeed");
		saved.savedAnchor = network.contains("DCAnchor") ? BlockPos.of(network.getLong("DCAnchor")) : null;
	}

	public static void physicalSpeed(KineticBlockEntity be, CallbackInfoReturnable<Float> cir) {
		Level level = be.getLevel();
		if (level == null || level.tickRateManager().isFrozen()) return;
		if (level.isClientSide) {
			Float speed = ClientNetworkSpeeds.speedOf(be);
			if (speed != null) cir.setReturnValue(speed);
		} else if (be.hasNetwork()) {
			cir.setReturnValue((float) localSpeed(be, be.getOrCreateNetwork()));
		}
	}
}
