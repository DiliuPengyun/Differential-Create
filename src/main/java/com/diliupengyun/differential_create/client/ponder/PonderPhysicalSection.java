package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.AnimateWorldSectionInstruction;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Presentation transforms stay scripted; attached machinery follows its integrated actuator. */
public final class PonderPhysicalSection extends PonderInstruction {
    private final ElementLink<WorldSectionElement> link;
    private final Vec3 delta;
    private final int duration;
    private final boolean rotation;
    private final PonderInstruction presentation;
    private boolean started, physical;
    private int age;

    public PonderPhysicalSection(ElementLink<WorldSectionElement> link, Vec3 delta, int duration, boolean rotation) {
        this.link = link; this.delta = delta; this.duration = duration; this.rotation = rotation;
        presentation = rotation ? AnimateWorldSectionInstruction.rotate(link, delta, duration)
            : AnimateWorldSectionInstruction.move(link, delta, duration);
    }
    @Override public boolean isComplete() { return started && (physical || presentation.isComplete()); }
    @Override public void reset(PonderScene scene) { started = physical = false; age = 0; presentation.reset(scene); }
    @Override public void tick(PonderScene scene) {
        age++;
        if (!started && age == 1 && !rotation && duration > 0 && scene.getId().getPath().startsWith("cart_")) return;
        if (!started) {
            started = true;
            var section = scene.resolve(link);
            if (section != null && duration > 0) physical = attach(scene, section);
            if (section != null && duration == 0) PonderSimulation.of(scene.getWorld()).release(section);
        }
        if (!physical) presentation.tick(scene);
    }
    private boolean attach(PonderScene scene, WorldSectionElement section) {
        var world = scene.getWorld();
        var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
        if (selection == null || delta.lengthSqr() == 0) return false;
        if (rotation) for (var pos : selection)
            if (world.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity) {
                var below = com.diliupengyun.differential_create.kinetics.PhysicsPreview.relative(world, pos, new BlockPos(0, -1, 0));
                if (world.getBlockEntity(below) instanceof com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity valve) {
                    PonderSimulation.of(world).valveHandle(section, valve);
                    return true;
                }
            }
        if (!rotation && PonderSimulation.of(world).attachCart(section, delta, duration)) return true;
        for (var pos : selection) if (!rotation && world.getBlockEntity(pos) instanceof com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity) {
            // A movement instruction is a driver operation, never a prescribed displacement.
            PonderSimulation.of(world).driveTrain(world, section, delta.x, duration);
            return true;
        }
        int axis = Math.abs(delta.x) > Math.abs(delta.y) ? Math.abs(delta.x) > Math.abs(delta.z) ? 0 : 2
            : Math.abs(delta.y) > Math.abs(delta.z) ? 1 : 2;
        if (!rotation && PonderSimulation.of(world).followCarriage(world, section, axis)) return true;
        if (!rotation) {
            var shared = PonderSimulation.of(world).adjacentActuator(world, section, axis);
            if (shared != null) {
                double speed = PonderKinetics.of(world).commandedSpeed(shared);
                double sign = Math.signum(delta.get(net.minecraft.core.Direction.Axis.values()[axis])) * (speed == 0 ? 1 : Math.signum(speed));
                PonderSimulation.of(world).attach(section, shared, false, axis, sign);
                return true;
            }
        }
        Vec3 centre = ((PonderWorldSectionAccessor) section).differentialcreate$centre();
        KineticBlockEntity actuator = null;
        double best = Double.POSITIVE_INFINITY;
        for (var candidatePos : world.getBlockMap().keySet()) if (world.getBlockEntity(candidatePos) instanceof KineticBlockEntity candidate
            && (rotation ? candidate instanceof IBearingBlockEntity : candidate instanceof LinearActuatorBlockEntity || candidate instanceof GantryCarriageBlockEntity)) {
            if (!((com.diliupengyun.differential_create.kinetics.PhysicsPreview) world).differentialcreate$physical(candidatePos)) continue;
            if (!rotation && candidate instanceof GantryCarriageBlockEntity) {
                var face = candidate.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
                var behind = com.diliupengyun.differential_create.kinetics.PhysicsPreview.neighbor(world, candidate.getBlockPos(), face.getOpposite());
                if (!(world.getBlockEntity(behind) instanceof com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity shaft)
                    || shaft.getBlockState().getValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING).getAxis().ordinal() != axis) continue;
            }
            double score = rotation ? centre.add(section.getAnimatedOffset()).distanceToSqr(PonderSimulation.of(world).point(candidatePos).add(.5, .5, .5)) : Double.POSITIVE_INFINITY;
            if (!rotation) for (var pos : selection)
                score = Math.min(score, PonderSimulation.of(world).point(pos).distanceToSqr(PonderSimulation.of(world).point(candidatePos)));
            // A fully extended rope can be much longer than the adjacent-block search radius.
            if (!rotation && axis == 1 && candidate instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley) {
                var hook = PonderSimulation.of(world).point(candidatePos).add(0, -pulley.offset - 1, 0);
                for (var pos : selection) if (!world.getBlockState(pos).isAir())
                    score = Math.min(score, PonderSimulation.of(world).point(pos).distanceToSqr(hook));
            }
            if (score < best && score <= (rotation ? 2.01 : 4.01)) { actuator = candidate; best = score; }
        }
        if (actuator == null) return false;
        if (rotation && actuator instanceof com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity clock) {
            PonderSimulation.of(world).clockwork().request(section, clock, axis, delta.get(net.minecraft.core.Direction.Axis.values()[axis]));
            return true;
        }
        if (actuator instanceof GantryCarriageBlockEntity) {
            var facing = actuator.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
            var shaft = com.diliupengyun.differential_create.kinetics.PhysicsPreview.neighbor(world, actuator.getBlockPos(), facing.getOpposite());
            if (world.getBlockEntity(shaft) instanceof com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity gantry) actuator = gantry;
        }
        double speed = actuator.getSpeed();
        if (Math.abs(speed) < 1e-8) speed = PonderKinetics.of(world).commandedSpeed(actuator);
        double sign = Math.signum(delta.get(net.minecraft.core.Direction.Axis.values()[axis]));
        if (speed != 0) sign *= Math.signum(speed);
        PonderSimulation.of(world).attach(section, actuator, rotation, axis, sign);
        return true;
    }
}
