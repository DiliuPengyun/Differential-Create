package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.diliupengyun.differential_create.kinetics.IMemberDynamics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
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
	private float differentialcreate$pendingNetworkSpeed = Float.NaN;

	/**
	 * Captures speeds assigned by Create's rotation propagation into the
	 * network's ratio map. Our own code never calls setSpeed, so no recursion
	 * can occur.
	 */
	@Inject(method = "setSpeed", at = @At("HEAD"))
	private void differentialcreate$capturePropagatedSpeed(float speed, CallbackInfo ci) {
		NetworkDynamics.capturePropagatedSpeed((KineticBlockEntity) (Object) this, speed);
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
	 * is deliberately left alone: propagation keeps operating on the nominal
	 * field values.
	 */
	@Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$physicalSpeed(CallbackInfoReturnable<Float> cir) {
		NetworkDynamics.physicalSpeed((KineticBlockEntity) (Object) this, cir);
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
		int sizeBefore = tooltip.size();
		KineticGoggleInfo.append((KineticBlockEntity) (Object) this, tooltip);
		if (tooltip.size() > sizeBefore) {
			tooltip.add(sizeBefore, Component.empty());
			cir.setReturnValue(true);
		}
	}

	/**
	 * Members of a coasting network (no living source: source removed or
	 * deactivated) must not be stripped by the periodic validation: the
	 * network keeps spinning on its own inertia until friction stops it.
	 * Once it is stopped, validation resumes and cleans up the stale source
	 * links.
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
		if (((INetworkDynamics) network).differentialcreate$getSpeed() == 0)
			return;
		for (KineticBlockEntity source : network.sources.keySet())
			if (source.getGeneratedSpeed() != 0)
				return; // a living source drives this network: validate normally
		ci.cancel();
	}

	/**
	 * Drives visual refreshes on the client (DESIGN.md §5.3): rotating
	 * instances are re-anchored whenever the synced speed moved past the
	 * threshold — capped at 10 Hz during ramps, never at steady state.
	 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$refreshVisualOnSpeedChange(CallbackInfo ci) {
		KineticBlockEntity self = (KineticBlockEntity) (Object) this;
		Level level = self.getLevel();
		if (level == null || !level.isClientSide)
			return;
		float speed = self.getSpeed();
		float last = differentialcreate$lastVisualSpeed;
		long gameTime = level.getGameTime();
		if (Math.abs(speed - last) > 0.1f && gameTime - differentialcreate$lastVisualTick >= 2) {
			differentialcreate$lastVisualSpeed = speed;
			differentialcreate$lastVisualTick = gameTime;
			dev.engine_room.flywheel.lib.visualization.VisualizationHelper.queueUpdate(self);
		}
	}

	@Unique
	private float differentialcreate$lastVisualSpeed;
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
	public float differentialcreate$getPendingNetworkSpeed() {
		return differentialcreate$pendingNetworkSpeed;
	}

	@Override
	public void differentialcreate$setPendingNetworkSpeed(float speed) {
		this.differentialcreate$pendingNetworkSpeed = speed;
	}

	@Override
	public void differentialcreate$clearPendingNetworkSpeed() {
		this.differentialcreate$pendingNetworkSpeed = Float.NaN;
	}

}
