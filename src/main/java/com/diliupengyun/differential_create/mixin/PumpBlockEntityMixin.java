package com.diliupengyun.differential_create.mixin;

import java.util.Set;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses the existing pipe traversal to measure mean inlet/outlet elevation. */
@Mixin(PumpBlockEntity.class)
public abstract class PumpBlockEntityMixin implements IPumpDynamics {
	@Unique private float differentialcreate$inlet;
	@Unique private float differentialcreate$outlet;
	@Override public float differentialcreate$head() { return Math.max(0, differentialcreate$outlet - differentialcreate$inlet); }

	@Inject(method = "onSpeedChanged", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/fluids/pump/PumpBlockEntity;updatePressureChange()V"), cancellable = true)
	private void differentialcreate$continuousPressure(float previousSpeed, CallbackInfo ci) {
		var self = (PumpBlockEntity) (Object) this;
		// Pipe topology is unchanged while a running pump accelerates or coasts.
		// Resetting it repeatedly keeps its two-tick propagation delay from completing.
		if (previousSpeed != 0 && self.getSpeed() != 0) ci.cancel();
	}

	@Redirect(method = "distributePressureTo", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;addPressure(Lnet/minecraft/core/Direction;ZF)V"))
	private void differentialcreate$recordPressure(FluidTransportBehaviour pipe, Direction side, boolean inbound, float pressure) {
		var connection = pipe.getConnection(side);
		if (connection == null) return;
		((IPipePressure) connection).differentialcreate$addPumpPressure((PumpBlockEntity) (Object) this, inbound, pressure);
		pipe.blockEntity.sendData();
	}

	@Inject(method = "distributePressureTo", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/fluids/pump/PumpBlockEntity;searchForEndpointRecursively(Ljava/util/Map;Ljava/util/Set;Ljava/util/Map;Lnet/createmod/catnip/math/BlockFace;Z)Z"))
	private void differentialcreate$measureHead(Direction side, CallbackInfo ci, @Local Set<BlockFace> targets) {
		PumpBlockEntity self = (PumpBlockEntity) (Object) this;
		float before = differentialcreate$head();
		float elevation = targets.isEmpty() ? side.getStepY()
			: (float) targets.stream().mapToInt(face -> face.getConnectedPos().getY() - self.getBlockPos().getY()).average().orElse(0);
		if (self.isPullingOnSide(side == self.getBlockState().getValue(PumpBlock.FACING))) differentialcreate$inlet = elevation;
		else differentialcreate$outlet = elevation;
		if (Math.abs(before - differentialcreate$head()) > 0.001 && self.hasNetwork()) {
			var data = ((INetworkDynamics) self.getOrCreateNetwork()).differentialcreate$data();
			data.invalidate();
			self.sendData();
		}
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$inlet = 0;
		differentialcreate$outlet = tag.getFloat("DCPumpHead");
	}
}
