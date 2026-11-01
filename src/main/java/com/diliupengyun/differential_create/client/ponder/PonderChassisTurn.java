package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.kinetics.PositionServo;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.minecraft.core.BlockPos;

/** Off-stage operator for finite chassis demonstrations, including braking and holding. */
public final class PonderChassisTurn extends PonderInstruction {
    private final BlockPos bearing;
    private final double degrees, cruise;
    private final int limit;
    private int elapsed;
    private double target;
    private boolean started, braking, complete;

    public PonderChassisTurn(BlockPos bearing, float degrees, int duration) {
        this.bearing = bearing.immutable();
        this.degrees = degrees;
        cruise = Math.abs(degrees / (.3 * duration));
        limit = duration + 300;
    }

    @Override public boolean isComplete() { return complete; }
    @Override public void reset(PonderScene scene) { elapsed = 0; started = braking = complete = false; }

    @Override public void tick(PonderScene scene) {
        var world = scene.getWorld();
        if (!(world.getBlockEntity(bearing) instanceof KineticBlockEntity be)) { complete = true; return; }
        var physics = PonderKinetics.of(world);
        Double angle = physics.outputAngle(be, 1);
        if (!started) {
            started = true;
            target = (angle == null ? 0 : angle) + degrees;
            // The next rotateSection instructions choose the new attachment
            // range. A section omitted from that stroke must remain parked.
            var simulation = PonderSimulation.of(world);
            for (var section : simulation.sections(be)) simulation.release(section);
            physics.setAttachmentBrake(bearing, 0);
        }
        double error = target - (angle == null ? 0 : angle);
        braking |= Math.abs(error) < .002 && Math.abs(physics.outputSpeed(bearing)) < .02;
        if (++elapsed >= limit) braking = true; // Do not hang the player's tutorial on a changed configuration.
        physics.boundary(world, bearing, braking ? 0 : (float) Math.clamp(error, -cruise, cruise));
        if (braking) {
            physics.setAttachmentBrake(bearing, PositionServo.TORQUE);
            complete = Math.abs(physics.outputSpeed(bearing)) < 1e-6;
        }
    }

    public static boolean parked(PonderScene scene, BlockPos bearing) {
        return Math.abs(PonderKinetics.of(scene.getWorld()).outputSpeed(bearing)) < 1e-6;
    }
}
