package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionPhysics;
import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.diliupengyun.differential_create.kinetics.ActorTargets;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContraptionEntity.class)
public abstract class AbstractContraptionEntityMixin {
	@Inject(method = "writeAdditional", at = @At("RETURN"))
	private void differentialcreate$saveOutputTravel(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		if (!packet) ContraptionMotion.save((AbstractContraptionEntity) (Object) this, tag);
	}
	@Inject(method = "readAdditional", at = @At("RETURN"))
	private void differentialcreate$loadOutputTravel(CompoundTag tag, boolean packet, CallbackInfo ci) {
		if (!packet) ContraptionMotion.load((AbstractContraptionEntity) (Object) this, tag);
	}
	@Inject(method = "tickActors", at = @At("HEAD"))
	private void differentialcreate$bindActors(CallbackInfo ci) {
		var self = (AbstractContraptionEntity) (Object) this;
		ContraptionPhysics.bindActors(self);
		if (!self.level().isClientSide && self.getContraption() != null)
			for (var actor : self.getContraption().getActors())
				if (actor.getRight() != null) ContraptionActorWork.of(actor.getRight()).manageMotion();
	}
	@Redirect(method = "tickActors", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/api/behaviour/movement/MovementBehaviour;visitNewPosition(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;)V"))
	private void differentialcreate$sweepOnPhysicalClock(MovementBehaviour behaviour, MovementContext context, BlockPos pos) {
		if (!ContraptionActorWork.of(context).motionManaged()) behaviour.visitNewPosition(context, pos);
	}
	@Inject(method = "setBlock", at = @At("RETURN"))
	private void differentialcreate$changedGeometry(BlockPos pos, StructureBlockInfo info, CallbackInfo ci) {
		ContraptionPhysics.invalidate(((AbstractContraptionEntity) (Object) this).getContraption());
	}
	@Inject(method = "disassemble", at = @At("HEAD"))
	private void differentialcreate$cancelWork(CallbackInfo ci) {
		var self = (AbstractContraptionEntity) (Object) this;
		if (self.getContraption() == null || self.level().isClientSide) return;
		for (var actor : self.getContraption().getActors())
			if (actor.getRight() != null) ContraptionActorWork.of(actor.getRight()).cancel();
		ContraptionPhysics.disassembling(self);
		ContraptionPhysics.detach(self);
	}
	@Inject(method = "remove", at = @At("HEAD"))
	private void differentialcreate$releaseOwner(RemovalReason reason, CallbackInfo ci) {
		var self = (AbstractContraptionEntity) (Object) this;
		if (!self.level().isClientSide && self.getContraption() != null)
			for (var actor : self.getContraption().getActors())
				if (actor.getRight() != null) ActorTargets.forget(ContraptionActorWork.of(actor.getRight()));
		if (!self.level().isClientSide && (reason == RemovalReason.UNLOADED_TO_CHUNK || reason == RemovalReason.UNLOADED_WITH_PLAYER))
			ContraptionPhysics.unloading(self);
		ContraptionPhysics.detach(self);
		ContraptionMotion.forget(self);
	}
}
