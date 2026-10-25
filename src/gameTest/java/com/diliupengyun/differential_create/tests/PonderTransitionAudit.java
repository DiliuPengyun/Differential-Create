package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Checks ownership and live bindings at every tick, including the frames between keyframes. */
final class PonderTransitionAudit {
    private record Shown(WorldSectionElement owner, BlockPos template, BlockState state) {}
    private final Map<String, Integer> ages = new HashMap<>();
    private final Map<String, Integer> failures = new LinkedHashMap<>();
    private int frames, endpointChecks, mountChecks, presentationCopies;
    void sample(PonderScene scene) {
        frames++;
        var world = scene.getWorld(); var simulation = PonderSimulation.of(world);
        var physics = PonderKinetics.of(world); var observed = new HashSet<String>();
        var originals = new HashMap<BlockPos, Shown>(); var cells = new HashMap<BlockPos, Shown>();
        scene.forEachVisible(WorldSectionElement.class, owner -> {
            var selection = ((PonderWorldSectionAccessor) owner).differentialcreate$selection();
            if (selection == null) return;
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) owner).transformMS(pose, 1);
            for (var cursor : selection) {
                var pos = cursor.immutable(); var state = world.getBlockState(pos); if (state.isAir()) continue;
                var shown = new Shown(owner, pos, state); var previous = originals.putIfAbsent(pos, shown);
                if (previous != null && previous.owner() != owner) {
                    var be = world.getBlockEntity(pos);
                    boolean samePose = previous.owner().getAnimatedOffset().equals(owner.getAnimatedOffset())
                        && previous.owner().getAnimatedRotation().equals(owner.getAnimatedRotation());
                    boolean functional = be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
                    var fluids = world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, null);
                    if (fluids != null) for (int i = 0; i < fluids.getTanks(); i++) functional |= !fluids.getFluidInTank(i).isEmpty();
                    var items = world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
                    if (items != null) for (int i = 0; i < items.getSlots(); i++) functional |= !items.getStackInSlot(i).isEmpty();
                    if (samePose || functional) watch("duplicate template " + pos.toShortString(), observed, 15);
                    else presentationCopies++; // Native size diagrams reuse empty containers and inert scaffolding.
                }
                var v = pose.last().pose().transformPosition(new org.joml.Vector3f(pos.getX(), pos.getY(), pos.getZ()));
                var cell = new BlockPos(Math.round(v.x), Math.round(v.y), Math.round(v.z));
                if (v.distanceSquared(new org.joml.Vector3f(cell.getX(), cell.getY(), cell.getZ())) > 1e-8) continue;
                previous = cells.putIfAbsent(cell, shown);
                if (previous == null || previous.owner() == owner || previous.template().equals(pos)) continue;
                // A liquid source and a solid container cannot occupy one displayed voxel.
                // Base-plate water cutaways at y=0 are presentation geometry, not a transition.
                if (cell.getY() > 0 && ((!state.getFluidState().isEmpty() && previous.state().isCollisionShapeFullBlock(world, previous.template()))
                    || (!previous.state().getFluidState().isEmpty() && state.isCollisionShapeFullBlock(world, pos))))
                    watch("overlapping source/container " + cell.toShortString() + " from " + pos.toShortString() + " and " + previous.template().toShortString(), observed, 15);
            }
        });
        simulation.mounting().owners().forEach((be, owners) -> {
            mountChecks++;
            var mounted = physics.attachmentState(be.getBlockPos());
            boolean visible = !be.isRemoved() && world.getBlockEntity(be.getBlockPos()) == be
                && owners.stream().anyMatch(owner -> owner.isVisible() && !owner.isEmpty());
            if (world.getBlockEntity(be.getBlockPos()) == be && mounted != null && mounted.active() != visible)
                watch("inactive mounted mass " + be.getBlockPos().toShortString(), observed, 1);
            if (!visible && mounted != null && (mounted.load() != 0 || mounted.actorLoad() != 0))
                watch("inactive mounted load " + be.getBlockPos().toShortString(), observed, 1);
        });
        for (var be : world.getBlockEntities()) if (be instanceof SmartBlockEntity smart && simulation.physical(world, be.getBlockPos())) {
            var pipe = smart.getBehaviour(FluidTransportBehaviour.TYPE); if (pipe == null || pipe.interfaces == null) continue;
            for (var entry : pipe.interfaces.entrySet()) {
                if (!pipe.canHaveFlowToward(be.getBlockState(), entry.getKey())) continue;
                var expected = PhysicsPreview.neighbor(world, be.getBlockPos(), entry.getKey());
                var neighbor = com.simibubi.create.content.fluids.FluidPropagator.getPipe(world, expected);
                if (neighbor != null && neighbor.canHaveFlowToward(neighbor.blockEntity.getBlockState(), entry.getKey().getOpposite())) continue;
                var source = ((IPipePressure) entry.getValue()).differentialcreate$source();
                if (!(source instanceof OpenEndedPipe end)) continue;
                endpointChecks++;
                var handler = end.provideHandler(); var stock = handler == null ? null : handler.getCapability();
                var buffer = FluidKinetics.state(pipe).buffers.get(entry.getKey());
                // Reserved parcels keep their old intake until spent; they must not be discarded.
                if (stock != null && !stock.getFluidInTank(0).isEmpty() || buffer != null && buffer.amount > 0) continue;
                if (!end.getOutputPos().equals(expected)) watch("stale fluid endpoint " + be.getBlockPos().toShortString() + "/" + entry.getKey(), observed, 1);
            }
        }
        ages.keySet().retainAll(observed);
    }
    private void watch(String reason, Set<String> observed, int allowance) {
        if (!observed.add(reason)) return;
        int age = ages.merge(reason, 1, Integer::sum);
        if (age > allowance && (failures.containsKey(reason) || failures.size() < 16)) failures.put(reason, age);
    }
    Map<String, Object> report() { return Map.of("frames", frames, "endpointChecks", endpointChecks, "mountChecks", mountChecks, "presentationCopies", presentationCopies, "failures", failures); }
    void verify(PonderScene scene) {
        if (!failures.isEmpty()) throw new AssertionError("Scene transition residue in " + scene.getId() + ": " + failures);
    }
    static void verifySourceOverlapDetector(PonderScene scene) {
        var world = scene.getWorld(); var pos = new BlockPos(100, 1, 100); var before = world.getBlockState(pos);
        var source = new net.createmod.ponder.foundation.element.WorldSectionElementImpl(net.createmod.ponder.foundation.SelectionImpl.of(
            new net.minecraft.world.level.levelgen.structure.BoundingBox(pos)));
        try {
            world.setBlock(pos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            source.setAnimatedOffset(new net.minecraft.world.phys.Vec3(1 - pos.getX(), 0, 2 - pos.getZ()), true);
            source.forceApplyFade(1); source.setVisible(true); scene.addElement(source);
            var probe = new PonderTransitionAudit(); for (int i = 0; i < 17; i++) probe.sample(scene);
            if (probe.failures.keySet().stream().noneMatch(reason -> reason.startsWith("overlapping source/container")))
                throw new AssertionError("Transition audit failed to detect a displaced old water source covering the honey example");
        } finally { source.setVisible(false); world.setBlock(pos, before, 3); }
    }
}
