package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Native pole/shaft endpoints bound accepted movement, rather than an animation duration. */
final class PonderLinearMotion {
    private final KineticBlockEntity actuator;
    private final Direction.Axis axis;
    private final double sign, initialExtension;
    private final double floorAllowance;
    private final double initialTravel;
    private double previousAngle, position;
    private boolean blocked, disassemble;
    boolean blocked() { return blocked; }
    boolean disassemble() { return disassemble; }
    double direction() { return sign; }
    PonderLinearMotion(PonderLevel world, net.createmod.ponder.api.element.WorldSectionElement section,
        KineticBlockEntity actuator, int axis, double angle, double fallbackSign) {
        this.actuator = actuator; this.axis = Direction.Axis.values()[axis]; previousAngle = angle;
        initialTravel = section.getAnimatedOffset().get(this.axis)
            - PonderSimulation.of(world).point(actuator.getBlockPos()).subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(actuator.getBlockPos())).get(this.axis);
        sign = actuator instanceof GantryShaftBlockEntity ? -actuator.getBlockState().getValue(GantryShaftBlock.FACING).getAxisDirection().getStep()
            : actuator instanceof MechanicalPistonBlockEntity ? this.axis == Direction.Axis.Z ? 1 : -1
            : actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity ? -1 : fallbackSign;
        double extension = 0;
        if (actuator instanceof MechanicalPistonBlockEntity) {
            var facing = actuator.getBlockState().getValue(BlockStateProperties.FACING);
            var origin = PonderSimulation.of(world).point(actuator.getBlockPos());
            for (var pos : world.getBlockMap().keySet()) if (PonderSimulation.of(world).physical(world, pos) && AllBlocks.MECHANICAL_PISTON_HEAD.has(world.getBlockState(pos))) {
                var delta = PonderSimulation.of(world).point(pos).subtract(origin);
                double along = delta.get(this.axis);
                if (delta.lengthSqr() - along * along < 1e-6) { extension = along * facing.getAxisDirection().getStep(); break; }
            }
        }
        if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley) extension = pulley.offset;
        initialExtension = extension;
        double ground = Double.POSITIVE_INFINITY;
        if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity) {
            var selection = ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section).differentialcreate$selection();
            for (var cursor : selection) if (!world.getBlockState(cursor).isAir()) {
                var point = PonderSimulation.of(world).point(cursor);
                for (var pos : world.getBlockMap().keySet()) {
                    var state = world.getBlockState(pos);
                    if (selection.test(pos) || state.isAir() || state.getCollisionShape(world, pos).isEmpty() || !PonderSimulation.of(world).physical(world, pos)
                        || com.simibubi.create.api.contraption.BlockMovementChecks.isBrittle(state)) continue;
                    var support = PonderSimulation.of(world).point(pos);
                    if (Math.abs(point.x - support.x) < .01 && Math.abs(point.z - support.z) < .01 && support.y < point.y)
                        ground = Math.min(ground, point.y - support.y - 1);
                }
            }
        }
        floorAllowance = ground;
        if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley)
            PonderSimulation.of(world).pulley(pulley).limit(initialExtension + floorAllowance);
    }
    private double[] range(PonderLevel world) {
        double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
        if (actuator instanceof GantryShaftBlockEntity) {
            var state = world.getBlockState(actuator.getBlockPos());
            if (state.getValue(GantryShaftBlock.POWERED)) return new double[] {position, position};
            low = high = -initialTravel;
            var positive = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            var negative = positive.getOpposite();
            for (int step = 1; step <= 128; step++) {
                var pos = PhysicsPreview.relative(world, actuator.getBlockPos(), BlockPos.ZERO.relative(positive, step));
                var next = world.getBlockState(pos);
                if (!PonderSimulation.of(world).physical(world, pos) || !AllBlocks.GANTRY_SHAFT.has(next)
                    || next.getValue(GantryShaftBlock.FACING) != state.getValue(GantryShaftBlock.FACING)) break;
                high = step - initialTravel;
            }
            for (int step = 1; step <= 128; step++) {
                var pos = PhysicsPreview.relative(world, actuator.getBlockPos(), BlockPos.ZERO.relative(negative, step));
                var next = world.getBlockState(pos);
                if (!PonderSimulation.of(world).physical(world, pos) || !AllBlocks.GANTRY_SHAFT.has(next)
                    || next.getValue(GantryShaftBlock.FACING) != state.getValue(GantryShaftBlock.FACING)) break;
                low = -step - initialTravel;
            }
        } else if (actuator instanceof MechanicalPistonBlockEntity) {
            int poles = 0;
            var origin = PonderSimulation.of(world).point(actuator.getBlockPos());
            for (var pos : world.getBlockMap().keySet()) if (PonderSimulation.of(world).physical(world, pos) && AllBlocks.PISTON_EXTENSION_POLE.has(world.getBlockState(pos))) {
                var difference = PonderSimulation.of(world).point(pos).subtract(origin);
                double along = difference.get(axis);
                if (difference.lengthSqr() - along * along < 1e-6) poles++;
            }
            int step = actuator.getBlockState().getValue(BlockStateProperties.FACING).getAxisDirection().getStep();
            double a = -initialExtension * step, b = (poles - initialExtension) * step;
            low = Math.min(a, b); high = Math.max(a, b);
        }
        return new double[] {low, high};
    }
    PonderSimulation.TravelBounds bounds(PonderLevel world) {
        if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley)
            return PonderSimulation.of(world).pulley(pulley).bounds();
        double[] range = range(world);
        double lead = sign * 1200 / 512;
        double a = (range[0] - position) / lead, b = (range[1] - position) / lead;
        return new PonderSimulation.TravelBounds(Math.min(a, b), Math.max(a, b));
    }
    double advance(PonderLevel world, double angle) {
        double delta = Math.clamp(Math.toRadians(angle - previousAngle) * ContraptionGeometry.LINEAR_RADIUS * sign, -.49, .49);
        previousAngle = angle;
        blocked = false;
        disassemble = false;
        if (actuator instanceof GantryShaftBlockEntity && world.getBlockState(actuator.getBlockPos()).getValue(GantryShaftBlock.POWERED)) {
            blocked = disassemble = true; return position;
        }
        if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley) {
            var motion = PonderSimulation.of(world).pulley(pulley);
            motion.limit(initialExtension + floorAllowance);
            blocked = motion.arrived();
            return initialExtension - pulley.offset;
        }
        double[] range = range(world);
        double low = range[0], high = range[1];
        double next = Math.max(low, Math.min(high, position + delta));
        blocked = Math.abs(next - position) < 1e-10 && Math.abs(delta) > 1e-10;
        position = next;
        return position;
    }
}
