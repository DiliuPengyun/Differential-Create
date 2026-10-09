package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import com.simibubi.create.content.kinetics.transmission.sequencer.InstructionSpeedModifiers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Instruction.class)
public interface InstructionAccessor {
	@Accessor("instruction") SequencerInstructions differentialcreate$type();
	@Accessor("value") int differentialcreate$value();
	@Accessor("speedModifier") InstructionSpeedModifiers differentialcreate$speedModifier();
}
