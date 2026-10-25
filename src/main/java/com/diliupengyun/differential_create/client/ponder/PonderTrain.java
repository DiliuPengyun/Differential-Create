package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.*;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

/** A real Train, carriage, mounted inventories and steam simulation; the section is its visual proxy. */
final class PonderTrain {
    final Train train;
    private final CarriageContraption contraption;
    private final Carriage carriage;
    private final WorldSectionElement section;
    private final CarriageContraptionEntity entity;
    private final TrainMechanics.Engine boundary = new TrainMechanics.Engine();
    private final List<BlockPos> bogeys = new ArrayList<>();
    private final java.util.Map<BlockPos, AbstractBogeyBlockEntity> bogeyTemplates = new java.util.HashMap<>();
    private boolean stopAtEnd;
    boolean contains(BlockPos pos) { return bogeys.contains(pos); }
    boolean owns(BlockPos pos) { return contraption.getBlocks().containsKey(pos); }
    boolean live() {
        var selected = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
        return section.isVisible() && selected != null && bogeys.stream().anyMatch(pos -> selected.test(pos)
            && entity.level().getBlockEntity(pos) == bogeyTemplates.get(pos));
    }
    java.util.List<Double> reading() {
        var cs = TrainPhysics.carriage(carriage); var state = TrainPhysics.state(train);
        return java.util.List.of(train.speed, state.distance,
            cs.engines.values().stream().mapToDouble(engine -> Math.abs(engine.rpm())).sum(),
            cs.water.values().stream().mapToDouble(Double::doubleValue).sum());
    }
    private double throttle;
    private boolean brake = true;
    private int duration;
    private int direction = 1;
    private Double destination;
    TrainMechanics.Engine boundary() {
        if (!TrainPhysics.carriage(carriage).engines.isEmpty()) return null;
        // An off-stage towing source is an input boundary for infrastructure
        // lessons which do not show a locomotive. Its force and power are finite.
        boolean working = !contraption.getActors().isEmpty();
        boundary.ratio = working ? .125 : 1;
        double radius = TrainMechanics.WHEEL_RADIUS * boundary.ratio;
        boundary.stallTorque = duration > 0 && !brake ? direction * throttle * TrainPhysics.state(train).mass * (working ? 32 : 1.5) * radius : 0;
        boundary.power = Math.abs(boundary.stallTorque) * 4 / boundary.ratio;
        boundary.governor = 60 / (2 * Math.PI * radius);
        boundary.governorRange = 2;
        return boundary;
    }

    PonderTrain(PonderLevel world, WorldSectionElement section) {
        this.section = section;
        contraption = new CarriageContraption(Direction.EAST);
        contraption.anchor = BlockPos.ZERO;
        var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
        if (selection == null) throw new IllegalArgumentException("Empty train section");
        for (var cursor : selection) {
            var pos = cursor.immutable();
            var state = world.getBlockState(pos); if (state.isAir()) continue;
            var be = world.getBlockEntity(pos);
            contraption.getBlocks().put(pos, new StructureBlockInfo(pos, state,
                be == null ? null : be.saveWithFullMetadata(world.registryAccess())));
            var behaviour = com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(state);
            if (behaviour instanceof com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour
                || behaviour instanceof com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour)
                contraption.getActors().add(org.apache.commons.lang3.tuple.MutablePair.of(contraption.getBlocks().get(pos), null));
            if (be instanceof AbstractBogeyBlockEntity bogey) { bogeys.add(pos); bogeyTemplates.put(pos, bogey); }
        }
        if (bogeys.isEmpty()) throw new IllegalArgumentException("Train has no bogey");
        var first = (AbstractBogeyBlockEntity) world.getBlockEntity(bogeys.getFirst());
        var bogey = new CarriageBogey((AbstractBogeyBlock<?>) first.getBlockState().getBlock(), false, first.getBogeyData().copy());
        carriage = new Carriage(bogey, null, 0);
        PonderMaterials.stock(world, contraption.getBlocks().keySet());
        for (var info : contraption.getBlocks().values())
            carriage.storage.addBlock(world, info.state(), info.pos(), info.pos(), world.getBlockEntity(info.pos()));
        carriage.storage.initialize();
        var layout = TrainAssembly.capture(world, contraption);
        ((CarriageSteamState.Holder) carriage).differentialcreate$steam(new CarriageSteamState(layout));
        train = new Train(new UUID(0, 1), new UUID(0, 2), null, List.of(carriage), List.of(), true, 0);
        var physics = TrainPhysics.state(train);
        physics.level = world; physics.gear = layout.initialGear; physics.gearInitialized = true;
        entity = CarriageContraptionEntity.create(world, contraption);
        ((com.diliupengyun.differential_create.mixin.PonderCarriageAccessor) entity).differentialcreate$carriage(carriage);
        entity.trainId = train.id; entity.carriageIndex = 0;
        contraption.entity = entity; contraption.swapStorageAfterAssembly(entity);
        carriage.getDimensional(world).entity = new java.lang.ref.WeakReference<>(entity);
        contraption.startMoving(world);
    }

    void command(double opening, boolean braking, int ticks) {
        destination = null;
        throttle = Math.clamp(opening, 0, 1); brake = braking; duration = ticks;
    }
    void driver(PonderLevel world, double direction, int ticks) {
        double target = (destination != null && Math.signum(direction) == this.direction ? destination : section.getAnimatedOffset().x) + direction;
        this.direction = direction < 0 ? -1 : 1;
        var state = TrainPhysics.carriage(carriage);
        for (var pos : state.layout.levers) if (ModContent.GEAR_LEVER.has(world.getBlockState(pos)))
            world.setBlock(pos, world.getBlockState(pos).setValue(GearLeverBlock.GEAR, direction < 0 ? TrainMechanics.REVERSE_GEAR : 2), 3);
        command(state.engines.isEmpty() ? 1 : .04, false, Math.max(ticks, 4000)); stopAtEnd = false;
        destination = target;
    }
    boolean arrived() { return destination == null || Math.abs(destination - section.getAnimatedOffset().x) < .1 && Math.abs(train.speed) < .001; }

    void tick(PonderLevel world) {
        if (!live()) { TrainPhysics.state(train).distance = 0; return; }
        // OrientedContraptionEntity's anchor is position minus (.5, 0, .5).
        // Template coordinates already are scene coordinates; keep actor contact
        // on the same blocks as the rendered section, including roller side rows.
        var offset = section.getAnimatedOffset(); entity.setPos(offset.x + .5, offset.y, offset.z + .5);
        if (destination != null) {
            double remaining = this.direction * (destination - offset.x);
            var physics = TrainPhysics.state(train);
            double mass = Math.max(1, physics.mass), equivalentMass = mass;
            var engines = TrainPhysics.carriage(carriage).engines;
            if (engines.isEmpty()) equivalentMass += boundary.inertia / Math.pow(TrainMechanics.WHEEL_RADIUS * boundary.ratio, 2);
            else for (var engine : engines.values()) if (engine.ratio != 0)
                equivalentMass += engine.inertia / Math.pow(TrainMechanics.WHEEL_RADIUS * engine.ratio, 2);
            // The coupled input rotor also has to decelerate. A mass-only stop
            // estimate overshoots, then leaves the script waiting at the stop.
            double stopping = TrainMechanics.stoppingDistance(Math.abs(train.speed) * 20, 0) * equivalentMass / mass;
            brake = remaining <= stopping + Math.abs(train.speed) + .015;
        }
        int gear = TrainPhysics.state(train).gear;
        for (var pos : TrainPhysics.carriage(carriage).layout.levers)
            if (ModContent.GEAR_LEVER.has(world.getBlockState(pos))) {
                gear = world.getBlockState(pos).getValue(GearLeverBlock.GEAR); break;
            }
        if (gear != TrainPhysics.state(train).gear) TrainPhysics.shift(train, gear);
        train.throttle = duration > 0 ? throttle : 0;
        TrainPhysics.manual(train, gear == TrainMechanics.REVERSE_GEAR ? -1 : 1, brake);
        TrainPhysics.advance(train);
        for (var actor : contraption.getActors()) {
            var ctx = actor.getRight(); if (ctx == null) continue;
            if (world.getBlockEntity(ctx.localPos) instanceof com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity roller) roller.setAnimatedSpeed(ctx.getAnimationSpeed());
            if (world.getBlockEntity(ctx.localPos) instanceof com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity harvester) harvester.setAnimatedSpeed(ctx.getAnimationSpeed());
        }
        if (duration > 0 && --duration == 0 && stopAtEnd) brake = true;
        double distance = TrainPhysics.state(train).distance;
        section.setAnimatedOffset(section.getAnimatedOffset().add(distance, 0, 0), false);
        var state = TrainPhysics.carriage(carriage);
        for (var pos : bogeys) if (world.getBlockEntity(pos) instanceof AbstractBogeyBlockEntity be) {
            be.animate((float) -distance);
            var engine = state.engines.get(pos);
            if (engine != null) {
                var data = be.getBogeyData().copy();
                data.putDouble(SteamBogey.RPM, engine.rpm()); data.putDouble(SteamBogey.TURNS, engine.turns);
                be.setBogeyData(data);
            }
        }
        // These BEs only display the mounted inventories; the carriage remains their simulation owner.
        for (var info : contraption.getBlocks().values())
            carriage.storage.unmount(world, info, info.pos(), world.getBlockEntity(info.pos()));
        for (var entry : state.boilers.entrySet())
            if (world.getBlockEntity(entry.getKey()) instanceof FluidTankBlockEntity tank) {
                tank.boiler.read(entry.getValue().write(), tank.getTotalTankSize());
            }
    }
}
