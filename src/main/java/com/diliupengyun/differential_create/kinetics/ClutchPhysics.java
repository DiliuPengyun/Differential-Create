package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The clutch as a friction-slip element (DESIGN.md §6): an engaged clutch no
 * longer merges the two sides into one network. Instead each side keeps its
 * own network and speed, and the clutch transmits a bounded coupling torque
 * τ_c = clamp(GAIN·Δω, ±τ_clutch) that drags them into sync. Once the sides
 * are synchronous for a while the clutch locks: the networks merge for real
 * through one propagation pass. Disengaging (redstone) drops the coupling
 * instantly and the sides evolve on their own.
 * <p>
 * The engaged clutch only acts as a boundary against a <em>different live
 * network</em>: a dead or networkless side is adopted through vanilla
 * pass-through (the zero-inertia case of DESIGN.md §6.1), and once both
 * sides share one network (locked) the clutch is transparent again.
 */
public final class ClutchPhysics {

	private static final int SLIP_PARTICLE_INTERVAL = 5;
	private static final int SLIP_SOUND_INTERVAL = 15;
	private static final int SLIP_LOG_INTERVAL = 40;

	private record LockRequest(ClutchBlockEntity clutch, double delta) {}

	/** Lock merges queued during the coupling pass, flushed next pre-tick. */
	private static final List<LockRequest> pendingLocks = new ArrayList<>();

	private ClutchPhysics() {}

	/**
	 * Boundary query from the clutch's speed-modifier hook. Engaged
	 * (unpowered) clutches convey 0 while a different live network sits across
	 * them, severing the propagation in both directions.
	 */
	public static void onSpeedModifierQuery(ClutchBlockEntity clutch, CallbackInfoReturnable<Float> cir) {
		BlockState state = clutch.getBlockState();
		if (state.getValue(BlockStateProperties.POWERED))
			return; // disengaged: vanilla disconnect
		if (((IClutchDynamics) clutch).differentialcreate$isMergePending())
			return; // lock merge in progress: vanilla 1:1 pass-through
		if (cir.getReturnValue() == 0 || !clutch.hasNetwork())
			return; // already disconnected, or unclaimed: let a live side adopt it
		Level level = clutch.getLevel();
		if (level == null || level.isClientSide)
			return;
		if (findFarSide(clutch, level) != null)
			cir.setReturnValue(0f);
	}

	/**
	 * Per-tick pass over one network's members (called for every network
	 * before integration): accumulates the clutch coupling torques into the
	 * external-torque slot of both networks and advances the lock logic.
	 */
	public static void accumulateCoupling(KineticNetwork network, Level level) {
		INetworkDynamics dynamics = (INetworkDynamics) network;
		for (KineticBlockEntity member : network.members.keySet()) {
			if (member instanceof ClutchBlockEntity clutch && !clutch.isRemoved())
				processClutch(clutch, network, dynamics, level);
		}
	}

	private static void processClutch(ClutchBlockEntity clutch, KineticNetwork networkA, INetworkDynamics dynamicsA,
		Level level) {
		IClutchDynamics duck = (IClutchDynamics) clutch;
		BlockState state = clutch.getBlockState();

		if (state.getValue(BlockStateProperties.POWERED)) {
			// Disengaged: no coupling; the sides evolve on their own
			if (duck.differentialcreate$getLastCouplingTorque() != 0
				|| duck.differentialcreate$getLastDeltaOmega() != 0)
				DifferentialCreate.LOGGER.debug("Clutch at {} disengaged, coupling dropped",
					clutch.getBlockPos().toShortString());
			duck.differentialcreate$setLastCouplingTorque(0);
			duck.differentialcreate$setLastDeltaOmega(0);
			duck.differentialcreate$setLockTimer(0);
			return;
		}
		if (!clutch.hasNetwork())
			return;

		KineticBlockEntity far = findFarSide(clutch, level);
		if (far == null) {
			// Locked (same network) or a dead side: nothing to couple against
			duck.differentialcreate$setLastCouplingTorque(0);
			duck.differentialcreate$setLastDeltaOmega(0);
			duck.differentialcreate$setLockTimer(0);
			return;
		}

		KineticNetwork networkB = far.getOrCreateNetwork();
		INetworkDynamics dynamicsB = (INetworkDynamics) networkB;
		Float ratioClutch = dynamicsA.differentialcreate$getRatios()
			.get(clutch);
		Float ratioFar = dynamicsB.differentialcreate$getRatios()
			.get(far);
		if (ratioClutch == null || ratioFar == null || ratioClutch == 0 || ratioFar == 0)
			return;

		// Both local speeds in the clutch shaft frame; an engaged clutch is a
		// straight 1:1 shaft, so no connection modifier is needed
		double omegaA = ratioClutch * dynamicsA.differentialcreate$getSpeed();
		double omegaB = ratioFar * dynamicsB.differentialcreate$getSpeed();
		double delta = omegaA - omegaB;
		double torque = Mth.clamp(PhysicsTuning.CLUTCH_GAIN * delta, -PhysicsTuning.CLUTCH_TORQUE,
			PhysicsTuning.CLUTCH_TORQUE);

		// The coupling drags the sides together; the clutch itself carries no
		// inertia, so this is a pure external torque on both networks
		dynamicsA.differentialcreate$setExternalTorque(
			(float) (dynamicsA.differentialcreate$getExternalTorque() - torque * ratioClutch));
		dynamicsB.differentialcreate$setExternalTorque(
			(float) (dynamicsB.differentialcreate$getExternalTorque() + torque * ratioFar));

		boolean wasSlipping = Math.abs(duck.differentialcreate$getLastDeltaOmega()) >= PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM;
		duck.differentialcreate$setLastCouplingTorque((float) torque);
		duck.differentialcreate$setLastDeltaOmega((float) delta);

		if (Math.abs(delta) < PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM) {
			int timer = duck.differentialcreate$getLockTimer() + 1;
			duck.differentialcreate$setLockTimer(timer);
			if (timer >= PhysicsTuning.CLUTCH_LOCK_TICKS) {
				// Queue the lock: the merge must not run while the network
				// member lists are being iterated (CME), and it must see
				// nominal speed fields — it flushes in the next pre-tick
				// phase after the restore
				duck.differentialcreate$setLockTimer(0);
				pendingLocks.add(new LockRequest(clutch, delta));
			}
			return;
		}

		// Slipping
		duck.differentialcreate$setLockTimer(0);
		long gameTime = level.getGameTime();
		if (!wasSlipping || gameTime % SLIP_LOG_INTERVAL == 0)
			DifferentialCreate.LOGGER.debug("Clutch at {} slipping: Δω={} RPM, torque={} su/RPM",
				clutch.getBlockPos().toShortString(), fmt(delta), fmt(torque));
		if (level instanceof ServerLevel serverLevel) {
			BlockPos pos = clutch.getBlockPos();
			if (gameTime % SLIP_PARTICLE_INTERVAL == 0)
				serverLevel.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					1, 0.1, 0.1, 0.1, 0.01);
			if (gameTime % SLIP_SOUND_INTERVAL == 0)
				level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.25f, 1.2f);
		}
	}

	/**
	 * The far side of an engaged clutch: a kinetic neighbour along the clutch
	 * axis that belongs to a different network, or null when the sides share
	 * one network (locked) or the far side is dead.
	 */
	private static KineticBlockEntity findFarSide(ClutchBlockEntity clutch, Level level) {
		Direction.Axis axis = clutch.getBlockState()
			.getValue(BlockStateProperties.AXIS);
		for (Direction.AxisDirection axisDirection : Direction.AxisDirection.values()) {
			BlockEntity neighbour = level.getBlockEntity(
				clutch.getBlockPos().relative(Direction.fromAxisAndDirection(axis, axisDirection)));
			if (neighbour instanceof KineticBlockEntity kbe && kbe.hasNetwork()
				&& !kbe.network.equals(clutch.network))
				return kbe;
		}
		return null;
	}

	/**
	 * Executes the queued lock merges. Called from the ticker's pre-tick
	 * phase after the nominal-speed restore, so the propagation that merges
	 * the networks sees vanilla-consistent (nominal) speed fields, and no
	 * network member list is being iterated.
	 */
	public static void flushLocks() {
		for (LockRequest request : pendingLocks) {
			ClutchBlockEntity clutch = request.clutch();
			Level level = clutch.getLevel();
			BlockState state = clutch.getBlockState();
			if (clutch.isRemoved() || level == null || state.getValue(BlockStateProperties.POWERED))
				continue; // disengaged or gone before the flush
			IClutchDynamics duck = (IClutchDynamics) clutch;
			BlockPos pos = clutch.getBlockPos();
			DifferentialCreate.LOGGER.debug("Clutch at {} locked (Δω={} RPM), merging networks", pos.toShortString(),
				fmt(request.delta()));
			duck.differentialcreate$setMergePending(true);
			RotationPropagator.handleAdded(level, pos, clutch);
			duck.differentialcreate$setMergePending(false);
			if (level instanceof ServerLevel serverLevel) {
				serverLevel.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					8, 0.2, 0.2, 0.2, 0.02);
				level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5f, 0.9f);
			}
		}
		pendingLocks.clear();
	}

	private static String fmt(double value) {
		return String.format("%.2f", value);
	}

}
