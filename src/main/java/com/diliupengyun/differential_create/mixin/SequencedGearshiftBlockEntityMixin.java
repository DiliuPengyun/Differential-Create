package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.SequencerMotion;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native instruction/redstone/comparator UI, with a mechanical position servo. */
@Mixin(SequencedGearshiftBlockEntity.class)
public abstract class SequencedGearshiftBlockEntityMixin implements ISequencerDynamics {
    @Shadow int currentInstruction;
    @Shadow int currentInstructionDuration;
    @Shadow float currentInstructionProgress;
    @Shadow int timer;
    @Shadow boolean poweredPreviously;
    @Shadow public abstract Instruction getInstruction(int index);
    @Shadow public abstract void run(int index);
    @Unique private final SequencerMotion differentialcreate$motion = new SequencerMotion();
    @Unique private boolean differentialcreate$continuation, differentialcreate$awaitPulse;

    @Override public SequencerMotion differentialcreate$motion() { return differentialcreate$motion; }
    @Override public List<SequencerMotion.Segment> differentialcreate$program() {
        var self = (SequencedGearshiftBlockEntity) (Object) this;
        return self.getInstructions().stream().map(i -> {
            var a = (InstructionAccessor) i;
            var kind = switch (a.differentialcreate$type()) {
                case TURN_ANGLE -> SequencerMotion.Kind.ANGLE;
                case TURN_DISTANCE -> SequencerMotion.Kind.DISTANCE;
                case DELAY -> SequencerMotion.Kind.DELAY;
                case AWAIT -> SequencerMotion.Kind.AWAIT;
                case END -> SequencerMotion.Kind.END;
            };
            int ratio = switch (a.differentialcreate$speedModifier()) {
                case FORWARD -> 1; case FORWARD_FAST -> 2; case BACK -> -1; case BACK_FAST -> -2;
            };
            return new SequencerMotion.Segment(kind, Math.max(0, a.differentialcreate$value()), ratio);
        }).toList();
    }
    @Override public void differentialcreate$finishMotion() {
        differentialcreate$continuation = true;
        try { run(currentInstruction + 1); }
        finally { differentialcreate$continuation = false; }
    }
    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$begin(int index, CallbackInfo ci) {
        var self = (SequencedGearshiftBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        ci.cancel();
        differentialcreate$motion.begin(differentialcreate$program(), index, NetworkDynamics.preciseSpeed(self), differentialcreate$continuation);
        currentInstruction = differentialcreate$motion.index;
        currentInstructionProgress = 0;
        currentInstructionDuration = -1;
        timer = 0;
        differentialcreate$awaitPulse = false;
        // Native geometry limits would hide coasting motion and discard its work.
        self.sequenceContext = null;
        if (self.getLevel() != null && !self.getLevel().isClientSide) {
            int display = currentInstruction < 0 ? self.getLevel().hasNeighborSignal(self.getBlockPos())
                ? self.getBlockState().getValue(SequencedGearshiftBlock.STATE) : 0 : currentInstruction + 1;
            self.getLevel().setBlock(self.getBlockPos(), self.getBlockState().setValue(SequencedGearshiftBlock.STATE, display), Block.UPDATE_ALL);
            self.setChanged(); self.sendData();
            NetworkTopology.mark(self);
        }
    }
    @Inject(method = "getRotationSpeedModifier", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$ports(Direction face, CallbackInfoReturnable<Float> cir) { cir.setReturnValue(1f); }
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$tick(CallbackInfo ci) {
        var self = (SequencedGearshiftBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        ci.cancel();
        if (self.getLevel().isClientSide || self.getLevel().tickRateManager().isFrozen()) return;
        Instruction instruction = getInstruction(currentInstruction);
        if (!(instruction instanceof InstructionAccessor a)) return;
        switch (a.differentialcreate$type()) {
            case TURN_ANGLE -> currentInstructionProgress = (float) (differentialcreate$motion.progress() * 360);
            case TURN_DISTANCE -> currentInstructionProgress = (float) (differentialcreate$motion.progress() * 1200 / 512);
            case DELAY -> {
                if (differentialcreate$motion.settled()) {
                    currentInstructionProgress = ++timer;
                    if (timer >= a.differentialcreate$value()) differentialcreate$finishMotion();
                }
            }
            case AWAIT -> { if (differentialcreate$awaitPulse && differentialcreate$motion.settled()) differentialcreate$finishMotion(); }
            default -> { }
        }
        self.setChanged();
    }
    @Inject(method = "risingFlank", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$await(CallbackInfo ci) {
        if (((SequencedGearshiftBlockEntity) (Object) this).isVirtual()) return;
        Instruction instruction = getInstruction(currentInstruction);
        if (instruction instanceof InstructionAccessor a && a.differentialcreate$type() == com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions.AWAIT) {
            ci.cancel();
            if (poweredPreviously) return;
            poweredPreviously = true;
            differentialcreate$awaitPulse = true;
            if (differentialcreate$motion.settled()) differentialcreate$finishMotion();
        }
    }
    @Inject(method = "onRedstoneUpdate", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$latchStart(boolean powered, boolean running, CallbackInfo ci) {
        var self = (SequencedGearshiftBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        if (currentInstruction < 0 && powered && !running && !self.computerBehaviour.hasAttachedComputer()) {
            ci.cancel(); poweredPreviously = true; run(0);
        }
    }
    @Inject(method = "onSpeedChanged", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;onSpeedChanged(F)V", shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$keepProgress(float previous, CallbackInfo ci) {
        if (!((SequencedGearshiftBlockEntity) (Object) this).isVirtual()) ci.cancel();
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        if (((SequencedGearshiftBlockEntity) (Object) this).isVirtual()) return;
        var m = differentialcreate$motion;
        var servo = new CompoundTag();
        servo.putDouble("Angle", m.angle); servo.putDouble("Speed", m.speed);
        servo.putDouble("PhaseOffset", m.phaseOffset);
        servo.putDouble("Start", m.start); servo.putDouble("Target", m.target);
        servo.putDouble("RequestedTurns", m.requestedTurns);
        servo.putInt("Direction", m.direction); servo.putInt("InputDirection", m.inputDirection);
        servo.putDouble("BrakeHeat", m.brakeHeat); servo.putBoolean("Initialized", m.initialized);
        servo.putDouble("SettleTime", m.settleTime); servo.putDouble("SettlingSpeed", m.settlingSpeed);
        servo.putDouble("BoundarySpeed", m.boundarySpeed); servo.putDouble("BoundaryAngle", m.boundaryAngle); servo.putInt("Transitions", m.transitions);
        servo.putDouble("Gear", m.command.gear()); servo.putDouble("Coupling", m.command.coupling()); servo.putDouble("Brake", m.command.brake());
        servo.putBoolean("Holding", m.command.holding());
        servo.putBoolean("AwaitPulse", differentialcreate$awaitPulse);
        tag.put("DCServo", servo);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        if (((SequencedGearshiftBlockEntity) (Object) this).isVirtual()) return;
        var m = differentialcreate$motion;
        m.index = currentInstruction;
        if (tag.contains("DCServo")) {
            var s = tag.getCompound("DCServo");
            m.angle = s.getDouble("Angle"); m.speed = s.getDouble("Speed");
            m.phaseOffset = s.getDouble("PhaseOffset");
            m.start = s.getDouble("Start"); m.target = s.getDouble("Target");
            m.requestedTurns = s.getDouble("RequestedTurns");
            m.direction = s.getInt("Direction"); m.inputDirection = s.getInt("InputDirection");
            m.brakeHeat = s.getDouble("BrakeHeat"); m.initialized = s.getBoolean("Initialized");
            m.settleTime = s.getDouble("SettleTime"); m.settlingSpeed = s.getDouble("SettlingSpeed");
            m.boundarySpeed = s.getDouble("BoundarySpeed"); m.boundaryAngle = s.getDouble("BoundaryAngle"); m.transitions = s.getInt("Transitions");
            m.command = new SequencerMotion.Command(s.getDouble("Gear"), s.getDouble("Coupling"), s.getDouble("Brake"), 0, 0, 0, s.getBoolean("Holding"));
            differentialcreate$awaitPulse = s.getBoolean("AwaitPulse");
        }
        ((SequencedGearshiftBlockEntity) (Object) this).sequenceContext = null;
    }
}
