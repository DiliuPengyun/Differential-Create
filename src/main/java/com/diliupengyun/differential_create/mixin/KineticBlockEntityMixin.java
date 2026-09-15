package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.kinetics.IMemberDynamics;
import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Hooks Create's {@link KineticBlockEntity} to capture propagation results,
 * persist the network's continuous speed state, serve the physical speed
 * and reshape the goggle tooltip.
 */
@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements IMemberDynamics {
	@Unique
	private final com.diliupengyun.differential_create.kinetics.MemberState differentialcreate$memberState =
		new com.diliupengyun.differential_create.kinetics.MemberState();

	@Override
	public com.diliupengyun.differential_create.kinetics.MemberState differentialcreate$member() {
		return differentialcreate$memberState;
	}

	@Unique
	private double differentialcreate$pendingNetworkSpeed = Double.NaN;

	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$captureWorkingTick(CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.MachineActivity.beforeTick((KineticBlockEntity) (Object) this);
	}

	/** New and loaded machines participate in topology even at standstill. */
	@Inject(method = "initialize", at = @At("RETURN"))
	private void differentialcreate$initializePhysics(CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.NetworkTopology.mark((KineticBlockEntity) (Object) this);
	}

	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$writeNetworkDynamics(CompoundTag tag, HolderLookup.Provider registries,
		boolean clientPacket, CallbackInfo ci) {
		NetworkDynamics.writeNetworkDynamics((KineticBlockEntity) (Object) this, tag, clientPacket);
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$readNetworkDynamics(CompoundTag tag, HolderLookup.Provider registries,
		boolean clientPacket, CallbackInfo ci) {
		NetworkDynamics.readNetworkDynamics(tag, clientPacket, (KineticBlockEntity) (Object) this);
	}

	/**
	 * Reports the physical speed (ratio × network ω). getTheoreticalSpeed()
	 * remains the nominal reference used by Create's source/render contexts.
	 */
	@Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$physicalSpeed(CallbackInfoReturnable<Float> cir) {
		NetworkDynamics.physicalSpeed((KineticBlockEntity) (Object) this, cir);
	}

	@Inject(method = "calculateStressApplied", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$pumpHeadLoad(CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof com.diliupengyun.differential_create.kinetics.IPumpDynamics pump)
			cir.setReturnValue(cir.getReturnValue() * (1 + pump.differentialcreate$head() / 16));
	}

	@Inject(method = "isSpeedRequirementFulfilled", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$continuousProcessing(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity mixer)
			cir.setReturnValue(mixer.getSpeed() != 0);
	}

	/**
	 * Overstress shutdown is removed (DESIGN.md §2): insufficient power shows
	 * up as the network slowing down, not as a halt. Stress accounting is
	 * kept intact.
	 */
	@Redirect(method = "updateFromNetwork",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$neverOverstressedFromNetwork() {
		return false;
	}

	@Redirect(method = "read",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$neverOverstressedFromNbt() {
		return false;
	}

	/**
	 * Vanilla stress lines in the goggle tooltip are nominal bookkeeping
	 * (e.g. "256 su at current speed") with no meaning in the physical model
	 * — suppress them by short-circuiting the stress-impact gate inside the
	 * tooltip method only.
	 */
	@Redirect(method = "addToGoggleTooltip",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$suppressVanillaStressLines() {
		return false;
	}

	/**
	 * Appends the physical readouts after the vanilla goggle content
	 * (DESIGN.md §8), with a spacer line first so the overlay icon does not
	 * cover the first row.
	 */
	@Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$appendPhysicsInfo(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		KineticGoggleInfo.append((KineticBlockEntity) (Object) this, tooltip, isPlayerSneaking);
		cir.setReturnValue(true);
	}

	/**
	 * Physical networks may be stationary or have no generator. Their source
	 * tree and membership are rebuilt by topology events, so vanilla's
	 * generator-dependent validation must not discard them.
	 */
	@Inject(method = "validateKinetics", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$skipValidationWhileCoasting(CallbackInfo ci) {
		KineticBlockEntity self = (KineticBlockEntity) (Object) this;
		Level level = self.getLevel();
		if (level == null || level.isClientSide || !self.hasNetwork())
			return;
		KineticNetwork network = self.getOrCreateNetwork();
		if (network == null)
			return;
		ci.cancel();
	}

	/**
	 * Delivers machine speed notifications and fallback visual updates.
	 * ClientNetworkSpeeds also refreshes every member together whenever its
	 * network's shared rendering coefficients change, even below this threshold.
	 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$refreshVisualOnSpeedChange(CallbackInfo ci) {
		KineticBlockEntity self = (KineticBlockEntity) (Object) this;
		Level level = self.getLevel();
		if (level == null)
			return;
		float speed = self.getSpeed();
		if (!level.isClientSide) {
			com.diliupengyun.differential_create.kinetics.InertiaRegistry.checkAttachments(self);
			float previous = differentialcreate$memberState.notifiedSpeed;
			if (Float.isNaN(previous) || Math.signum(previous) != Math.signum(speed)
				|| Math.abs(previous - speed) >= Math.max(0.05f, Math.abs(speed) * 0.005f)) {
				differentialcreate$memberState.notifiedSpeed = speed;
				self.onSpeedChanged(Float.isNaN(previous) ? 0 : previous);
			}
			return;
		}
		float last = differentialcreate$lastVisualSpeed;
		long gameTime = level.getGameTime();
		// While slipping or open, the output may accelerate/coast independently
		// even if the clutch's own input network remains at a constant speed.
		if (self instanceof com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity
			&& !((com.diliupengyun.differential_create.kinetics.IClutchDynamics) self).differentialcreate$isLocked())
			dev.engine_room.flywheel.lib.visualization.VisualizationHelper.queueUpdate(self);
		if (Float.isNaN(last) || Math.signum(speed) != Math.signum(last)
			|| Math.abs(speed - last) > 0.1f && gameTime - differentialcreate$lastVisualTick >= 2) {
			differentialcreate$lastVisualSpeed = speed;
			differentialcreate$lastVisualTick = gameTime;
			dev.engine_room.flywheel.lib.visualization.VisualizationHelper.queueUpdate(self);
			if (self instanceof com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity)
				self.onSpeedChanged(last);
		}
	}

	@Unique
	private float differentialcreate$lastVisualSpeed = Float.NaN;
	@Unique
	private long differentialcreate$lastVisualTick = -100;

	@Override
	public float differentialcreate$getLastVisualSpeed() {
		return differentialcreate$lastVisualSpeed;
	}

	@Override
	public void differentialcreate$setLastVisualSpeed(float speed) {
		this.differentialcreate$lastVisualSpeed = speed;
	}

	@Override
	public long differentialcreate$getLastVisualTick() {
		return differentialcreate$lastVisualTick;
	}

	@Override
	public void differentialcreate$setLastVisualTick(long tick) {
		this.differentialcreate$lastVisualTick = tick;
	}

	@Override
	public double differentialcreate$getPendingNetworkSpeed() {
		return differentialcreate$pendingNetworkSpeed;
	}

	@Override
	public void differentialcreate$setPendingNetworkSpeed(double speed) {
		this.differentialcreate$pendingNetworkSpeed = speed;
	}

	@Override
	public void differentialcreate$clearPendingNetworkSpeed() {
		this.differentialcreate$pendingNetworkSpeed = Double.NaN;
	}

}
