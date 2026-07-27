package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.kinetics.ClutchPhysics;
import com.diliupengyun.differential_create.kinetics.IClutchDynamics;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import net.minecraft.core.Direction;

/**
 * Turns Create's clutch into a friction-slip element (DESIGN.md §6): while
 * engaged, the clutch is a boundary between the two networks instead of a
 * pass-through; torque is exchanged by ClutchPhysics instead.
 */
@Mixin(ClutchBlockEntity.class)
public abstract class ClutchBlockEntityMixin implements IClutchDynamics {

	@Unique
	private int differentialcreate$lockTimer;
	@Unique
	private float differentialcreate$lastDeltaOmega;
	@Unique
	private float differentialcreate$lastCouplingTorque;
	@Unique
	private boolean differentialcreate$mergePending;

	/**
	 * Boundary hook. getRotationSpeedModifier feeds RotationPropagator's
	 * connection check in both directions (the clutch is consulted as `from`
	 * and as `to`), so zeroing it here severs the network connection cleanly
	 * for propagation while leaving the block visually and structurally in
	 * place. Only consulted during propagation events, not per tick.
	 */
	@Inject(method = "getRotationSpeedModifier", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$slipBoundary(Direction face, CallbackInfoReturnable<Float> cir) {
		ClutchPhysics.onSpeedModifierQuery((ClutchBlockEntity) (Object) this, cir);
	}

	@Override
	public int differentialcreate$getLockTimer() {
		return differentialcreate$lockTimer;
	}

	@Override
	public void differentialcreate$setLockTimer(int ticks) {
		this.differentialcreate$lockTimer = ticks;
	}

	@Override
	public float differentialcreate$getLastDeltaOmega() {
		return differentialcreate$lastDeltaOmega;
	}

	@Override
	public void differentialcreate$setLastDeltaOmega(float delta) {
		this.differentialcreate$lastDeltaOmega = delta;
	}

	@Override
	public float differentialcreate$getLastCouplingTorque() {
		return differentialcreate$lastCouplingTorque;
	}

	@Override
	public void differentialcreate$setLastCouplingTorque(float torque) {
		this.differentialcreate$lastCouplingTorque = torque;
	}

	@Override
	public boolean differentialcreate$isMergePending() {
		return differentialcreate$mergePending;
	}

	@Override
	public void differentialcreate$setMergePending(boolean pending) {
		this.differentialcreate$mergePending = pending;
	}

}
