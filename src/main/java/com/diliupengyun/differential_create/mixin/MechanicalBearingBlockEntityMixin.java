package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.ISequenceContext;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.FontHelper;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.SequenceContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Mechanical bearings assemble manually; new structures start at rest on the physical rotor. */
@Mixin(MechanicalBearingBlockEntity.class)
public abstract class MechanicalBearingBlockEntityMixin {
	@Shadow protected double sequencedAngleLimit;
	@Shadow protected boolean assembleNextTick;
	@Shadow protected float angle;
	@Shadow protected abstract void applyRotation();
	@Shadow protected abstract boolean isWindmill();
	@Unique private SequenceContext differentialcreate$lastSequence;
	@Unique private double differentialcreate$savedLimit;
	@Unique private boolean differentialcreate$keepLimit;
	@Unique private boolean differentialcreate$wasRunningBeforeAssembly;
	@Unique private float differentialcreate$angleBeforeSpeedChange;
	@Unique private boolean differentialcreate$retainPhysicalAngle;
	@Inject(method = "attach", at = @At("RETURN"))
	private void differentialcreate$recoverPaidTravel(com.simibubi.create.content.contraptions.ControlledContraptionEntity root, CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel().isClientSide || self.getMovedContraption() != root || !ContraptionMotion.restored(root)) return;
		double pending = ContraptionMotion.angular(root);
		angle = (float) ((angle + pending) % 360);
		if (sequencedAngleLimit >= 0) sequencedAngleLimit = Math.max(0, sequencedAngleLimit - Math.abs(pending));
		applyRotation(); ContraptionMotion.consume(root); self.sendData();
	}
	@Inject(method = "tick", at = @At("RETURN"))
	private void differentialcreate$consumeOutputTravel(CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel() != null && !self.getLevel().isClientSide) ContraptionMotion.consume(self.getMovedContraption());
	}
	@Redirect(method = "onSpeedChanged", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/Contraption;stop(Lnet/minecraft/world/level/Level;)V"))
	private void differentialcreate$retainCutAtPhysicalStop(com.simibubi.create.content.contraptions.Contraption contraption, net.minecraft.world.level.Level level) {
		com.diliupengyun.differential_create.kinetics.ContraptionActorWork.stopOnSpeedChange(contraption, level);
	}
	@Inject(method = "getAngularSpeed", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$integratedStructureTravel(CallbackInfoReturnable<Float> cir) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel() == null || self.getLevel().isClientSide) return;
		Double angle = ContraptionMotion.angular(self.getMovedContraption());
		if (angle != null) cir.setReturnValue(angle.floatValue());
	}

	@Redirect(method = "addToTooltip", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/foundation/item/TooltipHelper;addHint(Ljava/util/List;Ljava/lang/String;[Ljava/lang/Object;)V"))
	private void differentialcreate$alignBearingHint(List<Component> tooltip, String hintKey, Object[] messageParams) {
		// The physical readout already reserves a separate row for the icon.
		// Keep Create's hint styles and wrapping without its icon padding.
		tooltip.add(CreateLang.translate(hintKey + ".title").style(ChatFormatting.GOLD).component());
		tooltip.addAll(TooltipHelper.cutTextComponent(CreateLang.translateDirect(hintKey), FontHelper.Palette.GRAY_AND_WHITE));
	}

	@Inject(method = "addBehaviours", at = @At("RETURN"))
	private void differentialcreate$removeAutomaticPlacementSetting(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
		if (!isWindmill()) behaviours.remove(((MechanicalBearingAccessor) this).differentialcreate$getMovementMode());
	}

	@Inject(method = "assemble", at = @At("HEAD"))
	private void differentialcreate$captureBareInertia(CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		differentialcreate$wasRunningBeforeAssembly = self.isRunning();
		if (!self.getLevel().isClientSide && !self.isRunning())
			ElasticKinetics.state(self).lastInertia = InertiaRegistry.getInertia(self);
	}

	@Inject(method = "assemble", at = @At("RETURN"))
	private void differentialcreate$attachRestingStructure(CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel().isClientSide || differentialcreate$wasRunningBeforeAssembly || !self.isRunning()) return;
		if (!isWindmill()) {
			// Reset only the newly assembled output. The upstream rotor keeps its
			// speed; the ordinary elastic contact handles acceleration and fracture.
			var member = ElasticKinetics.state(self);
			member.physicalSpeed = 0;
			member.pendingBearingInertia = Float.NaN;
			member.lastInertia = InertiaRegistry.getInertia(self);
		}
		InertiaRegistry.checkAttachments(self);
		if (self.hasNetwork()) {
			ElasticKinetics.refresh(self.getOrCreateNetwork());
			ElasticKinetics.publishReference(self.getOrCreateNetwork());
		}
		self.sendData();
	}

	@Redirect(method = "onSpeedChanged", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
		target = "Lcom/simibubi/create/content/contraptions/bearing/MechanicalBearingBlockEntity;assembleNextTick:Z"))
	private void differentialcreate$keepAssemblyManual(MechanicalBearingBlockEntity self, boolean requested) {
		// Do not overwrite a pending right-click request when the network rebuilds.
		if (isWindmill()) assembleNextTick = requested;
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void differentialcreate$assembleOnRequest(CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (self.getLevel().isClientSide || isWindmill() || !assembleNextTick) return;
		// MechanicalBearingBlock sets this flag on right-click. Consume it before
		// Create's automatic placement branch, and allow assembly at zero RPM.
		assembleNextTick = false;
		if (!self.isRunning()) self.assemble();
	}

	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$saveAttachedInertia(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		if (!packet && !isWindmill() && self.isRunning()) tag.putFloat("DCBearingInertia", InertiaRegistry.getInertia(self));
	}

	@Inject(method = "disassemble", at = @At("RETURN"))
	private void differentialcreate$clearAttachedInertia(CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		ElasticKinetics.state(self).pendingBearingInertia = Float.NaN;
		if (!self.getLevel().isClientSide && !isWindmill()) {
			InertiaRegistry.checkAttachments(self);
			ElasticKinetics.state(self).lastInertia = InertiaRegistry.getInertia(self);
			if (self.hasNetwork()) ElasticKinetics.refresh(self.getOrCreateNetwork());
		}
	}

	@Inject(method = "onSpeedChanged", at = @At("HEAD"))
	private void differentialcreate$rememberLimit(float previous, CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		differentialcreate$retainPhysicalAngle = self.getLevel() != null && !self.getLevel().isClientSide && ContraptionMotion.managed(self.getMovedContraption());
		differentialcreate$angleBeforeSpeedChange = angle;
		var context = ((MechanicalBearingBlockEntity) (Object) this).sequenceContext;
		differentialcreate$keepLimit = ISequenceContext.same(context, differentialcreate$lastSequence);
		differentialcreate$savedLimit = sequencedAngleLimit;
	}
	@Inject(method = "onSpeedChanged", at = @At("RETURN"))
	private void differentialcreate$restoreLimit(float previous, CallbackInfo ci) {
		if (differentialcreate$retainPhysicalAngle) { angle = differentialcreate$angleBeforeSpeedChange; applyRotation(); }
		if (differentialcreate$keepLimit) sequencedAngleLimit = differentialcreate$savedLimit;
		differentialcreate$lastSequence = ((MechanicalBearingBlockEntity) (Object) this).sequenceContext;
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$restoredSequence(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		var self = (MechanicalBearingBlockEntity) (Object) this;
		differentialcreate$lastSequence = self.sequenceContext;
		if (!packet && !isWindmill()) {
			float inertia = com.diliupengyun.differential_create.kinetics.ContraptionPhysics.readSavedInertia(self, tag, "DCBearingInertia");
			ElasticKinetics.state(self).pendingBearingInertia = self.isRunning() && Float.isFinite(inertia) && inertia >= 0
				? inertia : Float.NaN;
		}
	}
	@Redirect(method = "getAngularSpeed", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/bearing/MechanicalBearingBlockEntity;getGeneratedSpeed()F"))
	private float differentialcreate$physicalWindmillSpeed(MechanicalBearingBlockEntity bearing) {
		// This call is only in Create's windmill branch. Keep its degree/tick
		// conversion and client angle reconciliation, and leave getGeneratedSpeed
		// itself available to the sail-dependent torque characteristic.
		return bearing.getSpeed();
	}
}
