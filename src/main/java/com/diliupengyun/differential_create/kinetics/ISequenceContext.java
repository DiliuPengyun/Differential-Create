package com.diliupengyun.differential_create.kinetics;

import java.util.UUID;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.SequenceContext;

/** An instruction identity survives NBT but changes for a repeated identical instruction. */
public interface ISequenceContext {
	UUID differentialcreate$instructionId();
	void differentialcreate$instructionId(UUID id);
	static boolean same(SequenceContext a, SequenceContext b) {
		return a != null && b != null && ((ISequenceContext) (Object) a).differentialcreate$instructionId()
			.equals(((ISequenceContext) (Object) b).differentialcreate$instructionId());
	}
}
