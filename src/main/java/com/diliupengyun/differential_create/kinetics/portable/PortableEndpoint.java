package com.diliupengyun.differential_create.kinetics.portable;

import com.simibubi.create.content.contraptions.Contraption;

/** Shared mechanical endpoint. Storage extensions can bind their own capabilities to session(). */
public interface PortableEndpoint {
    DockingState differentialcreate$docking();
    void differentialcreate$openStorage(Contraption contraption, float distance);
    void differentialcreate$closeStorage();
    void differentialcreate$attachStorage(Contraption contraption, float distance);
    boolean differentialcreate$nativePermission();
    void differentialcreate$animate(float progress, float distance);
    int differentialcreate$timeout();
}
