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
    private double previousAngle, position;
    private boolean blocked;
    boolean blocked() { return blocked; }
    PonderLinearMotion(PonderLevel world, net.createmod.ponder.api.element.WorldSectionElement section,
        KineticBlockEntity actuator, int axis, double angle, double fallbackSign) {
        this.actuator = actuator; this.axis = Direction.Axis.values()[axis]; previousAngle = angle;
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
                    if (selection.test(pos) || state.isAir() || !PonderSimulation.of(world).physical(world, pos)
                        || com.simibubi.create.api.contraption.BlockMovementChecks.isBrittle(state)) continue;
                    var support = PonderSimulation.of(world).point(pos);
                    if (Math.abs(point.x - support.x) < .01 && Math.abs(point.z - support.z) < .01 && support.y < point.y)
                        ground = Math.min(ground, point.y - support.y - 1);
                }
            }
        }
        floorAllowance = ground;
    }
    double advance(PonderLevel world, double angle) {
        double delta = Math.clamp(Math.toRadians(angle - previousAngle) * ContraptionGeometry.LINEAR_RADIUS * sign, -.49, .49);
        previousAngle = angle;
        blocked = false;
        double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
        if (actuator instanceof GantryShaftBlockEntity) {
            var state = world.getBlockState(actuator.getBlockPos());
            if (state.getValue(GantryShaftBlock.POWERED)) { blocked = true; return position; }
            low = high = 0;
            var positive = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            var negative = positive.getOpposite();
            for (int step = 1; step <= 128; step++) {
                var pos = PhysicsPreview.relative(world, actuator.getBlockPos(), BlockPos.ZERO.relative(positive, step));
                var next = world.getBlockState(pos);
                if (!PonderSimulation.of(world).physical(world, pos) || !AllBlocks.GANTRY_SHAFT.has(next)
                    || next.getValue(GantryShaftBlock.FACING) != state.getValue(GantryShaftBlock.FACING)) break;
                high = step;
            }
            for (int step = 1; step <= 128; step++) {
                var pos = PhysicsPreview.relative(world, actuator.getBlockPos(), BlockPos.ZERO.relative(negative, step));
                var next = world.getBlockState(pos);
                if (!PonderSimulation.of(world).physical(world, pos) || !AllBlocks.GANTRY_SHAFT.has(next)
                    || next.getValue(GantryShaftBlock.FACING) != state.getValue(GantryShaftBlock.FACING)) break;
                low = -step;
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
        } else if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity) {
            int range = ((com.diliupengyun.differential_create.mixin.LinearActuatorAccessor) actuator).differentialcreate$extensionRange();
            low = -Math.min(floorAllowance, range - initialExtension); high = initialExtension;
        }
        double next = Math.max(low, Math.min(high, position + delta));
        blocked = Math.abs(next - position) < 1e-10 && Math.abs(delta) > 1e-10;
        position = next;
        if (actuator instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley)
            pulley.animateOffset((float) (initialExtension - position));
        return position;
    }
}
