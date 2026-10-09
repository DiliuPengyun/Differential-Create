package com.diliupengyun.differential_create.kinetics;

import java.util.List;

public interface ISequencerDynamics {
    SequencerMotion differentialcreate$motion();
    List<SequencerMotion.Segment> differentialcreate$program();
    void differentialcreate$finishMotion();
}
