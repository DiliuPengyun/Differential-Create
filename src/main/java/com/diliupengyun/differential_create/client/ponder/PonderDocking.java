package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.kinetics.portable.DockingState;
import com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Actual preview contact and speed feed the production docking lifecycle and brake. */
final class PonderDocking implements DockingState.Preview {
    private final PonderLevel world;
    private final WorldSectionElement moving;
    private final BlockPos station, endpoint, actuator;
    private final DockingState state;
    private final net.minecraft.world.level.block.entity.BlockEntity stationEntity, endpointEntity, actuatorEntity;
    private final java.util.Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> templates = new java.util.HashMap<>();
    private Vec3 previous;
    private boolean armed = true;
    java.util.List<Double> reading() { return java.util.List.of((double) state.phase.ordinal(), state.speed, state.pointSpeed, state.dissipated); }
    private final com.simibubi.create.content.contraptions.mounted.MountedContraption storage =
        new com.simibubi.create.content.contraptions.mounted.MountedContraption();

    PonderDocking(PonderLevel world, WorldSectionElement moving, BlockPos station, BlockPos endpoint, BlockPos actuator) {
        this.world = world; this.moving = moving; this.station = station.immutable();
        this.endpoint = endpoint.immutable(); this.actuator = actuator.immutable();
        stationEntity = world.getBlockEntity(station); endpointEntity = world.getBlockEntity(endpoint); actuatorEntity = world.getBlockEntity(actuator);
        state = ((PortableEndpoint) world.getBlockEntity(station)).differentialcreate$docking();
        storage.anchor = BlockPos.ZERO;
        var selection = ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) moving).differentialcreate$selection();
        for (var cursor : selection) {
            var pos = cursor.immutable(); var block = world.getBlockState(pos); var be = world.getBlockEntity(pos);
            var info = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(pos, block,
                be == null ? null : be.saveWithFullMetadata(world.registryAccess()));
            storage.getBlocks().put(pos, info);
            templates.put(pos, be);
            storage.getStorage().addBlock(world, block, pos, pos, be);
        }
        storage.getStorage().initialize();
        var entity = com.simibubi.create.content.contraptions.OrientedContraptionEntity.create(world, storage, Direction.SOUTH);
        storage.entity = entity;
        ((PortableEndpoint) stationEntity).differentialcreate$previewAnimation(state);
        ((PortableEndpoint) endpointEntity).differentialcreate$previewAnimation(state);
    }
    private Vec3 point() {
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) moving).transformMS(pose, 1);
        return new Vec3(pose.last().pose().transformPosition(new org.joml.Vector3f(endpoint.getX() + .5f, endpoint.getY() + .5f, endpoint.getZ() + .5f)));
    }
    private Direction facing() { return world.getBlockState(station).getValue(BlockStateProperties.FACING); }
    private double axial() { return point().subtract(Vec3.atCenterOf(station)).dot(Vec3.atLowerCornerOf(facing().getNormal())); }
    @Override public boolean live() { return moving.isVisible() && PonderSimulation.of(world).physical(world, station)
        && world.getBlockEntity(station) == stationEntity && world.getBlockEntity(endpoint) == endpointEntity
        && world.getBlockEntity(actuator) == actuatorEntity
        && ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) moving).differentialcreate$selection().test(endpoint); }
    BlockPos endpoint() { return endpoint; }
    void retire() {
        syncStorage();
        state.release("missing"); previous = null;
        for (var entity : new net.minecraft.world.level.block.entity.BlockEntity[]{stationEntity, endpointEntity}) {
            var bridge = (PortableEndpoint) entity;
            bridge.differentialcreate$animate(0, state.distance());
            bridge.differentialcreate$previewAnimation(null);
        }
        if (world.getBlockEntity(actuator) == actuatorEntity) PonderKinetics.of(world).setAttachmentLoad(actuator, 0);
    }
    private void syncStorage() {
        for (var info : storage.getBlocks().values()) if (world.getBlockEntity(info.pos()) == templates.get(info.pos()))
            storage.getStorage().unmount(world, info, info.pos(), world.getBlockEntity(info.pos()));
    }
    private Vec3 movingFacing() {
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) moving).transformMS(pose, 1);
        var direction = world.getBlockState(endpoint).getValue(BlockStateProperties.FACING);
        var normal = pose.last().pose().transformDirection(new org.joml.Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
        return new Vec3(normal);
    }
    private com.diliupengyun.differential_create.kinetics.portable.PortableDocking.Geometry geometry(double width, double alignment, double margin) {
        return com.diliupengyun.differential_create.kinetics.portable.PortableDocking.geometry(point(), movingFacing(),
            Vec3.atCenterOf(station), facing().getOpposite(), width, alignment, margin);
    }
    @Override public boolean valid() {
        return geometry(com.diliupengyun.differential_create.kinetics.portable.PortableDocking.CONTACT_WIDTH,
            com.diliupengyun.differential_create.kinetics.portable.PortableDocking.CONTACT_ALIGNMENT, 0).valid();
    }
    private double torque() {
        if (world.getBlockEntity(actuator) instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity) {
            var direction = ((com.simibubi.create.content.kinetics.base.IRotate) world.getBlockState(actuator).getBlock()).getRotationAxis(world.getBlockState(actuator));
            var axis = direction == Direction.Axis.X ? new Vec3(1,0,0) : direction == Direction.Axis.Y ? new Vec3(0,1,0) : new Vec3(0,0,1);
            var centre = ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) moving).differentialcreate$centre().add(moving.getAnimatedOffset());
            var radius = point().subtract(centre).add(movingFacing().scale(axial() / 2));
            return com.diliupengyun.differential_create.kinetics.portable.PortableDocking.LINEAR_FORCE
                * axis.cross(radius).length() * Math.toRadians(.3) * 1200
                + com.diliupengyun.differential_create.kinetics.portable.PortableDocking.TWIST_TORQUE;
        }
        return 1024;
    }
    @Override public void open() {
        ((PortableEndpoint) world.getBlockEntity(station)).differentialcreate$openStorage(storage, state.distance());
    }
    @Override public void close() {
        armed = false;
        if (world.getBlockEntity(station) instanceof PortableEndpoint bridge) bridge.differentialcreate$closeStorage();
    }
    void tick() {
        if (!live()) { retire(); return; }
        // Storyboard lever/wire operations change block states without running
        // server neighbour notifications. Feed that real circuit into the port.
        var owner = (PortableStorageInterfaceBlockEntity) stationEntity;
        if (owner.isPowered() != world.hasNeighborSignal(station)) {
            owner.neighbourChanged();
            if (!owner.isPowered()) armed = true;
        }
        Vec3 position = point();
        var broad = geometry(1.5, .875, 0);
        if (!geometry(1.75, .75, .25).valid()) armed = true;
        if (!owner.isPowered() && state.phase == DockingState.Phase.IDLE && broad.valid() && armed)
            state.preview(this, broad.axial());
        if (state.phase != DockingState.Phase.IDLE && !broad.valid())
            state.release(state.phase == DockingState.Phase.PREPARING ? "none" : "alignment");
        double speed = Math.abs(PonderKinetics.of(world).outputSpeed(actuator));
        double velocity = previous == null ? 0 : position.distanceTo(previous);
        state.observe(position, speed, velocity, valid(), torque());
        state.tick();
        PonderKinetics.updateGaugeComparators(world, stationEntity);
        PonderKinetics.of(world).setAttachmentLoad(actuator, state.occupied() ? state.torque : 0);
        previous = position;
        storage.entity.setPos(moving.getAnimatedOffset().x, moving.getAnimatedOffset().y, moving.getAnimatedOffset().z);
        syncStorage();
        // Both rendered heads show the same accepted connection, never a scripted Timer.
        for (var pos : new BlockPos[]{station, endpoint})
            if (world.getBlockEntity(pos) instanceof PortableEndpoint bridge)
                bridge.differentialcreate$animate(state.extension(), state.distance());
    }
}
