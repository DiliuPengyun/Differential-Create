package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.content.BrassClutchBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Control and display for a friction-limited elastic contact; lock never changes its force law. */
public final class ClutchPhysics {
	private ClutchPhysics() {}

	public static void onSpeedModifierQuery(ClutchBlockEntity clutch, Direction face, CallbackInfoReturnable<Float> cir) {
		if (!clutch.hasSource() || face == clutch.getSourceFacing()) return;
		if (NetworkTopology.probingClutches()) { cir.setReturnValue(1f); return; }
		// Override Create's binary POWERED result in both directions: a weak
		// signal still connects the friction surface, whether sticking or slipping.
		cir.setReturnValue(torqueLimit(clutch) == 0 ? 0f : 1f);
	}

	public static double manualEngagement(ClutchBlockEntity clutch) {
		return clutch instanceof BrassClutchBlockEntity brass ? brass.manualEngagement() : 1;
	}

	public static double engagement(ClutchBlockEntity clutch) {
		return ClutchControl.engagement(manualEngagement(clutch), signal(clutch));
	}

	public static double torqueLimit(ClutchBlockEntity clutch) {
		double rating = clutch instanceof BrassClutchBlockEntity ? PhysicsTuning.BRASS_CLUTCH_TORQUE : PhysicsTuning.CLUTCH_TORQUE;
		return ClutchControl.torqueLimit(rating, manualEngagement(clutch), signal(clutch));
	}

	public static void manualEngagementChanged(ClutchBlockEntity clutch) {
		if (clutch.getLevel() == null || clutch.getLevel().isClientSide) return;
		IClutchDynamics state = (IClutchDynamics) clutch;
		if (!state.differentialcreate$isLocked()) state.differentialcreate$setLockTimer(0);
		// The regular coupling pass checks the new limit and preserves a lock if
		// it still supports the load; it captures/splits an opened clutch once.
		// Editing a setting while redstone holds it open must not move its free output.
	}

	public static int signal(ClutchBlockEntity clutch) {
		if (clutch.getLevel() != null && !clutch.getLevel().isClientSide)
			return clutch.getLevel().getBestNeighborSignal(clutch.getBlockPos());
		int synced = ((IClutchDynamics) clutch).differentialcreate$getRedstoneSignal();
		return synced < 0 ? (clutch.getBlockState().getValue(BlockStateProperties.POWERED) ? 15 : 0) : synced;
	}

	public static KineticBlockEntity output(ClutchBlockEntity clutch) {
		if (!clutch.hasSource() || clutch.getLevel() == null) return null;
		var pos = clutch.getBlockPos().relative(clutch.getSourceFacing().getOpposite());
		if (!clutch.getLevel().isLoaded(pos)) return null;
		if (!(clutch.getLevel().getBlockEntity(pos) instanceof KineticBlockEntity be)) return null;
		return be.getBlockState().getBlock() instanceof IRotate rotate
			&& rotate.hasShaftTowards(clutch.getLevel(), pos, be.getBlockState(), clutch.getSourceFacing()) ? be : null;
	}

	/** Control changes alter the graph only when the friction surface opens or closes. */
	public static void prepare(KineticNetwork network) {
		for (ClutchBlockEntity clutch : ((INetworkDynamics) network).differentialcreate$data().clutches) {
			IClutchDynamics state = (IClutchDynamics) clutch;
			double limit = torqueLimit(clutch), previous = state.differentialcreate$getPreviousTorqueLimit();
			if (limit == 0 && previous > 0 && output(clutch) == null)
				state.differentialcreate$setFreeOutputSpeed(clutch.getSpeed());
			if ((limit > 0) != (previous > 0)) NetworkTopology.mark(clutch);
			state.differentialcreate$setPreviousTorqueLimit(limit);
		}
	}

	/** Lock is a display state, never a different constitutive law or an unlimited rigid edge. */
	public static void accumulateCoupling(KineticNetwork network, Level level) {
		for (ClutchBlockEntity clutch : ((INetworkDynamics) network).differentialcreate$data().clutches) {
			if (clutch.isRemoved()) continue;
			IClutchDynamics state = (IClutchDynamics) clutch;
			int signal = signal(clutch);
			boolean changed = signal != state.differentialcreate$getRedstoneSignal();
			state.differentialcreate$setRedstoneSignal(signal);
			double limit = torqueLimit(clutch), torque = 0;
			KineticBlockEntity far = output(clutch);
			boolean slipping = false;
			if (far != null) state.differentialcreate$setFreeOutputSpeed(far.getSpeed());
			else if (limit > 0) state.differentialcreate$setFreeOutputSpeed(clutch.getSpeed());
			for (var edge : ((INetworkDynamics) network).differentialcreate$data().connections)
				if (edge.clutch == clutch) {
					torque = edge.a == clutch ? edge.ratio * edge.torque : -edge.torque;
					slipping = edge.slipping;
					break;
				}
			double delta = clutch.getSpeed() - state.differentialcreate$getFreeOutputSpeed();
			int timer = limit > 0 && !slipping && Math.abs(delta) < PhysicsTuning.CLUTCH_LOCK_EPSILON_RPM
				? Math.min(PhysicsTuning.CLUTCH_LOCK_TICKS, state.differentialcreate$getLockTimer() + 1) : 0;
			boolean locked = timer >= PhysicsTuning.CLUTCH_LOCK_TICKS;
			changed |= locked != state.differentialcreate$isLocked();
			state.differentialcreate$setLockTimer(timer);
			state.differentialcreate$setLocked(locked);
			display(clutch, (float) torque, (float) delta, changed);
			if (slipping && level.getGameTime() % 10 == 0 && level instanceof net.minecraft.server.level.ServerLevel server) {
				var pos = clutch.getBlockPos();
				server.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
					pos.getX() + .5, pos.getY() + .75, pos.getZ() + .5, 2, .15, .1, .15, .01);
			}
		}
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
