package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionPhysics;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GantryContraptionEntity.class)
public abstract class GantryContraptionEntityMixin {
	@Inject(method = "tickContraption", at = @At("RETURN"))
	private void differentialcreate$consumeOutputTravel(CallbackInfo ci) {
		var self = (GantryContraptionEntity) (Object) this;
		if (!self.level().isClientSide) ContraptionMotion.consume(self);
	}
	@Redirect(method = "tickContraption", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/Contraption;stop(Lnet/minecraft/world/level/Level;)V"))
	private void differentialcreate$retainCutAtPhysicalStop(com.simibubi.create.content.contraptions.Contraption contraption, net.minecraft.world.level.Level level) {
		com.diliupengyun.differential_create.kinetics.ContraptionActorWork.stopOnSpeedChange(contraption, level);
	}
	@Unique private double differentialcreate$restoredSpeed = Double.NaN;
	@Unique private double differentialcreate$lastKnownSpeed = Double.NaN;
	@Redirect(method = "checkPinionShaft", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/gantry/GantryShaftBlockEntity;getPinionMovementSpeed()F"))
	private float differentialcreate$integratedStructureTravel(GantryShaftBlockEntity shaft) {
		var self = (GantryContraptionEntity) (Object) this;
		var motion = self.level().isClientSide ? null : ContraptionMotion.linear(self);
		if (motion == null && !self.level().isClientSide && !ContraptionMotion.samplingGeometry()
			&& com.diliupengyun.differential_create.kinetics.ActorFeed.enabled(shaft)) return 0;
		return motion == null ? shaft.getPinionMovementSpeed()
			: (float) motion.dot(net.minecraft.world.phys.Vec3.atLowerCornerOf(shaft.getBlockState().getValue(GantryShaftBlock.FACING).getNormal()));
	}
	@Inject(method = "tickContraption", at = @At("HEAD"))
	private void differentialcreate$attachMass(CallbackInfo ci) {
		var self = (GantryContraptionEntity) (Object) this;
		ContraptionPhysics.bindGantry(self, Double.isFinite(differentialcreate$restoredSpeed)
			? differentialcreate$restoredSpeed : differentialcreate$lastKnownSpeed);
		var shaft = ContraptionPhysics.gantryShaft(self);
		if (shaft != null && !shaft.isRemoved()) {
			differentialcreate$lastKnownSpeed = com.diliupengyun.differential_create.kinetics.ActorFeed.speed(shaft);
			differentialcreate$restoredSpeed = Double.NaN;
		}
	}
	@Redirect(method = "checkPinionShaft", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/gantry/GantryContraptionEntity;disassemble()V"))
	private void differentialcreate$keepPoweredStall(GantryContraptionEntity self) {
		var shaft = ContraptionPhysics.gantryShaft(self);
		if (shaft != null && com.diliupengyun.differential_create.kinetics.ActorFeed.enabled(shaft)
			&& !shaft.getBlockState().getValue(GantryShaftBlock.POWERED)) return;
		// A stationary physical rotor can still be commanded to move: it first has
		// to accelerate the carriage, or overcome a work load. Preserve that assembly.
		if (shaft != null && shaft.getTheoreticalSpeed() != 0 && shaft.getSpeed() == 0
			&& !shaft.getBlockState().getValue(GantryShaftBlock.POWERED)) return;
		self.disassemble();
	}
	@Inject(method = "writeAdditional", at = @At("RETURN"))
	private void differentialcreate$saveMomentum(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		double speed = ContraptionPhysics.gantrySpeedForSave((GantryContraptionEntity) (Object) this, differentialcreate$lastKnownSpeed);
		if (Double.isFinite(speed)) tag.putDouble("DCGantrySpeed", speed);
	}
	@Inject(method = "readAdditional", at = @At("RETURN"))
	private void differentialcreate$restoreMomentum(CompoundTag tag, boolean packet, CallbackInfo ci) {
		differentialcreate$restoredSpeed = tag.contains("DCGantrySpeed") ? tag.getDouble("DCGantrySpeed") : Double.NaN;
		differentialcreate$lastKnownSpeed = differentialcreate$restoredSpeed;
	}
}
