package com.diliupengyun.differential_create.mixin;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.diliupengyun.differential_create.kinetics.ClientSpeedSync;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Attaches the continuous-rotation state (current speed,
 * per-member ratios) to Create's {@link KineticNetwork}, and marks the
 * structure sync dirty on membership changes.
 */
@Mixin(KineticNetwork.class)
public abstract class KineticNetworkMixin implements INetworkDynamics {
	@Unique
	private final com.diliupengyun.differential_create.kinetics.NetworkData differentialcreate$data =
		new com.diliupengyun.differential_create.kinetics.NetworkData();

	@Override
	public com.diliupengyun.differential_create.kinetics.NetworkData differentialcreate$data() {
		return differentialcreate$data;
	}

	@Unique
	private double differentialcreate$speed;
	@Unique
	private final Map<KineticBlockEntity, Float> differentialcreate$ratios = new HashMap<>();
	@Unique
	private boolean differentialcreate$settled;
	@Unique
	private double differentialcreate$externalTorque;
	@Unique
	private float differentialcreate$lastNonZeroTarget;

	@Inject(method = "add", at = @At("RETURN"))
	private void differentialcreate$onAdd(KineticBlockEntity be, CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.NetworkTopology.mark(be);
		differentialcreate$markDirty(be);
	}

	@Inject(method = "addSilently", at = @At("RETURN"))
	private void differentialcreate$onAddSilently(KineticBlockEntity be, float lastCapacity, float lastStress,
		CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.NetworkDynamics.restoreMember(be, (KineticNetwork) (Object) this);
		differentialcreate$markDirty(be);
	}

	@Inject(method = "remove", at = @At("HEAD"))
	private void differentialcreate$onRemove(KineticBlockEntity be, CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.NetworkTopology.mark(be);
		differentialcreate$data.invalidate();
		KineticNetwork self = (KineticNetwork) (Object) this;
		if (be.getLevel() != null)
			ClientSpeedSync.markStructureDirty(self, be.getLevel());
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void differentialcreate$onRemoved(KineticBlockEntity be, CallbackInfo ci) {
		// KineticNetwork#remove drops the network from the registry when its
		// last member leaves: dissolve it on clients too
		KineticNetwork self = (KineticNetwork) (Object) this;
		if (self.members.isEmpty() && be.getLevel() != null)
			ClientSpeedSync.networkDissolved(be.getLevel(), self.id == null ? 0 : self.id);
	}

	@Unique
	private void differentialcreate$markDirty(KineticBlockEntity be) {
		differentialcreate$data.invalidate();
		if (be.getLevel() != null)
			ClientSpeedSync.markStructureDirty((KineticNetwork) (Object) this, be.getLevel());
	}

	@Inject(method = {"updateStressFor", "updateCapacityFor"}, at = @At("HEAD"))
	private void differentialcreate$invalidateBalance(KineticBlockEntity be, float value, CallbackInfo ci) {
		differentialcreate$data.invalidate();
	}

	@Override
	public double differentialcreate$getSpeed() {
		return differentialcreate$speed;
	}

	@Override
	public void differentialcreate$setSpeed(double speed) {
		this.differentialcreate$speed = speed;
		if (!differentialcreate$data.publishingReference)
			differentialcreate$ratios.forEach((be, ratio) ->
				((com.diliupengyun.differential_create.kinetics.IMemberDynamics) be).differentialcreate$member().physicalSpeed = ratio * speed);
	}

	@Override
	public Map<KineticBlockEntity, Float> differentialcreate$getRatios() {
		return differentialcreate$ratios;
	}

	@Override
	public boolean differentialcreate$isSettled() {
		return differentialcreate$settled;
	}

	@Override
	public void differentialcreate$setSettled(boolean settled) {
		this.differentialcreate$settled = settled;
	}

	@Override
	public double differentialcreate$getExternalTorque() {
		return differentialcreate$externalTorque;
	}

	@Override
	public void differentialcreate$setExternalTorque(double torque) {
		this.differentialcreate$externalTorque = torque;
	}

	@Override
	public float differentialcreate$getLastNonZeroTarget() {
		return differentialcreate$lastNonZeroTarget;
	}

	@Override
	public void differentialcreate$setLastNonZeroTarget(float target) {
		this.differentialcreate$lastNonZeroTarget = target;
	}

}
