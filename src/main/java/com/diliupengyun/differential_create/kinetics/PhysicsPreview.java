package com.diliupengyun.differential_create.kinetics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** An isolated preview opts into production simulation without pretending to be a server. */
public interface PhysicsPreview {
    boolean differentialcreate$physical(BlockPos pos);
    long differentialcreate$time();
    double differentialcreate$workTravel(BlockPos pos);
    BlockPos differentialcreate$relative(BlockPos origin, BlockPos offset);
    double differentialcreate$height(BlockPos pos);
    default ActorDrive differentialcreate$actorDrive(com.simibubi.create.content.contraptions.behaviour.MovementContext context) { return null; }
    default boolean differentialcreate$mountedTool(BlockPos pos) { return false; }
    default com.diliupengyun.differential_create.trains.TrainMechanics.Engine differentialcreate$trainBoundary(com.simibubi.create.content.trains.entity.Train train) { return null; }

    static BlockPos relative(Level world, BlockPos origin, BlockPos offset) {
        return world instanceof PhysicsPreview preview ? preview.differentialcreate$relative(origin, offset) : origin.offset(offset);
    }
    static BlockPos neighbor(Level world, BlockPos origin, net.minecraft.core.Direction face) {
        return relative(world, origin, BlockPos.ZERO.relative(face));
    }
    static double height(Level world, BlockPos pos) {
        return world instanceof PhysicsPreview preview ? preview.differentialcreate$height(pos) : pos.getY();
    }

    static double travel(com.simibubi.create.content.kinetics.base.KineticBlockEntity shaft) {
        return shaft.getLevel() instanceof PhysicsPreview preview ? preview.differentialcreate$workTravel(shaft.getBlockPos())
            : Math.abs(shaft.getSpeed());
    }

    static boolean enabled(Level world) { return world instanceof PhysicsPreview; }
    static long time(Level world) {
        return world instanceof PhysicsPreview preview ? preview.differentialcreate$time() : world.getGameTime();
    }
}
