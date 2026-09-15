package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace nominal-speed cascades with one atomic component rebuild. */
@Mixin(RotationPropagator.class)
public abstract class RotationPropagatorMixin {
	@Inject(method = "handleAdded", at = @At("HEAD"), cancellable = true)
	private static void differentialcreate$added(Level level, BlockPos pos, KineticBlockEntity be, CallbackInfo ci) {
		if (be != null) NetworkTopology.mark(be);
		ci.cancel();
	}
	@Inject(method = "handleRemoved", at = @At("HEAD"), cancellable = true)
	private static void differentialcreate$removed(Level level, BlockPos pos, KineticBlockEntity be, CallbackInfo ci) {
		if (be != null) NetworkTopology.mark(be);
		ci.cancel();
	}
}