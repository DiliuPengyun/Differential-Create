package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.Direction;

/** The physical coordinate that supplies a moving tool's feed and work. */
public interface ActorDrive {
    AbstractContraptionEntity entity();
    AbstractContraptionEntity root();
    Direction.Axis axis();
    boolean rotation();
    boolean stabilized();
    double workRatio(MovementContext context);
    double speed();
    double preferredSpeed();
    ActorContactPath path(MovementContext context);
    default ActorContactPath directionPath(MovementContext context, net.minecraft.world.phys.Vec3 local) {
        var direction = entity().applyRotation(local, 1);
        return ActorContactPath.linear(new ActorContactPath.Point(direction.x, direction.y, direction.z), new ActorContactPath.Point(0, 0, 0));
    }
    double travel();
    default double angle() { return 0; }
    default double angularRate() { return 0; }
    default double allowance(double direction) { return Double.POSITIVE_INFINITY; }
    default int wakeDirection() { return 0; }
    boolean motionActive();
    static ActorDrive of(MovementContext context) {
        var shaft = ContraptionPhysics.drive(context);
        if (shaft != null) return shaft;
        var train = TrainActorMotion.drive(context);
        return train != null ? train : MinecartActorMotion.drive(context);
    }
}
