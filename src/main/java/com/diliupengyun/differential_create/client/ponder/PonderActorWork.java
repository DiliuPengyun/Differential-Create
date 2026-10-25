package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

/** Native moving tools use actual contact, the production job costs, and accepted shaft travel. */
final class PonderActorWork implements ActorDrive {
    private static final class Actor {
        final MovementContext context;
        final MovementBehaviour behaviour;
        WorldSectionElement owner;
        Vec3 previous;
        BlockPos cell;
        Actor(MovementContext context, MovementBehaviour behaviour, WorldSectionElement owner) { this.context = context; this.behaviour = behaviour; this.owner = owner; }
    }
    private final PonderLevel world;
    private final KineticBlockEntity actuator;
    private final boolean rotating;
    private final Direction.Axis axis;
    private final List<Actor> actors = new ArrayList<>();
    private final java.util.Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> templates = new java.util.HashMap<>();
    private final MountedContraption storage = new MountedContraption();
    private final OrientedContraptionEntity entity;
    private double previousTravel = Double.NaN, accepted;
    private boolean storageInitialized;
    KineticBlockEntity actuator() { return actuator; }
    boolean active(MovementContext context) {
        return !actuator.isRemoved() && world.getBlockEntity(actuator.getBlockPos()) == actuator && actors.stream().anyMatch(actor -> actor.context == context
            && actor.owner.isVisible() && PonderSimulation.of(world).sections(actuator).contains(actor.owner)
            && world.getBlockEntity(context.localPos) == templates.get(context.localPos)
            && world.getBlockState(context.localPos).getBlock() == context.state.getBlock());
    }
    private void forget(BlockPos pos) {
        for (var iterator = actors.iterator(); iterator.hasNext();) {
            var actor = iterator.next(); if (!actor.context.localPos.equals(pos)) continue;
            ContraptionActorWork.of(actor.context).cancel(); PonderSimulation.of(world).removeActor(actor.context); iterator.remove();
        }
    }

    PonderActorWork(PonderLevel world, WorldSectionElement section, KineticBlockEntity actuator, boolean rotating, int axis) {
        this.world = world; this.actuator = actuator;
        this.rotating = rotating; this.axis = Direction.Axis.values()[axis];
        storage.anchor = BlockPos.ZERO;
        entity = OrientedContraptionEntity.create(world, storage, Direction.SOUTH); storage.entity = entity;
        discover();
    }
    private void discover() {
        boolean changed = false;
        var mountedEntities = new java.util.HashMap<>(templates);
        var owners = PonderSimulation.of(world).sections(actuator); var wanted = new java.util.HashSet<BlockPos>();
        for (var owner : owners) ((PonderWorldSectionAccessor) owner).differentialcreate$selection().forEach(pos -> wanted.add(pos.immutable()));
        for (var pos : java.util.List.copyOf(storage.getBlocks().keySet())) if (!wanted.contains(pos)) {
            forget(pos);
            if (storageInitialized && world.getBlockEntity(pos) == templates.get(pos)) storage.getStorage().unmount(world, storage.getBlocks().get(pos), pos, world.getBlockEntity(pos));
            storage.getBlocks().remove(pos); templates.remove(pos); changed = true;
        }
        for (var owner : owners) {
        var selection = ((PonderWorldSectionAccessor) owner).differentialcreate$selection();
        for (var cursor : selection) {
            var pos = cursor.immutable();
            var state = world.getBlockState(pos); var be = world.getBlockEntity(pos);
            var old = storage.getBlocks().get(pos);
            boolean rebind = actors.stream().anyMatch(actor -> actor.context.localPos.equals(pos) && actor.owner != owner);
            if (old != null && old.state().getBlock() == state.getBlock() && templates.get(pos) == be && !rebind) continue;
            forget(pos);
            templates.put(pos, be);
            var info = new StructureBlockInfo(pos, state, be == null ? null : be.saveWithFullMetadata(world.registryAccess()));
            storage.getBlocks().put(pos, info); changed = true;
            var behaviour = MovementBehaviour.REGISTRY.get(state);
            if (!(behaviour instanceof BlockBreakingMovementBehaviour)
                && !(behaviour instanceof com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour)) continue;
            var ctx = new MovementContext(world, info, storage);
            actors.add(new Actor(ctx, behaviour, owner)); PonderSimulation.of(world).actorDrive(ctx, this);
        }
        }
        if (changed) {
            // Refunds from cancelled jobs belong to the old mounted inventory.
            // Never unmount an old snapshot over a newly placed container.
            for (var info : storage.getBlocks().values()) if (storageInitialized && world.getBlockEntity(info.pos()) == mountedEntities.get(info.pos()))
                storage.getStorage().unmount(world, info, info.pos(), world.getBlockEntity(info.pos()));
            PonderMaterials.stock(world, storage.getBlocks().keySet());
            ((com.diliupengyun.differential_create.mixin.PonderMountedStorageAccessor) storage.getStorage()).differentialcreate$reassemble();
            for (var info : storage.getBlocks().values()) storage.getStorage().addBlock(world, info.state(), info.pos(), info.pos(), world.getBlockEntity(info.pos()));
            storage.getStorage().initialize();
            storageInitialized = true;
        }
    }
    void tick() {
        if (actuator.isRemoved() || world.getBlockEntity(actuator.getBlockPos()) != actuator) return;
        if (PonderSimulation.of(world).sections(actuator).isEmpty()) {
            PonderKinetics.of(world).setActorLoad(actuator.getBlockPos(), 0);
            return;
        }
        discover();
        double current = travel(); accepted = Double.isNaN(previousTravel) ? 0 : Math.abs(current - previousTravel); previousTravel = current;
        double load = 0;
        for (var actor : actors) {
            if (!actor.owner.isVisible() || !PonderSimulation.of(world).sections(actuator).contains(actor.owner)) continue;
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) actor.owner).transformMS(pose, 1);
            var matrix = pose.last().pose(); var inverse = new org.joml.Matrix4f(matrix).invert();
            var ctx = actor.context; var offset = actor.behaviour.getActiveAreaOffset(ctx);
            var local = Vec3.atCenterOf(ctx.localPos).add(offset);
            var point = new Vec3(matrix.transformPosition(new org.joml.Vector3f((float) local.x, (float) local.y, (float) local.z)));
            Vec3 start = actor.previous == null ? point : actor.previous;
            var delta = point.subtract(start); actor.previous = point;
            ctx.motion = delta;
            ctx.relativeMotion = new Vec3(inverse.transformDirection(new org.joml.Vector3f((float) delta.x, (float) delta.y, (float) delta.z)));
            ctx.rotation = v -> new Vec3(matrix.transformDirection(new org.joml.Vector3f((float) v.x, (float) v.y, (float) v.z)));
            ctx.disabled = false;
            if (world.getBlockEntity(ctx.localPos) instanceof com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity harvester) harvester.setAnimatedSpeed(ctx.getAnimationSpeed());
            if (world.getBlockEntity(ctx.localPos) instanceof com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity roller) roller.setAnimatedSpeed(ctx.getAnimationSpeed());
            for (var controlOwner : PonderSimulation.of(world).sections(actuator))
            for (var controlPos : ((PonderWorldSectionAccessor) controlOwner).differentialcreate$selection())
                if (world.getBlockEntity(controlPos) instanceof com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity control && control.disabled) {
                    var filter = control.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
                    if (filter == null || filter.getFilter().isEmpty() || filter.test(new net.minecraft.world.item.ItemStack(ctx.state.getBlock()))) ctx.disabled = true;
                }
            if (accepted <= 1e-10 || !actor.behaviour.isActive(ctx)) continue;
            int steps = Math.max(1, (int) Math.ceil(delta.length() / .03));
            for (int i = 1; i <= steps; i++) {
                ctx.position = start.lerp(point, (double) i / steps);
                var cell = BlockPos.containing(ctx.position); var work = ContraptionActorWork.of(ctx);
                if (!cell.equals(actor.cell)) {
                    work.cancel(); actor.cell = cell; actor.behaviour.visitNewPosition(ctx, cell); ctx.firstMovement = false;
                }
                double remaining = work.differentialcreate$remainingTravel();
                if (Double.isFinite(remaining)) {
                    load += work.torque(this) / steps;
                    work.differentialcreate$advanceTravel(Math.min(remaining, accepted / steps));
                }
            }
        }
        PonderKinetics.of(world).setActorLoad(actuator.getBlockPos(), load);
        for (var info : storage.getBlocks().values()) if (world.getBlockEntity(info.pos()) == templates.get(info.pos()))
            storage.getStorage().unmount(world, info, info.pos(), world.getBlockEntity(info.pos()));
    }
    @Override public AbstractContraptionEntity entity() { return entity; }
    @Override public AbstractContraptionEntity root() { return entity; }
    @Override public Direction.Axis axis() { return axis; }
    @Override public boolean rotation() { return rotating; }
    @Override public boolean stabilized() { return false; }
    @Override public double workRatio(MovementContext ctx) { return accepted > 0 ? ctx.motion.length() / accepted * 512 : 0; }
    @Override public double speed() { return PonderKinetics.of(world).outputSpeed(actuator.getBlockPos()); }
    @Override public double preferredSpeed() { return speed(); }
    @Override public ActorContactPath path(MovementContext ctx) { return null; }
    @Override public double travel() { var angle = PonderKinetics.of(world).outputAngle(actuator, 1); return angle == null ? 0 : angle / .3; }
    @Override public boolean motionActive() { return false; }
}
