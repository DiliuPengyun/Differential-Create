package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MachineWork;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Motion instructions consume real output travel. DELAY and AWAIT retain game time/redstone. */
@Mixin(SequencedGearshiftBlockEntity.class)
public abstract class SequencedGearshiftBlockEntityMixin implements ShaftWork {
	@Shadow int currentInstruction;
	@Shadow float currentInstructionProgress;
	@Shadow public abstract Instruction getInstruction(int index);
	@Shadow public abstract void run(int index);
	@Unique private double differentialcreate$progress;
	@Unique private boolean differentialcreate$realOutput;
	@Unique private InstructionAccessor differentialcreate$instruction() {
		Instruction instruction = getInstruction(currentInstruction);
		return instruction instanceof InstructionAccessor access ? access : null;
	}
	@Override public KineticBlockEntity differentialcreate$workShaft(KineticBlockEntity controller) {
		var self = (SequencedGearshiftBlockEntity) controller;
		differentialcreate$realOutput = false;
		if (self.getLevel() != null && self.hasSource())
			for (Direction face : Direction.values()) {
				if (face.getAxis() != ((IRotate) self.getBlockState().getBlock()).getRotationAxis(self.getBlockState())
					|| face == self.getSourceFacing()) continue;
				var pos = self.getBlockPos().relative(face);
				if (self.getLevel().isLoaded(pos) && self.getLevel().getBlockEntity(pos) instanceof KineticBlockEntity output
					&& output.hasNetwork() && NetworkTopology.connection(self, output) != 0) {
					differentialcreate$realOutput = true;
					return output;
				}
			}
		return controller;
	}
	@Unique private double differentialcreate$unitsPerTravel() {
		InstructionAccessor instruction = differentialcreate$instruction();
		if (instruction == null) return 0;
		double scale = differentialcreate$realOutput ? 1 : Math.abs(((SequencedGearshiftBlockEntity) (Object) this).getModifier());
		return switch (instruction.differentialcreate$type()) {
			case TURN_ANGLE -> .3 * scale;
			case TURN_DISTANCE -> scale / 512;
			default -> 0;
		};
	}
	@Override public double differentialcreate$remainingTravel() {
		var instruction = differentialcreate$instruction();
		double units = differentialcreate$unitsPerTravel();
		return instruction == null || units == 0 ? Double.POSITIVE_INFINITY
			: Math.max(0, instruction.differentialcreate$value() - differentialcreate$progress) / units;
	}
	@Override public void differentialcreate$advanceTravel(double travel) {
		boolean complete = travel + MachineWork.EPSILON >= differentialcreate$remainingTravel();
		differentialcreate$progress += travel * differentialcreate$unitsPerTravel();
		currentInstructionProgress = (float) differentialcreate$progress;
		var self = (SequencedGearshiftBlockEntity) (Object) this;
		self.setChanged();
		if (complete) {
			int index = currentInstruction;
			MachineWork.event(() -> { if (!self.isRemoved() && currentInstruction == index) run(index + 1); });
		}
	}
	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$defer(CallbackInfo ci) {
		var instruction = differentialcreate$instruction();
		if (MachineWork.deferred((SequencedGearshiftBlockEntity) (Object) this)
			&& instruction != null && instruction.differentialcreate$type().needsPropagation()) ci.cancel();
	}
	@Inject(method = "onSpeedChanged", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;onSpeedChanged(F)V",
		shift = At.Shift.AFTER), cancellable = true)
	private void differentialcreate$keepProgress(float previous, CallbackInfo ci) { ci.cancel(); }
	@Inject(method = "run", at = @At("RETURN"))
	private void differentialcreate$begin(int index, CallbackInfo ci) {
		differentialcreate$progress = currentInstructionProgress;
		NetworkTopology.mark((SequencedGearshiftBlockEntity) (Object) this);
	}
	@Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		tag.putDouble("DCInstructionProgress", differentialcreate$progress);
	}
	@Inject(method = "read", at = @At("RETURN"))
	private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
		differentialcreate$progress = tag.contains("DCInstructionProgress")
			? tag.getDouble("DCInstructionProgress") : currentInstructionProgress;
	}
}
