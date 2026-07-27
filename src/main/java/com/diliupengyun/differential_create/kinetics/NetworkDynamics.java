package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * Static helpers implementing the continuous-rotation bookkeeping that the
 * mixins attach to Create's kinetic network classes.
 */
public final class NetworkDynamics {

	public static final String TAG_SPEED = "DCSpeed";
	public static final String TAG_TARGET_SPEED = "DCTargetSpeed";
	public static final String TAG_CLUTCH_TORQUE = "DCClutchTorque";
	public static final String TAG_CLUTCH_SLIP = "DCClutchSlip";

	private NetworkDynamics() {}

	/**
	 * Nominal speed of a network: the fastest source on it. Single-source
	 * networks simply get |getGeneratedSpeed| of their source. Computed on
	 * demand from Create's source registry, so it never depends on the
	 * continuously ramping speed fields and the write-back cannot feed back
	 * into the target.
	 */
	public static float computeTargetSpeed(KineticNetwork network) {
		float target = 0;
		for (KineticBlockEntity source : network.sources.keySet())
			target = Math.max(target, Math.abs(source.getGeneratedSpeed()));
		return target;
	}

	/**
	 * Denominator for ratio computation: the nominal target speed, or — for
	 * sourceless (coasting) networks — the last non-zero nominal speed, so
	 * blocks joining a coasting network get a sensible ratio instead of 0.
	 */
	private static float ratioDenominator(INetworkDynamics dynamics, float target) {
		if (target != 0) {
			dynamics.differentialcreate$setLastNonZeroTarget(target);
			return target;
		}
		float lastNonZero = dynamics.differentialcreate$getLastNonZeroTarget();
		if (lastNonZero != 0)
			return lastNonZero;
		return Math.abs(dynamics.differentialcreate$getSpeed());
	}

	/**
	 * Called from the KineticBlockEntity#setSpeed mixin whenever propagation
	 * (or a generator) assigns a speed on the server. The speed fields always
	 * hold vanilla nominal values, so {@code newSpeed} is always a
	 * target-space value and the recorded ratio stays correct.
	 */
	public static void capturePropagatedSpeed(KineticBlockEntity kbe, float newSpeed) {
		Level level = kbe.getLevel();
		if (level == null || level.isClientSide)
			return;
		if (!kbe.hasNetwork())
			return;
		KineticNetwork network = kbe.getOrCreateNetwork();
		if (network == null)
			return;
		INetworkDynamics dynamics = (INetworkDynamics) network;
		float target = computeTargetSpeed(network);
		dynamics.differentialcreate$setTargetSpeed(target);
		float denominator = ratioDenominator(dynamics, target);
		dynamics.differentialcreate$getRatios()
			.put(kbe, denominator == 0 ? 0 : newSpeed / denominator);
		ClientSpeedSync.markStructureDirty(network, level);
	}

	/**
	 * Ratio for a member that joined without a captured propagation (the
	 * source itself, or restored from NBT): its field holds the vanilla
	 * nominal speed, so the ratio can be derived from it.
	 */
	public static float ratioFromNominal(KineticBlockEntity member, INetworkDynamics dynamics, float target) {
		float denominator = ratioDenominator(dynamics, target);
		return denominator == 0 ? 0 : member.getTheoreticalSpeed() / denominator;
	}

	/**
	 * Appends the member's network speed state to its save tag (inside the
	 * vanilla "Network" compound). Every member saves the same values; restore
	 * is idempotent. Clutch slip display values ride along on the sync packet
	 * as well so the goggle overlay can show them on the client.
	 */
	public static void writeNetworkDynamics(KineticBlockEntity kbe, CompoundTag tag, boolean clientPacket) {
		if (kbe instanceof ClutchBlockEntity) {
			IClutchDynamics clutch = (IClutchDynamics) kbe;
			tag.putFloat(TAG_CLUTCH_TORQUE, clutch.differentialcreate$getLastCouplingTorque());
			tag.putFloat(TAG_CLUTCH_SLIP, clutch.differentialcreate$getLastDeltaOmega());
		}
		if (clientPacket || !kbe.hasNetwork())
			return;
		Level level = kbe.getLevel();
		if (level == null || level.isClientSide)
			return;
		KineticNetwork network = kbe.getOrCreateNetwork();
		if (network == null || !tag.contains("Network"))
			return;
		INetworkDynamics dynamics = (INetworkDynamics) network;
		CompoundTag networkTag = tag.getCompound("Network");
		networkTag.putFloat(TAG_SPEED, dynamics.differentialcreate$getSpeed());
		networkTag.putFloat(TAG_TARGET_SPEED, dynamics.differentialcreate$getTargetSpeed());
	}

	/**
	 * Stashes the saved network speed on the block entity; the ticker applies
	 * it once the block entity has rejoined its network (the network object
	 * may not exist yet while the chunk is being read). Prefers the physical
	 * speed; tags written by the framework milestone only carry the nominal
	 * target. Clutch display values are restored on both sides.
	 */
	public static void readNetworkDynamics(CompoundTag tag, boolean clientPacket, KineticBlockEntity kbe) {
		if (kbe instanceof ClutchBlockEntity) {
			IClutchDynamics clutch = (IClutchDynamics) kbe;
			clutch.differentialcreate$setLastCouplingTorque(tag.getFloat(TAG_CLUTCH_TORQUE));
			clutch.differentialcreate$setLastDeltaOmega(tag.getFloat(TAG_CLUTCH_SLIP));
		}
		if (clientPacket || !tag.contains("Network"))
			return;
		CompoundTag networkTag = tag.getCompound("Network");
		IMemberDynamics member = (IMemberDynamics) kbe;
		if (networkTag.contains(TAG_SPEED))
			member.differentialcreate$setPendingNetworkSpeed(networkTag.getFloat(TAG_SPEED));
		else if (networkTag.contains(TAG_TARGET_SPEED))
			member.differentialcreate$setPendingNetworkSpeed(networkTag.getFloat(TAG_TARGET_SPEED));
	}

	/**
	 * Serves the getSpeed() mixin with the physical speed (ratio × network ω):
	 * on the server computed from the network state, on the client read from
	 * the sync state (falling back to the nominal field until the first
	 * packet arrives). Frozen levels keep the vanilla zero.
	 * getTheoreticalSpeed() is untouched: propagation keeps operating on
	 * nominal fields.
	 */
	public static void physicalSpeed(KineticBlockEntity kbe, CallbackInfoReturnable<Float> cir) {
		Level level = kbe.getLevel();
		if (level == null || level.tickRateManager()
			.isFrozen())
			return;
		if (level.isClientSide) {
			Float speed = ClientNetworkSpeeds.speedOf(kbe);
			if (speed != null)
				cir.setReturnValue(speed);
			return;
		}
		if (!kbe.hasNetwork())
			return;
		KineticNetwork network = kbe.getOrCreateNetwork();
		if (network == null)
			return;
		INetworkDynamics dynamics = (INetworkDynamics) network;
		Float ratio = dynamics.differentialcreate$getRatios()
			.get(kbe);
		if (ratio == null)
			return;
		cir.setReturnValue(ratio * dynamics.differentialcreate$getSpeed());
	}

}
