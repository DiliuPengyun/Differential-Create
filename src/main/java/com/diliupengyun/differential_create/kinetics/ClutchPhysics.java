package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Finite friction coupling. The output edge exists only while locked. */
public final class ClutchPhysics {
	private ClutchPhysics() {}

	public static void onSpeedModifierQuery(ClutchBlockEntity clutch, Direction face, CallbackInfoReturnable<Float> cir) {
		if (!clutch.hasSource() || face == clutch.getSourceFacing()) return;
		if (NetworkTopology.probingClutches()) { cir.setReturnValue(1f); return; }
		IClutchDynamics state = (IClutchDynamics) clutch;
		// Override Create's binary POWERED result in both directions: a weak
		// signal can still hold a rigid connection when its torque limit allows it.
		cir.setReturnValue(signal(clutch) == 15 || !state.differentialcreate$isLocked() ? 0f : 1f);
	}

	public static int signal(ClutchBlockEntity clutch) {
		if (clutch.getLevel() != null && !clutch.getLevel().isClientSide)
			return clutch.getLevel().getBestNeighborSignal(clutch.getBlockPos());
		int synced = ((IClutchDynamics) clutch).differentialcreate$getRedstoneSignal();
		return synced < 0 ? (clutch.getBlockState().getValue(BlockStateProperties.POWERED) ? 15 : 0) : synced;
	}

	public static void accumulateCoupling(KineticNetwork network, Level level) {
		for (ClutchBlockEntity clutch : ((INetworkDynamics) network).differentialcreate$data().clutches)
			if (!clutch.isRemoved()) process(clutch, network, level);
	}

	public static KineticBlockEntity output(ClutchBlockEntity clutch) {
		if (!clutch.hasSource() || clutch.getLevel() == null) return null;
		var pos = clutch.getBlockPos().relative(clutch.getSourceFacing().getOpposite());
		if (!clutch.getLevel().isLoaded(pos)) return null;
		if (!(clutch.getLevel().getBlockEntity(pos) instanceof KineticBlockEntity be)) return null;
		return be.getBlockState().getBlock() instanceof IRotate rotate
			&& rotate.hasShaftTowards(clutch.getLevel(), pos, be.getBlockState(), clutch.getSourceFacing()) ? be : null;
	}

	/** Release an impact-loaded clutch; the caller rebuilds the affected topology. */
	public static void startSlipping(ClutchBlockEntity clutch) {
		IClutchDynamics state = (IClutchDynamics) clutch;
		state.differentialcreate$setLocked(false);
		state.differentialcreate$setLockTimer(0);
		state.differentialcreate$setLastCouplingTorque(0);
		clutch.sendData();
	}

	private static void process(ClutchBlockEntity clutch, KineticNetwork a, Level level) {
		IClutchDynamics state = (IClutchDynamics) clutch;
		int signal = signal(clutch);
		int previousSignal = state.differentialcreate$getRedstoneSignal();
		boolean signalChanged = signal != previousSignal;
		if (signalChanged) {
			state.differentialcreate$setRedstoneSignal(signal);
			state.differentialcreate$setLockTimer(0);
		}
		double limit = ClutchControl.torqueLimit(signal);
		KineticBlockEntity far = output(clutch);
		if (far != null)
			state.differentialcreate$setFreeOutputSpeed(far.getSpeed());
		if (limit == 0) {
			boolean wasLocked = state.differentialcreate$isLocked();
			// The bare half-shaft has J = 0 and no load, just like an isolated
			// shaft network. Capture its release speed and retain that balance.
			if (far == null && (wasLocked || previousSignal >= 0 && previousSignal < 15))
				state.differentialcreate$setFreeOutputSpeed(clutch.getSpeed());
			if (state.differentialcreate$isLocked()) {
				state.differentialcreate$setLocked(false);
				NetworkTopology.mark(clutch);
			}
			display(clutch, 0, clutch.getSpeed() - state.differentialcreate$getFreeOutputSpeed(), signalChanged || wasLocked);
			state.differentialcreate$setLockTimer(0);
			return;
		}
		if (far == null) {
			// Any nonzero pressure can synchronize an unloaded, massless port.
			state.differentialcreate$setFreeOutputSpeed(clutch.getSpeed());
			int ticks = state.differentialcreate$isLocked() || !clutch.hasSource()
				? 0 : state.differentialcreate$getLockTimer() + 1;
			boolean locking = ticks >= PhysicsTuning.CLUTCH_LOCK_TICKS;
			state.differentialcreate$setLockTimer(locking ? 0 : ticks);
			if (locking) state.differentialcreate$setLocked(true);
			display(clutch, 0, 0, signalChanged || locking);
			return;
		}
		if (!far.hasNetwork()) {
			display(clutch, 0, 0, signalChanged);
			state.differentialcreate$setLockTimer(0);
			return;
		}
		KineticNetwork b = far.getOrCreateNetwork();
		INetworkDynamics da = (INetworkDynamics) a, db = (INetworkDynamics) b;
		if (a == b) {
			// Required shaft torque on the output component (including its acceleration).
			double transmitted = NetworkTopology.clutchTorque(clutch, a);
			boolean overloaded = Math.abs(transmitted) > limit + 0.001;
			if (overloaded) {
				state.differentialcreate$setLocked(false);
				NetworkTopology.mark(clutch);
			}
			display(clutch, (float) transmitted, 0, signalChanged || overloaded);
			return;
		}
		state.differentialcreate$setLocked(false);
		float ra = da.differentialcreate$getRatios().getOrDefault(clutch, 0f);
		float rb = db.differentialcreate$getRatios().getOrDefault(far, 0f);
		if (ra == 0 || rb == 0) return;
		double delta = ra * da.differentialcreate$getSpeed() - rb * db.differentialcreate$getSpeed();
		double ja = da.differentialcreate$data().balance.j(), jb = db.differentialcreate$data().balance.j();
		double mergeLoss = RotationMath.mergeLoss(ja, da.differentialcreate$getSpeed(), jb, db.differentialcreate$getSpeed(), ra / rb);
		// All engagement pressures dissipate a speed mismatch through finite
		// friction torque, including full pressure after a locked clutch releases.
		double torque = ja > 0 && jb > 0 ? ClutchCoupling.torque(shaft(da, ra), shaft(db, rb), limit) : 0;
		if (ja == 0 || jb == 0) {
			// Massless transmission still passes its load torque back to the driver.
			if (ja == 0 && jb > 0) {
				double matched = rb * db.differentialcreate$getSpeed() / ra;
				double required = force(da.differentialcreate$data().balance, matched) / ra;
				torque = Math.clamp(required, -limit, limit);
				da.differentialcreate$setSpeed(Math.abs(required) <= limit ? (float) matched : 0);
			} else if (jb == 0 && ja > 0) {
				double matched = ra * da.differentialcreate$getSpeed() / rb;
				double required = -force(db.differentialcreate$data().balance, matched) / rb;
				torque = Math.clamp(required, -limit, limit);
				db.differentialcreate$setSpeed(Math.abs(required) <= limit ? (float) matched : 0);
			}
			delta = ra * da.differentialcreate$getSpeed() - rb * db.differentialcreate$getSpeed();
		}
		double holding = holdingTorque(da, db, ra, rb);
		da.differentialcreate$setExternalTorque((float) (da.differentialcreate$getExternalTorque() - torque * ra));
		db.differentialcreate$setExternalTorque((float) (db.differentialcreate$getExternalTorque() + torque * rb));
		int ticks = Math.abs(delta) < PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM && Math.abs(holding) <= limit
			&& mergeLoss <= PhysicsTuning.CLUTCH_LOCK_ENERGY
			? state.differentialcreate$getLockTimer() + 1 : 0;
		state.differentialcreate$setLockTimer(ticks);
		boolean locking = ticks >= PhysicsTuning.CLUTCH_LOCK_TICKS;
		if (locking) {
			state.differentialcreate$setLocked(true);
			state.differentialcreate$setLockTimer(0);
			NetworkTopology.mark(clutch);
			level.playSound(null, clutch.getBlockPos(), SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5f, 0.9f);
		}
		display(clutch, (float) torque, (float) delta, locking || signalChanged);
		if (Math.abs(delta) > PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM && level.getGameTime() % 10 == 0
			&& level instanceof net.minecraft.server.level.ServerLevel server) {
			var pos = clutch.getBlockPos();
			server.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
				pos.getX() + 0.5, pos.getY() + 0.75, pos.getZ() + 0.5, 2, 0.15, 0.1, 0.15, 0.01);
			level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.15f, 1.6f);
		}
	}

	private static ClutchCoupling.Shaft shaft(INetworkDynamics dynamics, float ratio) {
		NetworkPhysics.Balance b = dynamics.differentialcreate$data().balance;
		return new ClutchCoupling.Shaft(dynamics.differentialcreate$getSpeed(),
			b.t0() + dynamics.differentialcreate$getExternalTorque(), b.k(), b.l(), b.j(), b.fanDrag(), ratio);
	}

	/** Torque needed to keep the two sides synchronous under their external loads. */
	private static double holdingTorque(INetworkDynamics a, INetworkDynamics b, float ra, float rb) {
		NetworkPhysics.Balance ba = a.differentialcreate$data().balance, bb = b.differentialcreate$data().balance;
		double matched = RotationMath.mergedSpeed(ba.j(), a.differentialcreate$getSpeed(),
			bb.j(), b.differentialcreate$getSpeed(), ra / rb);
		double fa = force(ba, matched) + a.differentialcreate$getExternalTorque();
		double fb = force(bb, matched * ra / rb) + b.differentialcreate$getExternalTorque();
		if (ba.j() == 0) return fa / ra;
		if (bb.j() == 0) return -fb / rb;
		return (ra * fa / ba.j() - rb * fb / bb.j()) / (ra * ra / ba.j() + rb * rb / bb.j());
	}

	private static double force(NetworkPhysics.Balance b, double speed) {
		return b.t0() - b.k() * speed - b.l() * Math.signum(speed) - b.fanDrag() * speed * Math.abs(speed);
	}

	private static void display(ClutchBlockEntity clutch, float torque, float delta, boolean force) {
		IClutchDynamics state = (IClutchDynamics) clutch;
		boolean changed = Math.abs(torque - state.differentialcreate$getLastCouplingTorque()) > 0.05f
			|| Math.abs(delta - state.differentialcreate$getLastDeltaOmega()) > 0.05f;
		state.differentialcreate$setLastCouplingTorque(torque);
		state.differentialcreate$setLastDeltaOmega(delta);
		if (force || changed && torque == 0 && delta == 0
			|| (torque != 0 || delta != 0) && clutch.getLevel().getGameTime() % 4 == 0) clutch.sendData();
	}
}
