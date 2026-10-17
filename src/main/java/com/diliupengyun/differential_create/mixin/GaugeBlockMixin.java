package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.LoadGaugeBehaviour;
import com.diliupengyun.differential_create.kinetics.LoadGaugeScale;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeScale;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GaugeBlock.class)
public abstract class GaugeBlockMixin {
	@Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$parkedInstrument(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (level.getBlockEntity(pos) instanceof StressGaugeBlockEntity gauge
			&& !LoadGaugeScale.available(LoadGaugeBehaviour.reading(gauge))) ci.cancel();
	}

	@Inject(method = "getAnalogOutputSignal", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$rangeSignal(BlockState state, Level level, BlockPos pos,
		CallbackInfoReturnable<Integer> cir) {
		if (level.getBlockEntity(pos) instanceof SpeedGaugeBlockEntity gauge)
			cir.setReturnValue(SpeedGaugeScale.signal(NetworkDynamics.preciseSpeed(gauge), SpeedGaugeRangeBehaviour.rangeOf(gauge)));
		else if (level.getBlockEntity(pos) instanceof StressGaugeBlockEntity gauge)
			cir.setReturnValue(LoadGaugeScale.signal(LoadGaugeBehaviour.reading(gauge)));
	}
}
