package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ISequenceContext;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.SequenceContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

/** Acceleration/stop notifications must not refill an actuator's remaining instruction. */
@Mixin(LinearActuatorBlockEntity.class)
public abstract class LinearActuatorBlockEntityMixin {
	@Shadow protected boolean assembleNextTick;
	@Shadow protected abstract void resetContraptionToOffset();
	@Unique private boolean differentialcreate$colliding;
	@Unique private net.minecraft.world.phys.Vec3 differentialcreate$collisionMotion;
	@Inject(method = "collided", at = @At("HEAD"))
	private void differentialcreate$beginCollision(CallbackInfo ci) {
		var self = (LinearActuatorBlockEntity) (Object) this;
		differentialcreate$colliding = self.getLevel() != null && !self.getLevel().isClientSide
			&& com.diliupengyun.differential_create.kinetics.ActorFeed.enabled(self);
		if (differentialcreate$colliding) differentialcreate$collisionMotion = self.getMotionVector();
	}
	@Inject(method = "collided", at = @At("RETURN"))
	private void differentialcreate$retainCollisionSupport(CallbackInfo ci) {
		if (differentialcreate$colliding)
			com.diliupengyun.differential_create.kinetics.LinearActuatorContact.collided(
				(LinearActuatorBlockEntity) (Object) this, differentialcreate$collisionMotion);
		differentialcreate$colliding = false;
	}
	@Inject(method = "tryDisassemble", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$waitForPhysicalFeed(CallbackInfo ci) {
		// A terrain collision must execute the configured native placement policy.
		if (differentialcreate$colliding) return;
		var self = (LinearActuatorBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide
			|| !com.diliupengyun.differential_create.kinetics.ActorFeed.enabled(self)) return;
		for (var drive : com.diliupengyun.differential_create.kinetics.ContraptionPhysics.drives(self)) {
			boolean busy = drive.entity().getContraption().getActors().stream().anyMatch(a -> a.getRight() != null
				&& com.diliupengyun.differential_create.kinetics.MovingDeployerWork.isHand(a.getRight())
				&& com.diliupengyun.differential_create.kinetics.ContraptionActorWork.of(a.getRight()).hand().pending());
			double direction = Math.signum(com.diliupengyun.differential_create.kinetics.NetworkDynamics.preciseSpeed(self));
			// A newly assembled output starts at rest. Allow its clutch to accelerate
			// away from an endpoint before Create considers the stroke finished.
			if (busy || direction != 0 && drive.allowance(direction) > com.diliupengyun.differential_create.kinetics.MachineWork.EPSILON) {
				ci.cancel(); return;
			}
		}
	}
	@Inject(method = "tryDisassemble", at = @At("RETURN"))
	private void differentialcreate$retainUnilateralEndpoint(CallbackInfo ci) {
		com.diliupengyun.differential_create.kinetics.ContraptionMotion.releaseEndpointWait((LinearActuatorBlockEntity) (Object) this);
	}
	@Inject(method = "attach", at = @At("RETURN"))
	private void differentialcreate$recoverPaidTravel(com.simibubi.create.content.contraptions.ControlledContraptionEntity root, CallbackInfo ci) {
		var self = (LinearActuatorBlockEntity) (Object) this;
		var motion = com.diliupengyun.differential_create.kinetics.ContraptionMotion.linear(root);
		if (self.getLevel().isClientSide || !com.diliupengyun.differential_create.kinetics.ContraptionMotion.restored(root) || motion == null) return;
		double pending = motion.dot(((LinearActuatorAccessor) self).differentialcreate$toMotionVector(1));
		self.offset += (float) pending;
		if (sequencedOffsetLimit >= 0) sequencedOffsetLimit = Math.max(0, sequencedOffsetLimit - Math.abs(pending));
		resetContraptionToOffset(); com.diliupengyun.differential_create.kinetics.ContraptionMotion.consume(root); self.sendData();
	}
	@Redirect(method = "onSpeedChanged", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
		target = "Lcom/simibubi/create/content/contraptions/piston/LinearActuatorBlockEntity;assembleNextTick:Z"))
	private void differentialcreate$keepPhysicalFeed(LinearActuatorBlockEntity self, boolean requested) {
		if (self.running && self.getLevel() != null && !self.getLevel().isClientSide
			&& com.diliupengyun.differential_create.kinetics.ContraptionMotion.managed(self.movedContraption)) return;
		assembleNextTick = requested;
	}
	@Redirect(method = "onSpeedChanged", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
		target = "Lcom/simibubi/create/content/contraptions/piston/LinearActuatorBlockEntity;offset:F"))
	private void differentialcreate$retainPhysicalPosition(LinearActuatorBlockEntity self, float rounded) {
		// A stop or reversal changes velocity, not position. The next native tick
		// must start from the fractional extension used by the physical tool sweep.
		if (self.running && self.getLevel() != null && !self.getLevel().isClientSide
			&& com.diliupengyun.differential_create.kinetics.ContraptionMotion.managed(self.movedContraption)) return;
		self.offset = rounded;
	}
	@Inject(method = "getMotionVector", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$canonicalStoppedMotion(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
		var self = (LinearActuatorBlockEntity) (Object) this;
		if (self.getLevel() != null && !self.getLevel().isClientSide
			&& com.diliupengyun.differential_create.kinetics.ContraptionMotion.managed(self.movedContraption)
			&& cir.getReturnValue().lengthSqr() == 0)
			// Vec3.equals distinguishes negative zero; the native terrain collider
			// must recognize a stopped carriage before choosing a movement direction.
			cir.setReturnValue(net.minecraft.world.phys.Vec3.ZERO);
	}
	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$continueDuringPhysicalSpeedChanges(CallbackInfo ci) {
		var self = (LinearActuatorBlockEntity) (Object) this;
		if (self.running && self.getLevel() != null && !self.getLevel().isClientSide
			&& com.diliupengyun.differential_create.kinetics.ContraptionMotion.managed(self.movedContraption))
			assembleNextTick = false;
	}
	@Inject(method = "tick", at = @At("RETURN"))
	private void differentialcreate$consumeOutputTravel(CallbackInfo ci) {
		var self = (LinearActuatorBlockEntity) (Object) this;
		if (self.getLevel() != null && !self.getLevel().isClientSide)
			com.diliupengyun.differential_create.kinetics.ContraptionMotion.consume(self.movedContraption);
	}
	@Redirect(method = "onSpeedChanged", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/Contraption;stop(Lnet/minecraft/world/level/Level;)V"))
	private void differentialcreate$retainCutAtPhysicalStop(com.simibubi.create.content.contraptions.Contraption contraption, net.minecraft.world.level.Level level) {
		com.diliupengyun.differential_create.kinetics.ContraptionActorWork.stopOnSpeedChange(contraption, level);
	}
	@Shadow protected double sequencedOffsetLimit;
	@Unique private SequenceContext differentialcreate$lastSequence;
	@Unique private double differentialcreate$savedLimit;
	@Unique private boolean differentialcreate$keepLimit;
	@Inject(method = "onSpeedChanged", at = @At("HEAD"))
	private void differentialcreate$remember(float previous, CallbackInfo ci) {
		var context = ((LinearActuatorBlockEntity) (Object) this).sequenceContext;
		differentialcreate$keepLimit = ISequenceContext.same(context, differentialcreate$lastSequence);
		differentialcreate$savedLimit = sequencedOffsetLimit;
	}
	@Inject(method = "onSpeedChanged", at = @At("RETURN"))
	private void differentialcreate$restore(float previous, CallbackInfo ci) {
		if (differentialcreate$keepLimit) sequencedOffsetLimit = differentialcreate$savedLimit;
		differentialcreate$lastSequence = ((LinearActuatorBlockEntity) (Object) this).sequenceContext;
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$restoredSequence(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$lastSequence = ((LinearActuatorBlockEntity) (Object) this).sequenceContext;
	}
}
