package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.IBlockBreakingWork;
import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A hardness unit takes 1000 RPM-ticks; Create still owns drops and tree cutting. */
@Mixin(BlockBreakingKineticBlockEntity.class)
public abstract class BlockBreakingKineticBlockEntityMixin implements IBlockBreakingWork {
	@Shadow protected BlockPos breakingPos;
	@Shadow protected int destroyProgress;
	@Shadow protected int ticksUntilNextProgress;
	@Shadow protected int breakerId;
	@Shadow protected abstract BlockPos getBreakingPos();
	@Shadow protected abstract boolean shouldRun();
	@Shadow public abstract boolean canBreak(BlockState state, float hardness);
	@Shadow public abstract void onBlockBroken(BlockState state);
	@Unique private BlockState differentialcreate$target;
	@Unique private double differentialcreate$progress;
	@Unique private final ShaftWork differentialcreate$work = new ShaftWork() {
		@Override public double differentialcreate$remainingTravel() { return differentialcreate$remaining(); }
		@Override public void differentialcreate$advanceTravel(double travel) { differentialcreate$advance(travel); }
	};
	@Override public ShaftWork differentialcreate$breakingWork() { return differentialcreate$work; }
	@Unique private boolean differentialcreate$supported() {
		return (Object) this instanceof DrillBlockEntity || (Object) this instanceof SawBlockEntity saw
			&& saw.getBlockState().getValue(BlockStateProperties.FACING).getAxis().isHorizontal();
	}
	@Unique private double differentialcreate$remaining() {
		var self = (BlockBreakingKineticBlockEntity) (Object) this;
		if (!differentialcreate$supported() || self.getLevel() == null || !shouldRun()) return Double.POSITIVE_INFINITY;
		BlockPos pos = getBreakingPos();
		if (!self.getLevel().isLoaded(pos)) return Double.POSITIVE_INFINITY;
		BlockState target = self.getLevel().getBlockState(pos);
		if (!pos.equals(breakingPos) || differentialcreate$target != target) {
			if (breakingPos != null && destroyProgress > 0) self.getLevel().destroyBlockProgress(breakerId, breakingPos, -1);
			breakingPos = pos;
			differentialcreate$target = target;
			differentialcreate$progress = 0;
			destroyProgress = 0;
		}
		float hardness = target.getDestroySpeed(self.getLevel(), pos);
		if (!canBreak(target, hardness)) return Double.POSITIVE_INFINITY;
		return Math.max(0, Math.max(.1, hardness) * 1000 - differentialcreate$progress);
	}
	@Unique private void differentialcreate$advance(double travel) {
		var self = (BlockBreakingKineticBlockEntity) (Object) this;
		double remaining = differentialcreate$remaining();
		if (!Double.isFinite(remaining)) return;
		boolean complete = travel + MachineWork.EPSILON >= remaining;
		differentialcreate$progress += travel;
		double total = Math.max(.1, differentialcreate$target.getDestroySpeed(self.getLevel(), breakingPos)) * 1000;
		destroyProgress = Math.min(9, (int) (10 * differentialcreate$progress / total));
		self.getLevel().destroyBlockProgress(breakerId, breakingPos, destroyProgress);
		self.setChanged();
		if (!complete) return;
		BlockPos pos = breakingPos;
		BlockState target = differentialcreate$target;
		MachineWork.event(() -> {
			if (self.isRemoved() || self.getLevel().getBlockState(pos) != target) return;
			onBlockBroken(target);
			self.getLevel().destroyBlockProgress(breakerId, pos, -1);
			differentialcreate$progress = 0;
			differentialcreate$target = null;
			destroyProgress = 0;
			ticksUntilNextProgress = -1;
		});
	}
	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		if (differentialcreate$supported() && MachineWork.deferred((BlockBreakingKineticBlockEntity) (Object) this)) ci.cancel();
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		if (differentialcreate$target != null) {
			tag.putDouble("DCBreakingTravel", differentialcreate$progress);
			tag.put("DCBreakingTarget", NbtUtils.writeBlockState(differentialcreate$target));
		}
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$target = tag.contains("DCBreakingTarget")
			? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("DCBreakingTarget")) : null;
		differentialcreate$progress = tag.getDouble("DCBreakingTravel");
	}
}
