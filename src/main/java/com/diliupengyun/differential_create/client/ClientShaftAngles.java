package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;

/** One face phase for both ordinary meshes and Flywheel instances. */
public final class ClientShaftAngles {
    private ClientShaftAngles() {}

    public record Rotation(KineticBlockEntity owner, Direction.Axis axis, RotationFrame frame, float modelOffset) {
        public float radians() {
            return (float) Math.toRadians((frame.angleAt(ClientKineticAngles.renderTick()) + modelOffset) % 360);
        }

        public RotatingInstance setup(RotatingInstance instance) {
            instance.setup(owner, axis, 0).setRotationOffset(modelOffset).setRotationalSpeed(frame.shaderSpeed());
            instance.rotationOffset = frame.shaderOffset(modelOffset);
            return instance;
        }
    }

    /** A full adjoining shaft owns the seam. Two face-only blocks choose the same endpoint. */
    public static Rotation at(KineticBlockEntity be, Direction face) {
        // Keep a clutch's free-output cache current while its adjoining shaft is visible.
        // Detaching that shaft must continue the last pose, not initialize a new phase.
        var local = own(be, face);
        var neighbour = neighbour(be, face);
        if (neighbour != null && (!hasSeparateFaces(neighbour)
            || hasSeparateFaces(be) && neighbour.getBlockPos().compareTo(be.getBlockPos()) < 0))
            return own(neighbour, face.getOpposite());
        return local;
    }

    public static boolean hasSeparateFaces(KineticBlockEntity be) {
        return be instanceof SplitShaftBlockEntity || be instanceof GearboxBlockEntity;
    }

    public static boolean hasShaft(KineticBlockEntity be, Direction face) {
        return be.getBlockState().getBlock() instanceof IRotate rotate
            && rotate.hasShaftTowards(be.getLevel(), be.getBlockPos(), be.getBlockState(), face);
    }

    public static KineticBlockEntity neighbour(KineticBlockEntity be, Direction face) {
        var level = be.getLevel();
        if (level == null || !hasShaft(be, face)) return null;
        KineticBlockEntity neighbour;
        if (level instanceof PonderLevel ponder) neighbour = PonderKinetics.of(ponder).shaftNeighbour(be, face);
        else {
            var pos = be.getBlockPos().relative(face);
            neighbour = level.isLoaded(pos) && level.getBlockEntity(pos) instanceof KineticBlockEntity kinetic ? kinetic : null;
        }
        return neighbour != null && !neighbour.isRemoved() && hasShaft(neighbour, face.getOpposite()) ? neighbour : null;
    }

    /** No neighbour recursion: independent ports retain their own motion when exposed. */
    private static Rotation own(KineticBlockEntity be, Direction face) {
        var axis = face.getAxis();
        RotationFrame frame;
        if (be instanceof ClutchBlockEntity clutch && output(clutch, face))
            frame = ClientClutchAngles.outputRotation(clutch);
        else if (be instanceof SequencedGearshiftBlockEntity sequencer && output(sequencer, face))
            frame = ClientSequencerAngles.outputRotation(sequencer);
        else {
            frame = ClientKineticAngles.rotationOf(be);
            if (frame == null) frame = fallback(be, axis);
            double modifier = 1;
            if (be instanceof GearboxBlockEntity gearbox && gearbox.hasSource()) {
                var source = gearbox.getSourceFacing();
                if (source.getAxis() == axis) modifier = source == face ? 1 : -1;
                else if (source.getAxisDirection() == face.getAxisDirection()) modifier = -1;
            } else if (be instanceof SplitShaftBlockEntity split) modifier = split.getRotationSpeedModifier(face);
            frame = frame.scaled(modifier);
        }
        return new Rotation(be, axis, frame, ClientKineticAngles.shaftOffsetOf(be, axis));
    }

    private static boolean output(SplitShaftBlockEntity be, Direction face) {
        return be.hasSource() ? face != be.getSourceFacing()
            : be instanceof SequencedGearshiftBlockEntity && face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
    }

    private static RotationFrame fallback(KineticBlockEntity be, Direction.Axis axis) {
        int tick = Minecraft.getInstance().levelRenderer.getTicks();
        // Unrevealed Ponder blocks must not inherit the unrelated world's render clock.
        if (be.getLevel() instanceof PonderLevel) return new RotationFrame(tick, 0, 0);
        double angle = Math.toDegrees(KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis))
            - KineticBlockEntityRenderer.getRotationOffsetForPosition(be, be.getBlockPos(), axis);
        double rate = be.getLevel() != null && be.getLevel().tickRateManager().isFrozen() ? 0 : be.getSpeed() * .3;
        return new RotationFrame(tick, angle - (ClientKineticAngles.renderTick() - tick) * rate, rate);
    }
}
