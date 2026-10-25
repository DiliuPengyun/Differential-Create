package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** World-down gravity projected through exactly the motion consumed by the entity. */
public final class ContraptionGravity {
    private ContraptionGravity() {}
    public static ShaftPotential capture(KineticBlockEntity shaft) {
        if (!ContraptionPhysics.manages(shaft)) return ShaftPotential.NONE;
        double linear = 0;
        var terms = new ArrayList<ShaftPotential.Harmonic>();
        for (var drive : ContraptionPhysics.drives(shaft)) {
            // Native pauses hold the body in the world. Its support carries gravity;
            // the separately spinning input does not change this body's height.
            if (drive.root().isStalled() || drive.root().getContraption().stalled) continue;
            var shape = ContraptionPhysics.shape(drive.entity().getContraption());
            double weight = shape.mass() * ShaftPotential.WEIGHT;
            var motion = ContraptionMotion.geometry(drive);
            if (!drive.rotation()) {
                linear += weight * motion.translation().y / RotationMath.TICK_MINUTES;
                continue;
            }
            if (drive.axis() == Direction.Axis.Y || motion.angularRate() == 0 || weight == 0) continue;
            // A stabilized child's own orientation is fixed. Only its mounting
            // point orbits the parent; its internal eccentricity adds no parent torque.
            Vec3 local = drive.stabilized() ? Vec3.atLowerCornerOf(drive.mount())
                : new Vec3(shape.centre(0), shape.centre(1), shape.centre(2));
            Vec3 radial = drive.root().applyRotation(local, 1);
            Vec3 tangent = drive.axis() == Direction.Axis.X ? new Vec3(0, -radial.z, radial.y)
                : new Vec3(-radial.y, radial.x, 0);
            double angle = Math.toRadians(motion.angle());
            double cosine = weight * (radial.y * Math.cos(angle) + tangent.y * Math.sin(angle));
            double sine = weight * (tangent.y * Math.cos(angle) - radial.y * Math.sin(angle));
            double frequency = Math.toRadians(motion.angularRate()) / RotationMath.TICK_MINUTES;
            if (cosine != 0 || sine != 0) terms.add(new ShaftPotential.Harmonic(cosine, sine, frequency));
        }
        return linear == 0 && terms.isEmpty() ? ShaftPotential.NONE : new ShaftPotential(linear, terms);
    }
}
