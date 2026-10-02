package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.diliupengyun.differential_create.kinetics.MachinePhase;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.WorkAccumulator;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeployerBlockEntity.class)
public abstract class DeployerBlockEntityMixin implements ShaftWork {
	// Keep the held item and use/punch mode in the normal view.
	@Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$energyView(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		if (!isPlayerSneaking) return;
		KineticGoggleInfo.append((DeployerBlockEntity) (Object) this, tooltip, true);
		cir.setReturnValue(true);
	}

	@Inject(method = "addToGoggleTooltip", at = @At("RETURN"))
	private void differentialcreate$dynamicsView(List<Component> tooltip, boolean isPlayerSneaking,
		CallbackInfoReturnable<Boolean> cir) {
		if (!isPlayerSneaking)
			KineticGoggleInfo.append((DeployerBlockEntity) (Object) this, tooltip, false);
	}

	@Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/IRotate$StressImpact;isEnabled()Z"))
	private boolean differentialcreate$suppressNominalStress() { return false; }


	@Shadow protected int timer;
	@Unique private boolean differentialcreate$event;
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V"))
	private void differentialcreate$once(DeployerBlockEntity self,
		Operation<Void> original) {
		if (!differentialcreate$event) original.call(self);
	}
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		if (!differentialcreate$event && MachineWork.deferred((DeployerBlockEntity) (Object) this)
			&& differentialcreate$working()) ci.cancel();
	}
	@Unique private boolean differentialcreate$working() {
		String phase = MachinePhase.of((DeployerBlockEntity) (Object) this);
		return phase.equals("EXPANDING") || phase.equals("RETRACTING");
	}
	@Override public double differentialcreate$remainingTravel() {
		// A loaded hand cannot run Create's action callback until initialize() restores its player.
		if (((DeployerBlockEntity) (Object) this).getPlayer() == null) return Double.POSITIVE_INFINITY;
		return differentialcreate$working() ? Math.max(0, timer - differentialcreate$work.remainder()) / 2 : Double.POSITIVE_INFINITY;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		timer -= differentialcreate$work.advance(travel * 2);
		DeployerBlockEntity self = (DeployerBlockEntity) (Object) this;
		self.setChanged();
		if (complete) {
			timer = 0;
			differentialcreate$work.reset();
			String phase = MachinePhase.of(self);
			MachineWork.event(() -> {
				// A paired deployer's fist-bump callback may already have retracted this hand.
				if (self.isRemoved() || !phase.equals(MachinePhase.of(self))) return;
				differentialcreate$event = true;
				try {
					self.tick();
					if (phase.equals("EXPANDING") && MachinePhase.of(self).equals("RETRACTING") && timer == 1000)
						differentialcreate$processTransportedItem(self);
				} finally { differentialcreate$event = false; }
			});
		}
	}
	@Unique private void differentialcreate$processTransportedItem(DeployerBlockEntity self) {
		if (self.getBlockState().getValue(BlockStateProperties.FACING) != Direction.DOWN) return;
		var handler = BlockEntityBehaviour.get(self.getLevel(), self.getBlockPos().below(2), TransportedItemStackHandlerBehaviour.TYPE);
		if (handler == null) return;
		// Process at actual contact, then retire Create's timer==1000 marker so a
		// later depot/belt tick cannot apply the same stroke twice. Repay the one
		// integer unit before retraction advances: its shaft-work budget stays 500.
		timer = 999; differentialcreate$work.borrowOne();
		var held = new ArrayList<TransportedItemStack>();
		handler.handleProcessingOnAllItems(stack -> {
			if (stack.locked && !stack.stack.isEmpty()) held.add(stack);
			return TransportedResult.doNothing();
		});
		for (var stack : held) {
			var recipe = self.getRecipe(stack.stack);
			if (recipe == null) continue;
			BeltDeployerCallbacks.activate(stack, handler, self, recipe.value());
			break; // One workpiece per paid stroke, using Create's recipe and material rules.
		}
	}
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;getSpeed()F"))
	private float differentialcreate$completedAtRest(float speed) { return differentialcreate$event && speed == 0 ? Float.MIN_NORMAL : speed; }
	@Inject(method = "start", at = @At("HEAD"))
	private void differentialcreate$start(CallbackInfo ci) { differentialcreate$work.reset(); }

	@Unique private final WorkAccumulator differentialcreate$work = new WorkAccumulator();
	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;getTimerSpeed()I", ordinal = 0))
	private int differentialcreate$shaftTravel(DeployerBlockEntity self) {
		if (!self.getLevel().isClientSide) self.setChanged();
		return differentialcreate$work.advance(Math.abs(self.getSpeed()) * 2.0);
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCWorkFraction", differentialcreate$work.remainder());
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$work.restore(tag.getDouble("DCWorkFraction"));
	}
}
